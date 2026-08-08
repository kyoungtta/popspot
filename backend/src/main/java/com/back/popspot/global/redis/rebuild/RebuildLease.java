package com.back.popspot.global.redis.rebuild;

/**
 * 재구축 게이트 임대. try-with-resources로 닫으면 게이트가 풀린다.
 *
 * <p>해제는 <b>내 토큰일 때만</b> 이뤄진다. TTL이 만료돼 다른 인스턴스가 새로 잡은 게이트를
 * 뒤늦게 끝난 내가 내려버리는 것을 막기 위해서다.
 */
public final class RebuildLease implements AutoCloseable {

	/** 게이트 해제/연장 실행자. 토큰 소유 검사는 구현체가 원자적으로 수행한다. */
	public interface Releaser {
		void release(RebuildScope scope, String token);

		boolean renew(RebuildScope scope, String token);
	}

	private final RebuildScope scope;
	private final String token;
	private final Releaser releaser;
	private boolean closed;

	public RebuildLease(RebuildScope scope, String token, Releaser releaser) {
		this.scope = scope;
		this.token = token;
		this.releaser = releaser;
	}

	public RebuildScope scope() {
		return scope;
	}

	/**
	 * TTL을 연장한다. 재구축이 TTL보다 오래 걸릴 때 쓴다.
	 *
	 * @return 연장 성공 여부. false면 이미 게이트가 만료됐거나 다른 인스턴스가 잡았다는 뜻이므로,
	 *     그 시점부터는 blind write를 막아주지 못한다.
	 */
	public boolean renew() {
		return releaser.renew(scope, token);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		releaser.release(scope, token);
	}
}
