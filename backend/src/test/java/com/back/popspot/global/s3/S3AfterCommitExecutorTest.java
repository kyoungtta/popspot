package com.back.popspot.global.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.back.popspot.global.transaction.AfterCommitExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 커밋 이후 S3 작업의 재시도·메트릭·예외 미전파 검증.
 *
 * <p>{@link AfterCommitExecutor}는 트랜잭션 밖에서 즉시 실행하므로, 별도 트랜잭션 없이
 * 실물을 그대로 쓰면 등록된 작업이 바로 돌아 재시도 동작을 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("커밋 이후 S3 작업 재시도 테스트")
class S3AfterCommitExecutorTest {

	private static final String SOURCE_KEY = "temp/abc.png";
	private static final String DEST_KEY = "popup/1/abc.png";

	@Mock
	private S3Service s3Service;

	private MeterRegistry meterRegistry;
	private S3AfterCommitExecutor executor;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		executor = new S3AfterCommitExecutor(s3Service, new AfterCommitExecutor(), meterRegistry);
	}

	@Nested
	@DisplayName("move")
	class Move {

		@Test
		@DisplayName("1회차 실패 → 2회차 성공이면 정상 처리되고 재시도 메트릭이 1 올라간다")
		void 재시도후_성공() {
			doThrow(s3Error()).doNothing().when(s3Service).move(SOURCE_KEY, DEST_KEY);

			executor.moveAfterCommit(SOURCE_KEY, DEST_KEY);

			verify(s3Service, times(2)).move(SOURCE_KEY, DEST_KEY);
			assertThat(retryCount("move")).isEqualTo(1.0);
			assertThat(failureCount("move")).as("성공했으므로 실패 메트릭은 오르지 않는다").isZero();
		}

		@Test
		@DisplayName("3회 모두 실패하면 예외가 전파되지 않고 실패 메트릭이 1 올라간다")
		void 전부_실패해도_예외_미전파() {
			doThrow(s3Error()).when(s3Service).move(SOURCE_KEY, DEST_KEY);

			assertThatCode(() -> executor.moveAfterCommit(SOURCE_KEY, DEST_KEY))
				.as("afterCommit은 롤백이 불가능하므로 예외를 던지면 안 된다")
				.doesNotThrowAnyException();

			verify(s3Service, times(3)).move(SOURCE_KEY, DEST_KEY);
			assertThat(retryCount("move")).as("1·2회차 실패 후 재시도 2회").isEqualTo(2.0);
			assertThat(failureCount("move")).isEqualTo(1.0);
		}

		@Test
		@DisplayName("첫 시도에 성공하면 재시도하지 않고 메트릭도 오르지 않는다")
		void 첫시도_성공() {
			doNothing().when(s3Service).move(SOURCE_KEY, DEST_KEY);

			executor.moveAfterCommit(SOURCE_KEY, DEST_KEY);

			verify(s3Service, times(1)).move(SOURCE_KEY, DEST_KEY);
			assertThat(retryCount("move")).isZero();
			assertThat(failureCount("move")).isZero();
		}
	}

	@Nested
	@DisplayName("delete")
	class Delete {

		@Test
		@DisplayName("1회차 실패 → 2회차 성공이면 정상 처리되고 재시도 메트릭이 1 올라간다")
		void 재시도후_성공() {
			doThrow(s3Error()).doNothing().when(s3Service).delete(DEST_KEY);

			executor.deleteAfterCommit(DEST_KEY);

			verify(s3Service, times(2)).delete(DEST_KEY);
			assertThat(retryCount("delete")).isEqualTo(1.0);
			assertThat(failureCount("delete")).isZero();
		}

		@Test
		@DisplayName("3회 모두 실패하면 예외가 전파되지 않고 실패 메트릭이 1 올라간다")
		void 전부_실패해도_예외_미전파() {
			doThrow(s3Error()).when(s3Service).delete(DEST_KEY);

			assertThatCode(() -> executor.deleteAfterCommit(DEST_KEY))
				.doesNotThrowAnyException();

			verify(s3Service, times(3)).delete(DEST_KEY);
			assertThat(retryCount("delete")).isEqualTo(2.0);
			assertThat(failureCount("delete")).isEqualTo(1.0);
		}

		@Test
		@DisplayName("키가 null이면 아무 것도 하지 않는다")
		void null_키는_무시() {
			executor.deleteAfterCommit(null);

			verify(s3Service, times(0)).delete(anyString());
		}

	}

	private static S3Exception s3Error() {
		return (S3Exception)S3Exception.builder().message("s3 unavailable").build();
	}

	private double retryCount(String operation) {
		return counter(S3AfterCommitExecutor.RETRY_METRIC, operation);
	}

	private double failureCount(String operation) {
		return counter(S3AfterCommitExecutor.FAILURE_METRIC, operation);
	}

	private double counter(String name, String operation) {
		return meterRegistry.find(name).tag("operation", operation).counter() == null
			? 0.0
			: meterRegistry.find(name).tag("operation", operation).counter().count();
	}
}
