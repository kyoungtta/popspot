package com.back.popspot.global.queue.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.domain.queue.entity.PopupQueueEntry;
import com.back.popspot.domain.queue.entity.QueueEntryStatus;
import com.back.popspot.domain.queue.repository.PopupQueueEntryRepository;
import com.back.popspot.global.queue.config.WaitingQueueProperties;
import com.back.popspot.global.redis.RedisKeys;
import com.back.popspot.global.redis.rebuild.RebuildGate;
import com.back.popspot.global.redis.rebuild.RebuildLease;
import com.back.popspot.global.redis.rebuild.RebuildScope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueRecoveryService {

	private static final DefaultRedisScript<Long> REBUILD_WAITING_QUEUE_SCRIPT = rebuildWaitingQueueScript();

	private static DefaultRedisScript<Long> rebuildWaitingQueueScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/rebuild-waiting-queue.lua"));
		script.setResultType(Long.class);
		return script;
	}

	private final PopupQueueEntryRepository entryRepository;
	private final StringRedisTemplate redisTemplate;
	private final PopupStoreRepository popupStoreRepository;
	private final WaitingQueueProperties properties;
	private final RebuildGate rebuildGate;

	public void recoverAll() {
		LocalDateTime now = LocalDateTime.now();
		popupStoreRepository.findOpen(now, Pageable.unpaged())
			.getContent()
			.forEach(popup -> recover(popup.getId(), popup.getReservationEndAt()));
	}

	public void recover(long popupId, LocalDateTime reservationEndAt) {
		RebuildScope scope = RebuildScope.popupQueue(popupId);

		// 두 층이 서로 다른 구간을 막는다.
		//   게이트 — DB 읽기부터 Redis 쓰기까지. 이 구간에 들어온 enqueue는 DB에는 남지만
		//            방금 읽은 목록에는 없으므로, 게이트 없이는 뒤이은 DEL에 지워진다.
		//   Lua   — Redis 쓰기 내부(DEL~ZADD~SET seq~EXPIREAT). 예전 executePipelined는
		//            원자적이지 않아 DEL만 나가고 ZADD가 도착하기 전에 끼어들 수 있었다.
		// 게이트는 반드시 DB 읽기 전에 잡아야 한다.
		Optional<RebuildLease> lease = rebuildGate.tryBegin(scope);
		if (lease.isEmpty()) {
			log.info("[QueueRecovery] popupId={} — 다른 인스턴스가 재구축 중, 스킵", popupId);
			return;
		}

		try (RebuildLease ignored = lease.get()) {
			doRecover(popupId, reservationEndAt);
		}
	}

	private void doRecover(long popupId, LocalDateTime reservationEndAt) {
		Optional<Long> maxSeq = entryRepository.findMaxSeqByPopupId(popupId);
		if (maxSeq.isEmpty()) {
			log.info("[QueueRecovery] popupId={} — DB에 항목 없음, 스킵", popupId);
			return;
		}

		List<PopupQueueEntry> waitingEntries =
			entryRepository.findByPopupIdAndStatusOrderBySeqAsc(popupId, QueueEntryStatus.WAITING);

		String zsetKey = RedisKeys.popupWaitingQueue(popupId);
		String seqKey = RedisKeys.popupQueueSeq(popupId);
		Instant expireAt = properties.computeExpireAt(reservationEndAt);

		Long restored = redisTemplate.execute(
			REBUILD_WAITING_QUEUE_SCRIPT,
			List.of(zsetKey, seqKey),
			buildScriptArgs(waitingEntries, maxSeq.get(), expireAt)
		);

		// DEL 직후 ZADD하므로 추가 수 == WAITING 건수여야 한다.
		// 어긋나면 같은 popupId에 동일 userId WAITING 행이 중복 존재해 ZSET에서 합쳐진 것이다.
		if (restored != null && restored != waitingEntries.size()) {
			log.warn("[QueueRecovery] popupId={} — WAITING {}건 중 {}건만 ZSET에 추가됨 (userId 중복 의심)",
				popupId, waitingEntries.size(), restored);
		}

		log.info("[QueueRecovery] popupId={} 복구 완료 — WAITING={}건, MAX seq={}, TTL={}",
			popupId, waitingEntries.size(), maxSeq.get(), expireAt);
	}

	/**
	 * 스크립트 ARGV를 만든다. [MAX seq, expireAt(초), score, member, score, member, ...] 순서로,
	 * score/member 쌍이 반드시 짝을 이뤄야 한다 (스크립트가 2칸씩 전진하며 읽는다).
	 */
	private Object[] buildScriptArgs(List<PopupQueueEntry> waitingEntries, long maxSeq, Instant expireAt) {
		Object[] args = new Object[2 + waitingEntries.size() * 2];
		args[0] = String.valueOf(maxSeq);
		args[1] = String.valueOf(expireAt.getEpochSecond());

		int index = 2;
		for (PopupQueueEntry entry : waitingEntries) {
			args[index++] = String.valueOf(entry.getSeq());     // score
			args[index++] = entry.getUserId().toString();       // member
		}
		return args;
	}
}
