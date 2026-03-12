package com.cryptoautotrader.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;

/**
 * 통합 테스트용 Redis 대체 설정.
 *
 * RedisConnectionFactory 를 mock 으로 교체하고,
 * CacheManager 를 NoOpCacheManager 로 대체하여
 * Redis 서버 없이 전체 Spring 컨텍스트가 정상 기동되도록 한다.
 *
 * RedisConfig 에서 선언한 빈들보다 @Primary 로 우선순위를 높여 덮어쓴다.
 */
@TestConfiguration
public class TestRedisConfig {

    /**
     * Redis 연결 팩토리 mock.
     * RedisConfig 의 redisTemplate / cacheManager 빈 초기화에 사용된다.
     */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    /**
     * RedisTemplate mock.
     * RedisConfig.redisTemplate() 을 대체한다.
     */
    @Bean("redisTemplate")
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, Object> redisTemplate() {
        return mock(RedisTemplate.class);
    }

    /**
     * No-op CacheManager 로 캐시 기능을 비활성화한다.
     * @Cacheable 어노테이션은 작동하지만 실제 캐싱은 발생하지 않는다.
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        return new NoOpCacheManager();
    }
}
