package com.back.popspot.global.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@DisplayName("커밋 이후 실행 등록 테스트")
class AfterCommitExecutorTest {

	private final AfterCommitExecutor executor = new AfterCommitExecutor();

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("트랜잭션 밖에서 호출하면 즉시 실행한다")
	void 트랜잭션_밖에서는_즉시_실행() {
		AtomicInteger runCount = new AtomicInteger();

		executor.execute(runCount::incrementAndGet);

		assertThat(runCount.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("트랜잭션 안에서 호출하면 커밋 이후까지 실행을 미룬다")
	void 트랜잭션_안에서는_커밋후_실행() {
		TransactionSynchronizationManager.initSynchronization();
		AtomicInteger runCount = new AtomicInteger();

		executor.execute(runCount::incrementAndGet);

		assertThat(runCount.get()).as("등록 시점에는 실행되지 않아야 한다").isZero();

		TransactionSynchronizationUtils.triggerAfterCommit();

		assertThat(runCount.get()).as("커밋 이후에 실행돼야 한다").isEqualTo(1);
	}

	@Test
	@DisplayName("작업이 던진 예외를 삼키지 않는다 — 삼킬지는 호출자가 정한다")
	void 예외를_삼키지_않는다() {
		TransactionSynchronizationManager.initSynchronization();

		executor.execute(() -> {
			throw new IllegalStateException("boom");
		});

		assertThatThrownBy(TransactionSynchronizationUtils::triggerAfterCommit)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("boom");
	}
}
