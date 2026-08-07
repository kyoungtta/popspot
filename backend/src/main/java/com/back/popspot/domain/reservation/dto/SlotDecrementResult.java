package com.back.popspot.domain.reservation.dto;

/**
 * 잔여 정원 차감 시도 결과.
 *
 * @param rebuilding 재구축 중이라 차감하지 않았으면 true
 * @param remaining 차감 후 잔여 정원. {@code rebuilding=true}면 null
 */
public record SlotDecrementResult(boolean rebuilding, Long remaining) {

	public static SlotDecrementResult rebuildInProgress() {
		return new SlotDecrementResult(true, null);
	}

	public static SlotDecrementResult decremented(Long remaining) {
		return new SlotDecrementResult(false, remaining);
	}
}
