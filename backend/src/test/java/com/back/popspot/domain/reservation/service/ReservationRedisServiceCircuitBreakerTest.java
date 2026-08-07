package com.back.popspot.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.back.popspot.domain.reservation.dto.SlotDecrementResult;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;

/**
 * {@link ReservationRedisService} 의 Resilience4j 서킷브레이커 동작 단위 테스트.
 *
 * <p>{@code @CircuitBreaker} 는 Spring AOP 프록시로 동작하므로 순수 Mockito 로는 발동하지 않는다.
 * 따라서 DB/실제 Redis 없이 <b>AOP + Resilience4j 서킷브레이커 자동구성만</b> 올린 최소 슬라이스 컨텍스트를 띄우고,
 * {@link RedisTemplate} 는 {@link MockitoBean} 으로 모킹해 Redis 장애를 시뮬레이션한다.
 *
 * <p>테스트가 빨리 열리도록 sliding-window/minimum-calls 를 작게 오버라이드하고,
 * 열린 상태가 유지되도록 wait-duration 을 길게 둔다. 서킷 상태는 테스트 간 공유되므로
 * {@link #setUp()} 에서 매번 reset 한다.
 */
@SpringBootTest(
	classes = ReservationRedisServiceCircuitBreakerTest.TestConfig.class,
	properties = {
		"resilience4j.circuitbreaker.instances.redisReservation.sliding-window-type=COUNT_BASED",
		"resilience4j.circuitbreaker.instances.redisReservation.sliding-window-size=4",
		"resilience4j.circuitbreaker.instances.redisReservation.minimum-number-of-calls=4",
		"resilience4j.circuitbreaker.instances.redisReservation.failure-rate-threshold=50",
		"resilience4j.circuitbreaker.instances.redisReservation.wait-duration-in-open-state=60s",
		"resilience4j.circuitbreaker.instances.redisReservation.register-health-indicator=false"
	}
)
class ReservationRedisServiceCircuitBreakerTest {

	private static final String KEY = "popspot:reservation:slot:1:remaining";
	private static final String GATE_KEY = "rebuild:reservation:slot:1";
	private static final String CB_NAME = "redisReservation";
	// minimum-number-of-calls=4, failure-rate=50% → 연속 4회 실패면 OPEN
	private static final int CALLS_TO_TRIP = 4;

	@Configuration
	@Import(ReservationRedisService.class)
	@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
	static class TestConfig {
	}

	@MockitoBean
	private RedisTemplate<String, Long> redisTemplate;

	@Autowired
	private ReservationRedisService reservationRedisService;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	private ValueOperations<String, Long> valueOperations;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		// 서킷 상태는 컨텍스트(빈) 단위로 공유되므로 테스트마다 CLOSED 로 초기화한다.
		circuitBreakerRegistry.circuitBreaker(CB_NAME).reset();

		valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	@DisplayName("decrementUnlessRebuilding: Redis 장애가 나면 fallback 이 RESERVATION_TEMPORARILY_UNAVAILABLE 예외를 던진다")
	void decrement_redisFailure_throwsTemporarilyUnavailable() {
		givenScriptFails();

		assertThatThrownBy(() -> reservationRedisService.decrementUnlessRebuilding(KEY, GATE_KEY))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode())
			.isEqualTo(ErrorCode.RESERVATION_TEMPORARILY_UNAVAILABLE);
	}

	@Test
	@DisplayName("increment: Redis 장애가 나면 fallback 이 null 을 반환한다(과소판매로 남김)")
	void increment_redisFailure_returnsNull() {
		when(valueOperations.increment(KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

		Long result = reservationRedisService.increment(KEY);

		assertThat(result).isNull();
	}

	@Test
	@DisplayName("서킷이 OPEN 되면 Redis 연결을 시도하지 않고 즉시 fallback 으로 차단한다")
	void decrement_whenCircuitOpen_doesNotCallRedis() {
		givenScriptFails();

		// 1) 연속 실패로 서킷을 OPEN 으로 만든다.
		for (int i = 0; i < CALLS_TO_TRIP; i++) {
			assertThatThrownBy(() -> reservationRedisService.decrementUnlessRebuilding(KEY, GATE_KEY))
				.isInstanceOf(BusinessException.class);
		}

		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// 2) 지금까지의 Redis 호출 기록을 비우고, OPEN 상태에서 한 번 더 호출한다.
		clearInvocations(redisTemplate);

		assertThatThrownBy(() -> reservationRedisService.decrementUnlessRebuilding(KEY, GATE_KEY))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode())
			.isEqualTo(ErrorCode.RESERVATION_TEMPORARILY_UNAVAILABLE);

		// 3) OPEN 이면 메서드 본문(=Redis 접근)이 실행되지 않아야 한다.
		verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
	}

	@Test
	@DisplayName("재구축 중이면 차감하지 않고 rebuilding=true 를 반환한다 (서킷은 실패로 기록하지 않음)")
	@SuppressWarnings("unchecked")
	void decrement_whenRebuilding_returnsRebuildingWithoutTrippingCircuit() {
		// Lua가 {0} 을 돌려주는 상황 = 게이트가 잡혀 있어 차감을 건너뛴 경우
		when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(List.of(0L));

		SlotDecrementResult result = reservationRedisService.decrementUnlessRebuilding(KEY, GATE_KEY);

		assertThat(result.rebuilding()).isTrue();
		assertThat(result.remaining()).isNull();
		// 재구축은 Redis 장애가 아니므로 서킷이 열려서는 안 된다
		assertThat(circuitBreakerRegistry.circuitBreaker(CB_NAME).getState())
			.isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	@DisplayName("재구축 중이 아니면 DECR 결과를 그대로 돌려준다")
	@SuppressWarnings("unchecked")
	void decrement_whenNotRebuilding_returnsRemaining() {
		when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(List.of(1L, 9L));

		SlotDecrementResult result = reservationRedisService.decrementUnlessRebuilding(KEY, GATE_KEY);

		assertThat(result.rebuilding()).isFalse();
		assertThat(result.remaining()).isEqualTo(9L);
	}

	@SuppressWarnings("unchecked")
	private void givenScriptFails() {
		when(redisTemplate.execute(any(RedisScript.class), anyList()))
			.thenThrow(new RedisConnectionFailureException("redis down"));
	}
}
