package com.back.popspot.domain.popupStore.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.back.popspot.domain.popupStore.dto.PopupStoreCreateRequest;
import com.back.popspot.domain.popupStore.dto.PopupStoreUpdateRequest;
import com.back.popspot.domain.popupStore.entity.PopupFeeType;
import com.back.popspot.domain.popupStore.entity.PopupStore;
import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.global.config.CacheConfig;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.support.ContainerIntegrationTestSupport;

/**
 * 팝업 수정·삭제 시 popupDetail 캐시가 비워지는지 검증하는 통합 테스트 (MySQL, Redis 컨테이너).
 * 캐시 삭제는 커밋 이후에 일어나야 하므로 테스트 트랜잭션을 끄고 서비스 트랜잭션이 실제로 커밋되게 한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("팝업 상세 캐시 무효화 통합 테스트")
class PopupStoreDetailCacheEvictTest extends ContainerIntegrationTestSupport {

	@Autowired
	private PopupStoreHostService popupStoreHostService;

	@Autowired
	private PopupStoreDetailReader popupStoreDetailReader;

	@Autowired
	private PopupStoreRepository popupStoreRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Cache cache;
	private User host;
	private Long popupStoreId;

	@BeforeEach
	void setUp() {
		cache = cacheManager.getCache(CacheConfig.POPUP_DETAIL);
		host = userRepository.save(User.create("cache-evict-host@test.com", "host"));
		LocalDateTime now = LocalDateTime.now();
		popupStoreId = popupStoreRepository.save(PopupStore.of(host, new PopupStoreCreateRequest(
			"원래 제목", "서울 성수동", PopupFeeType.FREE, null,
			now.plusDays(3), now.plusDays(10),
			now.plusDays(5), now.plusDays(15),
			null, "설명"))).getId();
	}

	@AfterEach
	void tearDown() {
		cache.evict(popupStoreId);
		popupStoreRepository.findById(popupStoreId).ifPresent(popupStoreRepository::delete);
		userRepository.delete(host);
	}

	@Test
	@DisplayName("수정: 성공하면 캐시가 비워지고, 다음 조회는 수정된 값을 캐시한다")
	void updatePopupStore_evictsCache() {
		popupStoreDetailReader.read(popupStoreId);
		assertThat(cache.get(popupStoreId)).isNotNull();

		popupStoreHostService.updatePopupStore(host.getId(), popupStoreId, titleUpdate("바뀐 제목"));

		assertThat(cache.get(popupStoreId)).isNull();
		assertThat(popupStoreDetailReader.read(popupStoreId).title()).isEqualTo("바뀐 제목");
	}

	@Test
	@DisplayName("수정: 캐시 삭제는 커밋 이후에 일어난다")
	void updatePopupStore_evictsAfterCommit() {
		popupStoreDetailReader.read(popupStoreId);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			popupStoreHostService.updatePopupStore(host.getId(), popupStoreId, titleUpdate("바뀐 제목"));
			assertThat(cache.get(popupStoreId)).isNotNull();
		});

		assertThat(cache.get(popupStoreId)).isNull();
	}

	@Test
	@DisplayName("수정: 실패(소유자 아님)하면 캐시를 비우지 않는다")
	void updatePopupStore_fails_keepsCache() {
		popupStoreDetailReader.read(popupStoreId);

		assertThatThrownBy(() ->
			popupStoreHostService.updatePopupStore(host.getId() + 1, popupStoreId, titleUpdate("바뀐 제목")))
			.isInstanceOf(BusinessException.class);

		assertThat(cache.get(popupStoreId)).isNotNull();
	}

	@Test
	@DisplayName("삭제: 성공하면 캐시가 비워진다")
	void deletePopupStore_evictsCache() {
		popupStoreDetailReader.read(popupStoreId);
		assertThat(cache.get(popupStoreId)).isNotNull();

		popupStoreHostService.deletePopupStore(host.getId(), popupStoreId);

		assertThat(cache.get(popupStoreId)).isNull();
		assertThatThrownBy(() -> popupStoreDetailReader.read(popupStoreId))
			.isInstanceOf(BusinessException.class);
	}

	private PopupStoreUpdateRequest titleUpdate(String title) {
		return new PopupStoreUpdateRequest(title, null, null, null, null, null, null, null, null, null);
	}
}
