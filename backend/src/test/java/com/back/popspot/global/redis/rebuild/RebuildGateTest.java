package com.back.popspot.global.redis.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.back.popspot.domain.reservation.dto.SlotDecrementResult;
import com.back.popspot.domain.reservation.service.ReservationRedisService;
import com.back.popspot.global.redis.RedisKeys;

/**
 * 재구축 게이트 통합 테스트 (실제 Redis).
 *
 * <p>게이트의 계약 — 상호배제, TTL 자동 해제, 토큰 소유 검사 — 과 이를 소비하는 예약 DECR 경로를
 * 함께 검증한다. Spring 컨텍스트 없이 {@code RedisConfig}와 동일한 방식으로 템플릿을 직접 만든다.
 */
@Testcontainers
@DisplayName("재구축 게이트 통합 테스트")
class RebuildGateTest {

	private static final long TTL_SECONDS = 30L;
	private static final Long SLOT_ID = 1L;

	@Container
	static final GenericContainer<?> redis =
		new GenericContainer<>(DockerImageName.parse("redis:7.2")).withExposedPorts(6379);

	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate stringRedisTemplate;
	private RedisTemplate<String, Long> longRedisTemplate;
	private RedisRebuildGate gate;
	private ReservationRedisService reservationRedisService;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();

		stringRedisTemplate = new StringRedisTemplate(connectionFactory);

		// RedisConfig의 RedisTemplate<String, Long> 설정과 동일하게 맞춘다
		longRedisTemplate = new RedisTemplate<>();
		longRedisTemplate.setConnectionFactory(connectionFactory);
		longRedisTemplate.setKeySerializer(new StringRedisSerializer());
		longRedisTemplate.setValueSerializer(new GenericToStringSerializer<>(Long.class));
		longRedisTemplate.afterPropertiesSet();

		gate = new RedisRebuildGate(stringRedisTemplate, new RebuildProperties(TTL_SECONDS));
		reservationRedisService = new ReservationRedisService(longRedisTemplate);

		flushAll();
	}

	@AfterEach
	void tearDown() {
		flushAll();
		connectionFactory.destroy();
	}

	// ── 게이트 계약 ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("같은 스코프는 한 번만 선점된다 — 두 번째 tryBegin은 empty")
	void tryBegin_isMutuallyExclusive() {
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);

		Optional<RebuildLease> first = gate.tryBegin(scope);
		Optional<RebuildLease> second = gate.tryBegin(scope);

		assertThat(first).isPresent();
		assertThat(second).as("이미 재구축 중이면 선점 실패").isEmpty();
	}

	@Test
	@DisplayName("서로 다른 스코프는 동시에 선점할 수 있다")
	void tryBegin_differentScopesDoNotBlockEachOther() {
		assertThat(gate.tryBegin(RebuildScope.reservationSlot(1L))).isPresent();
		assertThat(gate.tryBegin(RebuildScope.reservationSlot(2L))).isPresent();
		assertThat(gate.tryBegin(RebuildScope.popupQueue(1L))).isPresent();
	}

	@Test
	@DisplayName("임대를 닫으면 게이트가 풀려 다시 선점할 수 있다")
	void close_releasesGate() {
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);

		try (RebuildLease ignored = gate.tryBegin(scope).orElseThrow()) {
			assertThat(gate.isRebuilding(scope)).isTrue();
		}

		assertThat(gate.isRebuilding(scope)).isFalse();
		assertThat(gate.tryBegin(scope)).isPresent();
	}

	@Test
	@DisplayName("게이트에는 TTL이 걸려 있어 해제에 실패해도 영구히 남지 않는다")
	void tryBegin_setsTtl() {
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);

		gate.tryBegin(scope).orElseThrow();

		Long ttl = stringRedisTemplate.getExpire(scope.key(), TimeUnit.SECONDS);
		assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(TTL_SECONDS);
	}

	@Test
	@DisplayName("남의 토큰으로는 게이트를 해제할 수 없다")
	void release_onlyOwnToken() {
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);
		gate.tryBegin(scope).orElseThrow();

		// TTL 만료 후 다른 인스턴스가 새로 잡은 상황을 흉내 — 옛 토큰으로 해제 시도
		new RebuildLease(scope, "stale-token", gate).close();

		assertThat(gate.isRebuilding(scope))
			.as("남의 토큰으로 해제되면 재구축 중인 다른 인스턴스가 무방비가 된다")
			.isTrue();
	}

	@Test
	@DisplayName("renew는 내 토큰일 때만 성공한다")
	void renew_onlyOwnToken() {
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);
		RebuildLease lease = gate.tryBegin(scope).orElseThrow();

		assertThat(lease.renew()).isTrue();
		assertThat(gate.renew(scope, "stale-token")).isFalse();
	}

	@Test
	@DisplayName("isRebuilding은 게이트 유무를 그대로 반영한다")
	void isRebuilding_reflectsGate() {
		RebuildScope scope = RebuildScope.popupQueue(99L);

		assertThat(gate.isRebuilding(scope)).isFalse();
		gate.tryBegin(scope).orElseThrow();
		assertThat(gate.isRebuilding(scope)).isTrue();
	}

	// ── 예약 DECR 경로 ────────────────────────────────────────────────────────

	@Test
	@DisplayName("재구축 중에는 차감하지 않고 rebuilding을 알린다 — 카운터가 그대로 남는다")
	void decrement_blockedWhileRebuilding() {
		String remainingKey = RedisKeys.reservationSlotRemaining(SLOT_ID);
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);
		longRedisTemplate.opsForValue().set(remainingKey, 10L);

		gate.tryBegin(scope).orElseThrow();
		SlotDecrementResult result =
			reservationRedisService.decrementUnlessRebuilding(remainingKey, scope.key());

		assertThat(result.rebuilding()).isTrue();
		assertThat(result.remaining()).isNull();
		assertThat(longRedisTemplate.opsForValue().get(remainingKey))
			.as("재구축 중 차감했다면 곧이어 덮어써져 과다판매만 남는다")
			.isEqualTo(10L);
	}

	@Test
	@DisplayName("재구축이 끝나면 다시 정상 차감된다")
	void decrement_resumesAfterRebuild() {
		String remainingKey = RedisKeys.reservationSlotRemaining(SLOT_ID);
		RebuildScope scope = RebuildScope.reservationSlot(SLOT_ID);
		longRedisTemplate.opsForValue().set(remainingKey, 10L);

		try (RebuildLease ignored = gate.tryBegin(scope).orElseThrow()) {
			assertThat(reservationRedisService.decrementUnlessRebuilding(remainingKey, scope.key()).rebuilding())
				.isTrue();
		}

		SlotDecrementResult result =
			reservationRedisService.decrementUnlessRebuilding(remainingKey, scope.key());

		assertThat(result.rebuilding()).isFalse();
		assertThat(result.remaining()).isEqualTo(9L);
		assertThat(longRedisTemplate.opsForValue().get(remainingKey)).isEqualTo(9L);
	}

	@Test
	@DisplayName("미초기화 키는 기존 계약대로 음수를 돌려준다 (정원 초과 분기로 처리됨)")
	void decrement_uninitializedKeyReturnsNegative() {
		String remainingKey = RedisKeys.reservationSlotRemaining(404L);
		RebuildScope scope = RebuildScope.reservationSlot(404L);

		SlotDecrementResult result =
			reservationRedisService.decrementUnlessRebuilding(remainingKey, scope.key());

		assertThat(result.rebuilding()).isFalse();
		assertThat(result.remaining()).isNegative();
	}

	private void flushAll() {
		stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}
}
