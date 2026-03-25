package com.cryptoautotrader.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * - @SpringBootTest(webEnvironment = RANDOM_PORT) 로 전체 애플리케이션 컨텍스트 로드
 * - @AutoConfigureMockMvc 로 MockMvc 자동 설정
 * - @ActiveProfiles("test") 로 application-test.yml 활성화 (H2, Flyway 비활성화)
 * - TestRedisConfig 로 Redis 의존성 제거 (allow-bean-definition-overriding=true)
 * - 모든 요청에 Authorization: Bearer test-api-token 헤더 자동 추가
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
public abstract class IntegrationTestBase {

    protected static final String TEST_TOKEN = "test-api-token";

    @Autowired
    private WebApplicationContext wac;

    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = buildObjectMapper();

    @BeforeEach
    void setupMockMvc() {
        // 모든 요청에 API 토큰 헤더를 자동으로 추가한다
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .defaultRequest(MockMvcRequestBuilders.get("/")
                        .header("Authorization", "Bearer " + TEST_TOKEN))
                .build();
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
