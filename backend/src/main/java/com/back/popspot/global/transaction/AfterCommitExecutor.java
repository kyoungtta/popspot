package com.back.popspot.global.transaction;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 커밋 성공 이후에 작업을 실행한다. 트랜잭션 밖에서 호출되면 즉시 실행한다.
 *
 * <p>롤백 시 되돌릴 수 없는 외부 부수효과(S3 파일 이동·삭제, Redis 카운터 초기화)를 커밋 이후로
 * 미루는 용도다. 여러 서비스에 같은 등록 코드가 복사돼 있던 것을 한곳으로 모았다.
 *
 * <p><b>예외를 삼키지 않는다.</b> afterCommit에서 던진 예외는 커밋 이후에 호출자로 전파되므로,
 * 사용자에게는 실패로 보이지만 DB에는 저장된 상태가 된다. 그 처리는 작업의 성격에 따라 다르므로
 * 이 클래스가 정하지 않고 호출자에게 맡긴다. S3 작업의 경우
 * {@link com.back.popspot.global.s3.S3AfterCommitExecutor}가 재시도 후 삼키는 쪽을 택한다.
 */
@Component
public class AfterCommitExecutor {

	public void execute(Runnable task) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			task.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				task.run();
			}
		});
	}
}
