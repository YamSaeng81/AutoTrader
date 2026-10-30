package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 풀 한도의 <b>사전 등록값 대조</b> (2026-10-14) — 설정 누락·등록값 불일치·미등록 단계는 실자금 진입을 막는다.
 * 운영 판정은 {@code registrationProblem(false)} 로 본다(시험 런타임 예외 없이).
 */
class CapitalPoolRegistrationTest {

    private static CapitalPoolLimits limits(String stage, String total, String per, Integer slots) {
        CapitalPoolLimits l = new CapitalPoolLimits();
        l.setStage(stage);
        l.setTotalCapKrw(total == null ? null : new BigDecimal(total));
        l.setPerPositionCapKrw(per == null ? null : new BigDecimal(per));
        l.setMaxConcurrentPositions(slots);
        return l;
    }

    @Test
    @DisplayName("등록값과 정확히 같으면 승인한다 — 1단계·2단계, 소수 자릿수 표기 차이는 같은 값이다")
    void registeredStagesPass() {
        assertThat(limits("STAGE_1", "100000", "20000", 3).registrationProblem(false)).isNull();
        assertThat(limits("STAGE_2", "500000", "50000", 5).registrationProblem(false)).isNull();
        assertThat(limits(" STAGE_1 ", "100000.00", "20000.0", 3).registrationProblem(false)).isNull();
    }

    @Test
    @DisplayName("🔴 설정 누락 — 셋 중 하나라도 없거나 0 이하면 거절")
    void missingValuesBlock() {
        assertThat(limits("STAGE_1", null, "20000", 3).registrationProblem(false)).contains("누락");
        assertThat(limits("STAGE_1", "100000", null, 3).registrationProblem(false)).contains("누락");
        assertThat(limits("STAGE_1", "100000", "20000", null).registrationProblem(false)).contains("누락");
        assertThat(limits("STAGE_1", "100000", "0", 3).registrationProblem(false)).contains("누락");
        assertThat(limits("STAGE_1", "100000", "20000", 0).registrationProblem(false)).contains("누락");
        // 누락은 시험 런타임에서도 거절이다
        assertThat(limits("TEST", null, "1", 1).registrationProblem(true)).contains("누락");
    }

    @Test
    @DisplayName("🔴 등록값 불일치 — 값 하나만 달라도 거절(크든 작든)")
    void mismatchBlocks() {
        assertThat(limits("STAGE_1", "100001", "20000", 3).registrationProblem(false)).contains("등록값");
        assertThat(limits("STAGE_1", "100000", "19999", 3).registrationProblem(false)).contains("등록값");
        assertThat(limits("STAGE_1", "100000", "20000", 4).registrationProblem(false)).contains("등록값");
        assertThat(limits("STAGE_1", "500000", "50000", 5).registrationProblem(false))
                .as("2단계 값을 1단계 이름으로 — 단계 이름과 값이 엇갈림").contains("등록값");
        // 불일치는 시험 런타임에서도 거절이다
        assertThat(limits("STAGE_2", "500000", "50000", 6).registrationProblem(true)).contains("등록값");
    }

    @Test
    @DisplayName("🔴 미등록 단계 — 운영에서는 거절, 시험 런타임에서만 허용(3단계는 금액 미등록이라 거절)")
    void unknownStageBlocksOutsideTestRuntime() {
        assertThat(limits("TEST", "1000000", "100000", 10).registrationProblem(false)).contains("등록되지 않은 단계");
        assertThat(limits("", "100000", "20000", 3).registrationProblem(false)).contains("등록되지 않은 단계");
        assertThat(limits(null, "100000", "20000", 3).registrationProblem(false)).contains("등록되지 않은 단계");
        assertThat(limits("STAGE_3", "1000000", "100000", 5).registrationProblem(false)).contains("등록되지 않은 단계");
        assertThat(limits("stage_1", "100000", "20000", 3).registrationProblem(false))
                .as("대소문자도 정확히").contains("등록되지 않은 단계");
        assertThat(limits("TEST", "1000000", "100000", 10).registrationProblem(true)).isNull();
    }

    @Test
    @DisplayName("이 시험 클래스패스는 시험 런타임이다(H2 드라이버) — 운영 jar 에는 표지 클래스가 없다")
    void testRuntimeDetected() {
        assertThat(CapitalPoolLimits.testRuntime()).isTrue();
    }

    @Test
    @DisplayName("🔴 운영 compose 의 환경변수 이름이 @Value 에 묶인다 — 값이 있으면 등록, 빈 값(.env 미기입)이면 미등록(종전과 같다)")
    void prodComposeEnvironmentNamesBind() throws Exception {
        List<String> names = composeCapitalPoolEnvNames();
        assertThat(names).containsExactlyInAnyOrder("TRADING_CAPITALPOOL_STAGE", "TRADING_CAPITALPOOL_TOTALCAPKRW",
                "TRADING_CAPITALPOOL_PERPOSITIONCAPKRW", "TRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS");

        CapitalPoolLimits set = bindFromEnvironment(Map.of(
                "TRADING_CAPITALPOOL_STAGE", "STAGE_1",
                "TRADING_CAPITALPOOL_TOTALCAPKRW", "100000",
                "TRADING_CAPITALPOOL_PERPOSITIONCAPKRW", "20000",
                "TRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS", "3"));
        assertThat(set.registrationProblem(false)).isNull();
        assertThat(set.toString()).isEqualTo("단계=STAGE_1 총상한=100000 포지션당=20000 동시보유=3");

        // compose 의 ${...:-} 는 .env 에 값이 없으면 빈 문자열을 넘긴다 — 기동은 되고 미등록이어야 한다
        CapitalPoolLimits empty = bindFromEnvironment(Map.of(
                "TRADING_CAPITALPOOL_STAGE", "",
                "TRADING_CAPITALPOOL_TOTALCAPKRW", "",
                "TRADING_CAPITALPOOL_PERPOSITIONCAPKRW", "",
                "TRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS", ""));
        assertThat(empty.registrationProblem(false)).contains("누락");
    }

    /** 운영과 같은 방식(시스템 환경변수 + 느슨한 이름 대응 + 부트 변환)으로 {@code @Value} 를 채운다. */
    private static CapitalPoolLimits bindFromEnvironment(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        ConfigurationPropertySources.attach(environment);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(environment);
            ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            ctx.registerBean(PropertySourcesPlaceholderConfigurer.class);
            ctx.registerBean(CapitalPoolLimits.class);
            ctx.refresh();
            return ctx.getBean(CapitalPoolLimits.class);
        }
    }

    private static List<String> composeCapitalPoolEnvNames() throws Exception {
        Path compose = Path.of("../docker-compose.prod.yml");
        if (!Files.exists(compose)) {
            compose = Path.of("docker-compose.prod.yml");
        }
        assertThat(compose).as("docker-compose.prod.yml 을 찾지 못했다").exists();
        Matcher m = Pattern.compile("(?m)^\\s+(TRADING_CAPITALPOOL_[A-Z]+):").matcher(Files.readString(compose));
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
