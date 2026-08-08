package com.back.popspot.global.redis.rebuild;

import java.util.Optional;

/**
 * Redis를 DB 기준으로 재구축하는 동안 해당 키에 대한 트래픽 쓰기를 막는 게이트.
 *
 * <p>재구축은 DB를 읽어 Redis에 blind write를 하므로, 읽기~쓰기 사이에 들어온 요청의 변경이
 * 통째로 덮어써진다. 게이트는 그 구간을 표시해 트래픽 경로가 스스로 물러나게 한다.
 *
 * <p>{@link #tryBegin}은 {@code SET NX PX} 하나로 두 가지를 겸한다 — 게이트 표시와,
 * 다중 인스턴스가 같은 스코프를 동시에 재구축하지 못하게 하는 상호배제.
 *
 * <p><b>한계</b>: 게이트 검사와 뒤이은 쓰기가 분리돼 있으면 그 사이에 재구축이 시작될 수 있어
 * race가 완전히 사라지지 않는다. 예약 DECR 경로는 검사와 DECR을 Lua로 묶어 이 창을 없앴다
 * ({@code redis/decr-unless-rebuilding.lua}). enqueue 경로는 중간에 DB 쓰기가 끼어 한 스크립트로
 * 묶을 수 없어 창이 남지만, 남은 손실은 DB를 source of truth로 삼는 다음 복구에서 수렴한다.
 */
public interface RebuildGate {

	/**
	 * 게이트를 선점하고 임대를 반환한다. 다른 인스턴스가 이미 같은 스코프를 재구축 중이면 empty.
	 *
	 * <p>반환된 임대는 반드시 try-with-resources로 닫아야 한다. 닫지 못하고 프로세스가 죽어도
	 * TTL이 만료되면 게이트가 자동으로 풀리므로 서비스가 영구히 막히지는 않는다.
	 */
	Optional<RebuildLease> tryBegin(RebuildScope scope);

	/**
	 * 트래픽 경로용 검사. 재구축 중이면 true.
	 *
	 * <p>게이트 조회 자체가 실패하면 fail-open(false)이다. Redis 장애는 서킷브레이커와 도메인별
	 * in-memory 플래그가 따로 차단하므로, 여기서 fail-closed로 막으면 Redis가 흔들릴 때마다
	 * 정상 트래픽까지 함께 죽는다.
	 */
	boolean isRebuilding(RebuildScope scope);
}
