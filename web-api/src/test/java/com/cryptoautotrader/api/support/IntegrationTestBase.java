package com.cryptoautotrader.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * - @SpringBootTest(webEnvironment = RANDOM_PORT) 로 전체 애플리케이션 컨텍스트 로드
 * - @AutoConfigureMockMvc 로 MockMvc 자동 설정
 * - @ActiveProfiles("test") 로 application-test.yml 활성화 (H2, Flyway 비활성화)
 * - RedisAutoConfiguration 을 제외하고 TestRedisConfig 로 대체하여 Redis 의존성 제거
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        excludeAutoConfiguration = {
                RedisAutoConfiguration.class,
                RedisReactiveAutoConfiguration.class
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = buildObjectMapper();

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
