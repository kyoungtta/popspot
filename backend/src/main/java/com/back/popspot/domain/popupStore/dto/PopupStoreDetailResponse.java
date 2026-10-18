package com.back.popspot.domain.popupStore.dto;

import java.time.LocalDateTime;

import com.back.popspot.domain.popupStore.entity.PopupFeeType;
import com.back.popspot.domain.popupStore.entity.PopupStatus;

/**
 * 팝업스토어 상세 조회 응답. status 는 조회 시점 기준으로 계산된 값이다.
 * imageUrl 은 Service 에서 발급한 S3 presigned GET URL (없으면 null).
 */
public record PopupStoreDetailResponse(
		Long id,
		String title,
		String location,
		String imageUrl,
		String description,
		PopupFeeType feeType,
		Integer price,
		LocalDateTime reservationStartAt,
		LocalDateTime reservationEndAt,
		LocalDateTime openDate,
		LocalDateTime closeDate,
		PopupStatus status
) {
	public static PopupStoreDetailResponse from(PopupStoreDetailCache popupStore, PopupStatus status, String imageUrl) {
		return new PopupStoreDetailResponse(
				popupStore.id(),
				popupStore.title(),
				popupStore.location(),
				imageUrl,
				popupStore.description(),
				popupStore.feeType(),
				popupStore.price(),
				popupStore.reservationStartAt(),
				popupStore.reservationEndAt(),
				popupStore.openDate(),
				popupStore.closeDate(),
				status
		);
	}
}
