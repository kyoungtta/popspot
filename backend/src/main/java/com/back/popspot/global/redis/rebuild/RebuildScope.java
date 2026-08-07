package com.back.popspot.global.redis.rebuild;

import com.back.popspot.global.redis.RedisKeys;

/**
 * 재구축 게이트의 단위.
 *
 * <p>스코프는 <b>도메인이 아니라 Redis 키 기준</b>이다. 같은 키를 재구축하는 코드는 서로 다른
 * 도메인에 있더라도 반드시 같은 스코프를 써야 게이트가 의미를 갖는다. 예를 들어 예약 잔여 정원
 * 재구축(reservation)과 슬롯 카운터 초기화(popupStore)는 둘 다
 * {@link RedisKeys#reservationSlotRemaining}을 쓰므로 {@link #reservationSlot} 하나를 공유한다.
 */
public record RebuildScope(String name) {

	/** 예약 슬롯 잔여 정원 카운터 — DECR/INCR과 재구축이 경합하는 키 */
	public static RebuildScope reservationSlot(Long slotId) {
		return new RebuildScope("reservation:slot:" + slotId);
	}

	/** 팝업 대기열 ZSET + seq 카운터 — enqueue와 재구축이 경합하는 키 */
	public static RebuildScope popupQueue(Long popupId) {
		return new RebuildScope("queue:popup:" + popupId);
	}

	public String key() {
		return RedisKeys.rebuildFlag(name);
	}
}
