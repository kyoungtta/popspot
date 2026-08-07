package com.back.popspot.global.redis.rebuild;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRebuildGate implements RebuildGate, RebuildLease.Releaser {

	private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
		script("redis/rebuild-release.lua");
	private static final DefaultRedisScript<Long> RENEW_SCRIPT =
		script("redis/rebuild-renew.lua");

	private static DefaultRedisScript<Long> script(String location) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(location));
		script.setResultType(Long.class);
		return script;
	}

	private final StringRedisTemplate redisTemplate;
	private final RebuildProperties properties;

	@Override
	public Optional<RebuildLease> tryBegin(RebuildScope scope) {
		String token = UUID.randomUUID().toString();
		// SET NX PX — 게이트 표시와 다중 인스턴스 상호배제를 한 번에.
		// 실패(예외)는 삼키지 않는다. Redis에 쓸 수 없으면 재구축 자체가 불가능하므로
		// 호출자가 알아야 한다.
		Boolean acquired = redisTemplate.opsForValue()
			.setIfAbsent(scope.key(), token, Duration.ofSeconds(properties.ttlSeconds()));

		if (!Boolean.TRUE.equals(acquired)) {
			log.info("[RebuildGate] 게이트 선점 실패 — 이미 재구축 중: scope={}", scope.name());
			return Optional.empty();
		}

		log.info("[RebuildGate] 게이트 획득: scope={}, ttl={}s", scope.name(), properties.ttlSeconds());
		return Optional.of(new RebuildLease(scope, token, this));
	}

	@Override
	public boolean isRebuilding(RebuildScope scope) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(scope.key()));
		} catch (RuntimeException e) {
			// fail-open. Redis 장애는 CB와 도메인별 in-memory 플래그가 따로 막는다.
			// 여기서 fail-closed로 두면 Redis가 잠깐 흔들릴 때마다 정상 트래픽까지 503이 된다.
			log.warn("[RebuildGate] 게이트 조회 실패 — 통과시킴(fail-open): scope={}", scope.name(), e);
			return false;
		}
	}

	@Override
	public void release(RebuildScope scope, String token) {
		try {
			Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(scope.key()), token);
			if (!Long.valueOf(1L).equals(released)) {
				// TTL이 먼저 만료됐다는 뜻 — 재구축이 TTL보다 오래 걸렸고, 그 구간은
				// 게이트 없이 blind write가 노출됐다. TTL 상향이나 renew()가 필요한 신호.
				log.warn("[RebuildGate] 해제 대상 없음 — TTL 만료 추정: scope={}", scope.name());
			}
		} catch (RuntimeException e) {
			// 해제 실패해도 TTL이 있으므로 게이트가 영구히 남지는 않는다.
			log.error("[RebuildGate] 게이트 해제 실패 — TTL 만료까지 대기: scope={}", scope.name(), e);
		}
	}

	@Override
	public boolean renew(RebuildScope scope, String token) {
		Long renewed = redisTemplate.execute(
			RENEW_SCRIPT,
			List.of(scope.key()),
			token,
			String.valueOf(properties.ttlSeconds())
		);
		return Long.valueOf(1L).equals(renewed);
	}
}
