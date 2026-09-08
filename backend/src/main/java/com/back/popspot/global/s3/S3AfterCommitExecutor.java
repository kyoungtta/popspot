package com.back.popspot.global.s3;

import org.springframework.stereotype.Component;

import com.back.popspot.global.transaction.AfterCommitExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 커밋 이후 S3 이동·삭제를 재시도와 함께 실행한다.
 *
 * <p>move가 최종 실패하면 DB의 imageKey는 새 경로를 가리키는데 그 경로에 파일이 없는 상태가 되고,
 * 사용자에게는 깨진 이미지로 드러난다. 로그만 남기면 누군가 들여다볼 때까지 아무도 모르므로
 * 재시도 발생과 최종 실패를 각각 메트릭으로 노출한다 — {@value #RETRY_METRIC},
 * {@value #FAILURE_METRIC} (태그 {@code operation}=move|delete).
 *
 * <p><b>최종 실패해도 예외를 던지지 않는다.</b> afterCommit 시점은 이미 커밋이 끝나 롤백이
 * 불가능하다. 여기서 예외를 던지면 사용자에게는 등록 실패로 보이지만 DB에는 저장된 상태라,
 * 재등록으로 인한 중복만 유발한다.
 *
 * <p>재시도 지연을 짧게 잡은 이유: afterCommit은 응답 경로 안이라 길게 잡으면 사용자 응답이
 * 그만큼 밀린다. 최악의 경우에도 {@code 100 + 200 = 300ms} 추가가 상한이다.
 *
 * <p>두 작업 모두 재시도가 안전하다. delete는 없는 키에 대해서도 성공하고, move는 copy 후
 * 원본 delete인데 copy가 같은 대상으로 반복돼도 결과가 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3AfterCommitExecutor {

	static final String RETRY_METRIC = "s3.operation.retry";
	static final String FAILURE_METRIC = "s3.operation.failure";

	private static final String OPERATION_TAG = "operation";
	private static final String MOVE = "move";
	private static final String DELETE = "delete";

	/** 최초 1회 + 재시도 2회 */
	private static final int MAX_ATTEMPTS = 3;

	/** 시도 사이 대기(ms). 인덱스 = 직전 시도 횟수 - 1 */
	private static final long[] RETRY_DELAYS_MILLIS = {100L, 200L};

	private final S3Service s3Service;
	private final AfterCommitExecutor afterCommitExecutor;
	private final MeterRegistry meterRegistry;

	public void moveAfterCommit(String sourceKey, String destKey) {
		afterCommitExecutor.execute(() -> runWithRetry(
			MOVE,
			() -> s3Service.move(sourceKey, destKey),
			"sourceKey=" + sourceKey + ", destKey=" + destKey
		));
	}

	public void deleteAfterCommit(String key) {
		if (key == null) {
			return;
		}
		afterCommitExecutor.execute(() -> runWithRetry(DELETE, () -> s3Service.delete(key), "key=" + key));
	}

	private void runWithRetry(String operation, Runnable task, String context) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				task.run();
				return;
			} catch (RuntimeException e) {
				if (attempt == MAX_ATTEMPTS) {
					recordFailure(operation);
					log.error(
						"[S3_AFTER_COMMIT_FAILED] S3 {} {}회 시도 모두 실패 — DB와 실제 파일이 불일치할 수 있음: {}",
						operation, MAX_ATTEMPTS, context, e
					);
					return;
				}

				long delay = RETRY_DELAYS_MILLIS[attempt - 1];
				recordRetry(operation);
				log.warn(
					"[S3_AFTER_COMMIT_RETRY] S3 {} {}회차 실패 — {}ms 후 재시도: {}",
					operation, attempt, delay, context, e
				);

				if (!sleep(delay)) {
					recordFailure(operation);
					log.error(
						"[S3_AFTER_COMMIT_FAILED] S3 {} 재시도 대기 중 인터럽트로 중단 — DB와 실제 파일이 불일치할 수 있음: {}",
						operation, context
					);
					return;
				}
			}
		}
	}

	/** @return 정상 대기 완료 여부. 인터럽트되면 false를 돌려 재시도를 중단시킨다. */
	private boolean sleep(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void recordRetry(String operation) {
		meterRegistry.counter(RETRY_METRIC, OPERATION_TAG, operation).increment();
	}

	private void recordFailure(String operation) {
		meterRegistry.counter(FAILURE_METRIC, OPERATION_TAG, operation).increment();
	}
}
