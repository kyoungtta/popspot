package com.back.popspot.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.back.popspot.domain.popupStore.dto.PopupStoreDetailCache;
import com.back.popspot.domain.popupStore.entity.PopupFeeType;

/**
 * popupDetail 캐시 설정(TTL, JSON 직렬화) 단위 테스트 (Redis 연결 없이 직렬화만 검증).
 */
class CacheConfigTest {

	private RedisCacheManager cacheManager;

	@BeforeEach
	void setUp() {
		cacheManager = (RedisCacheManager)new CacheConfig().cacheManager(mock(RedisConnectionFactory.class));
		cacheManager.afterPropertiesSet();
	}

	@Test
	@DisplayName("popupDetail 캐시는 TTL 10분이 적용된다")
	void popupDetail_ttlIsTenMinutes() {
		RedisCache cache = (RedisCache)cacheManager.getCache(CacheConfig.POPUP_DETAIL);

		assertThat(cache.getCacheConfiguration().getTtlFunction()
				.getTimeToLive(1L, null)).hasMinutes(10);
	}

	@Test
	@DisplayName("LocalDateTime 을 포함한 스냅샷이 JSON 으로 저장되고 그대로 복원된다")
	void popupDetail_serializesToJsonAndRestores() {
		RedisCache cache = (RedisCache)cacheManager.getCache(CacheConfig.POPUP_DETAIL);
		var valuePair = cache.getCacheConfiguration().getValueSerializationPair();
		PopupStoreDetailCache original = new PopupStoreDetailCache(1L, "title", "location", "image", "설명",
				PopupFeeType.PAID, 10000, LocalDateTime.of(2026, 6, 1, 10, 0, 30), LocalDateTime.of(2026, 6, 10, 18, 0),
				LocalDateTime.of(2026, 6, 15, 11, 0), LocalDateTime.of(2026, 6, 30, 20, 0));

		byte[] bytes = valuePair.write(original).array();
		String json = new String(bytes, StandardCharsets.UTF_8);
		Object restored = valuePair.read(ByteBuffer.wrap(bytes));

		assertThat(json).contains("\"reservationStartAt\":\"2026-06-01T10:00:30\"");
		assertThat(restored).isEqualTo(original);
	}
}
