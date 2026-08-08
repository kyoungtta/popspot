package com.back.popspot.global.redis.rebuild;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttlSeconds 게이트 TTL. 재구축이 이보다 오래 걸리면 게이트가 먼저 풀려 blind write가
 *     다시 노출되므로, 스코프를 잘게 잡아 개별 재구축을 짧게 유지하거나
 *     {@link RebuildLease#renew()}로 연장해야 한다. 반대로 너무 길면 재구축 중 프로세스가
 *     죽었을 때 그만큼 서비스가 막힌다.
 */
@ConfigurationProperties(prefix = "redis-rebuild")
public record RebuildProperties(
	// 미설정 시 0이 되면 SET PX 0 이 에러가 되므로 기본값을 둔다
	@DefaultValue("30") long ttlSeconds
) {
}
