package com.back.popspot.domain.popupStore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.back.popspot.domain.popupStore.dto.PopupStoreDetailCache;
import com.back.popspot.domain.popupStore.entity.PopupStore;
import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;

/**
 * PopupStoreDetailReader 조회·매핑 단위 테스트 (repository 는 mock, 캐시 프록시 없이).
 */
@ExtendWith(MockitoExtension.class)
class PopupStoreDetailReaderTest {

	@Mock
	private PopupStoreRepository popupStoreRepository;

	@InjectMocks
	private PopupStoreDetailReader popupStoreDetailReader;

	@Test
	@DisplayName("존재하면 팝업스토어를 캐시용 스냅샷으로 변환한다")
	void read_found_returnsSnapshot() {
		LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 0);
		PopupStore popupStore = new PopupStore();
		ReflectionTestUtils.setField(popupStore, "id", 10L);
		ReflectionTestUtils.setField(popupStore, "title", "title10");
		ReflectionTestUtils.setField(popupStore, "imageKey", "image10");
		ReflectionTestUtils.setField(popupStore, "reservationStartAt", start);
		when(popupStoreRepository.findById(10L)).thenReturn(Optional.of(popupStore));

		PopupStoreDetailCache result = popupStoreDetailReader.read(10L);

		assertThat(result.id()).isEqualTo(10L);
		assertThat(result.title()).isEqualTo("title10");
		assertThat(result.imageKey()).isEqualTo("image10");
		assertThat(result.reservationStartAt()).isEqualTo(start);
	}

	@Test
	@DisplayName("존재하지 않으면 RESOURCE_NOT_FOUND 예외")
	void read_notFound_throwsBusinessException() {
		when(popupStoreRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> popupStoreDetailReader.read(99L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
	}
}
