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
import org.springframework.data.redis.cache.RedisCacheWriter;
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

		// Spring Data Redis 4 의 기본 cache writer 는 put/evict 를 비동기로 보내 메서드 반환 시점에 반영이 보장되지 않는다.
		// 수정 직후 조회가 이전 값을 읽지 않도록 쓰기를 동기로 고정한다.
		RedisCacheWriter cacheWriter = RedisCacheWriter.create(redisConnectionFactory,
			configurer -> configurer.immediateWrites());

		return RedisCacheManager.builder(cacheWriter)
			.withCacheConfiguration(POPUP_DETAIL, popupDetailConfig)
			.disableCreateOnMissingCache()
			// 트랜잭션 안의 put/evict 를 커밋 이후로 미룬다. @CacheEvict 가 커밋 전에 실행되면
			// 그 사이 조회가 커밋 전 DB 값을 다시 캐시에 채울 수 있다.
			.transactionAware()
			.build();
	}

	// Redis 장애로 캐시 조회·저장이 실패해도 예외를 던지지 않고 WARN 로그만 남긴다. 조회 실패는 캐시 miss 로 처리되어 DB 를 조회한다.
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}
}
