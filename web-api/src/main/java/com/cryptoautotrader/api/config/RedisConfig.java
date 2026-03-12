package com.cryptoautotrader.api.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 연결 및 직렬화 설정.
 * - RedisTemplate: 범용 JSON 직렬화 (키=String, 값=JSON)
 * - CacheManager: @Cacheable 어노테이션 지원, 캐시별 TTL 차등 설정
 *
 * Redis 키 패턴 (DESIGN.md 3.4단원):
 *   cache:upbit:ticker:{coin}             TTL 1초
 *   cache:upbit:candle:{coin}:{timeframe} TTL 60초
 *   ratelimit:upbit:api                   TTL 1초
 */
@Configuration
@EnableCaching
public class RedisConfig {

    // 캐시 이름 상수
    public static final String CACHE_CANDLE = "candle";
    public static final String CACHE_TICKER = "ticker";
    public static final String CACHE_STRATEGY_CONFIG = "strategyConfig";
    public static final String CACHE_BACKTEST_RESULT = "backtestResult";

    /**
     * 범용 RedisTemplate.
     * 키는 String, 값은 Jackson JSON으로 직렬화한다.
     * Spring Data Redis 자동설정이 제공하는 RedisConnectionFactory를 재사용한다.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = buildJsonSerializer();

        // 키/해시키: String 직렬화
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 값/해시값: JSON 직렬화
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Spring Cache 추상화용 CacheManager.
     * 캐시별 TTL을 개별 지정하고, 나머지는 기본값(5분)을 사용한다.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(buildJsonSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // 실시간 티커: TTL 1초 (시세는 1초마다 갱신)
        cacheConfigurations.put(CACHE_TICKER,
                defaultConfig.entryTtl(Duration.ofSeconds(1)));

        // 캔들 데이터: TTL 60초 (1분봉 기준 최소 단위)
        cacheConfigurations.put(CACHE_CANDLE,
                defaultConfig.entryTtl(Duration.ofSeconds(60)));

        // 전략 설정: TTL 10분 (설정 변경 반영 주기)
        cacheConfigurations.put(CACHE_STRATEGY_CONFIG,
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // 백테스트 결과: TTL 30분 (재계산 비용이 높음)
        cacheConfigurations.put(CACHE_BACKTEST_RESULT,
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * Jackson ObjectMapper 기반 Redis 직렬화기.
     * 타입 정보를 JSON에 포함시켜 역직렬화 시 정확한 클래스로 복원한다.
     */
    private GenericJackson2JsonRedisSerializer buildJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
