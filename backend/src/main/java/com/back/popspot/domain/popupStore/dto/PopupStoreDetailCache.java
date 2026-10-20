package com.back.popspot.domain.popupStore.dto;

import java.time.LocalDateTime;

import com.back.popspot.domain.popupStore.entity.PopupFeeType;
import com.back.popspot.domain.popupStore.entity.PopupStatus;
import com.back.popspot.domain.popupStore.entity.PopupStore;

/**
 * 상세 조회 캐시(popupDetail)에 저장하는 팝업스토어 스냅샷.
 * status 와 presigned imageUrl 은 시간이 지나면 달라지므로 저장하지 않고, 조회할 때마다 이 값으로 계산한다.
 */
public record PopupStoreDetailCache(
		Long id,
		String title,
		String location,
		String imageKey,
		String description,
		PopupFeeType feeType,
		Integer price,
		LocalDateTime reservationStartAt,
		LocalDateTime reservationEndAt,
		LocalDateTime openDate,
		LocalDateTime closeDate
) {
	public static PopupStoreDetailCache from(PopupStore popupStore) {
		return new PopupStoreDetailCache(
				popupStore.getId(),
				popupStore.getTitle(),
				popupStore.getLocation(),
				popupStore.getImageKey(),
				popupStore.getDescription(),
				popupStore.getFeeType(),
				popupStore.getPrice(),
				popupStore.getReservationStartAt(),
				popupStore.getReservationEndAt(),
				popupStore.getOpenDate(),
				popupStore.getCloseDate()
		);
	}

	public PopupStatus calculateStatus(LocalDateTime now) {
		return PopupStatus.calculate(reservationStartAt, reservationEndAt, now);
	}
}
