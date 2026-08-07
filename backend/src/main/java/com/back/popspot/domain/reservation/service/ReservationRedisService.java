package com.back.popspot.domain.reservation.service;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.back.popspot.domain.reservation.dto.SlotDecrementResult;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationRedisService {

	private static final DefaultRedisScript<List> DECR_UNLESS_REBUILDING_SCRIPT = decrUnlessRebuildingScript();

	private static DefaultRedisScript<List> decrUnlessRebuildingScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/decr-unless-rebuilding.lua"));
		script.setResultType(List.class);
		return script;
	}

	private final RedisTemplate<String, Long> redisTemplate;

	/**
	 * 예약 생성 시 정원 선차감. 재구축 중이면 차감하지 않고 그 사실을 알린다.
	 *
	 * <p>게이트 검사와 DECR을 Lua로 묶어, 검사 통과 직후 시작된 재구축에 차감이 덮어써지는
	 * 창을 없앤다. 게이트 EXISTS를 따로 치지 않으므로 왕복도 늘지 않는다.
	 */
	@CircuitBreaker(name = "redisReservation", fallbackMethod = "decrementFallback")
	@SuppressWarnings("unchecked")
	public SlotDecrementResult decrementUnlessRebuilding(String remainingKey, String rebuildGateKey) {
		List<Long> result = redisTemplate.execute(
			DECR_UNLESS_REBUILDING_SCRIPT,
			List.of(remainingKey, rebuildGateKey)
		);
		if (result == null || result.isEmpty()) {
			// 스크립트가 값을 못 돌려준 비정상 상황 — 기존 null 계약(미초기화/차감 실패)과 동일 취급
			return SlotDecrementResult.decremented(null);
		}
		if (result.get(0) == 0L) {
			return SlotDecrementResult.rebuildInProgress();
		}
		return SlotDecrementResult.decremented(result.get(1));
	}

	public SlotDecrementResult decrementFallback(String remainingKey, String rebuildGateKey, Exception e) {
		log.error("Redis 장애 감지 - 예약 차단: key={}", remainingKey, e);
		throw new BusinessException(ErrorCode.RESERVATION_TEMPORARILY_UNAVAILABLE);
	}

	// 취소/만료 시 정원 복구
	@CircuitBreaker(name = "redisReservation", fallbackMethod = "incrementFallback")
	public Long increment(String key) {
		return redisTemplate.opsForValue().increment(key);
	}

	public Long incrementFallback(String key, Exception e) {
		// INCR 실패는 과소판매로 남기고 재구축에서 복구
		log.error("Redis INCR 실패 - 과소판매 상태로 남김: key={}", key, e);
		return null;
	}
}