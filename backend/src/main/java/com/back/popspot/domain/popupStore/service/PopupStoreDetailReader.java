package com.back.popspot.domain.popupStore.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.back.popspot.domain.popupStore.dto.PopupStoreDetailCache;
import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.global.config.CacheConfig;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 팝업스토어 상세 조회의 캐시 계층. @Cacheable 은 프록시를 거쳐야 동작하므로 PopupStoreService 와 별도 빈으로 둔다.
 * 클래스 레벨 트랜잭션을 두지 않아 캐시 hit 시 DB 커넥션을 잡지 않는다 (miss 시 findById 자체 트랜잭션만 사용).
 */
@Service
@RequiredArgsConstructor
public class PopupStoreDetailReader {

	private final PopupStoreRepository popupStoreRepository;

	/**
	 * 팝업스토어 스냅샷을 조회한다. 없으면 RESOURCE_NOT_FOUND (예외는 캐시되지 않는다).
	 */
	@Cacheable(cacheNames = CacheConfig.POPUP_DETAIL, key = "#popupStoreId")
	public PopupStoreDetailCache read(Long popupStoreId) {
		return popupStoreRepository.findById(popupStoreId)
			.map(PopupStoreDetailCache::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}
}
