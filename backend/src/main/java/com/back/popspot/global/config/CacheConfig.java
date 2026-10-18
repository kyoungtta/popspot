package com.back.popspot.global.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.back.popspot.domain.popupStore.dto.PopupStoreDetailCache;

/**
 * Spring Cache(Redis) 설정. RedisConfig 의 RedisConnectionFactory 를 재사용한다.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	public static final String POPUP_DETAIL = "popupDetail";

	private static final Duration POPUP_DETAIL_TTL = Duration.ofMinutes(10);

	@Bean
	public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
		// 캐시 대상 타입을 고정한 JSON 직렬화: 값에 클래스 정보를 넣지 않고, LocalDateTime 은 ISO-8601 문자열로 저장된다.
		RedisCacheConfiguration popupDetailConfig = RedisCacheConfiguration.defaultCacheConfig()
			.entryTtl(POPUP_DETAIL_TTL)
			.disableCachingNullValues()
			.serializeValuesWith(SerializationPair.fromSerializer(
				new JacksonJsonRedisSerializer<>(PopupStoreDetailCache.class)));

		return RedisCacheManager.builder(redisConnectionFactory)
			.withCacheConfiguration(POPUP_DETAIL, popupDetailConfig)
			.disableCreateOnMissingCache()
			.build();
	}

	// Redis 장애로 캐시 조회·저장이 실패해도 예외를 던지지 않고 WARN 로그만 남긴다. 조회 실패는 캐시 miss 로 처리되어 DB 를 조회한다.
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}
}
