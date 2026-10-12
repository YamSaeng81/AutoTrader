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

    @Autowired(required = false)
    private com.cryptoautotrader.core.portfolio.PortfolioManager portfolioManager;

    /**
     * 🔴 계좌 스냅숏을 "충분하고 신선한 값" 으로 둔다 (2026-10-12).
     *
     * <p>실자금 배정·예약은 이제 스냅숏이 없거나 낡으면 거절한다(종전엔 0 이면 검사를 <b>건너뛰었다</b>).
     * 계좌 한도와 무관한 시험이 그 거절에 걸리지 않게 여기서 채운다. 계좌 한도를 시험하는 클래스는
     * 자기 {@code @BeforeEach} 에서 다시 정한다(부모의 것이 먼저 돈다). {@code @MockBean} 으로 바꾼
     * 클래스에서는 이 호출이 아무 일도 하지 않는다.</p>
     */
    @BeforeEach
    void seedAccountSnapshot() {
        if (portfolioManager != null) {
            portfolioManager.syncTotalCapital(new java.math.BigDecimal("1000000000000"));
        }
    }

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
