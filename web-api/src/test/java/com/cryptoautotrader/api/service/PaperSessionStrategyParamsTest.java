package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAPER 세션 생성이 {@code strategyParams} 를 <b>반드시</b> 엔티티로 옮기는지 고정한다.
 *
 * <h3>왜 이 테스트가 있나 (2026-08-24 실사고)</h3>
 * <p>{@code PaperTradingService.createSession} 의 빌더에서 {@code strategyParams} 가 빠져 있었다.
 * LIVE({@code LiveTradingService:370})·DYNAMIC({@code DynamicTradingService:415}) 은 처음부터
 * 넘기고 있었는데 PAPER 만 누락이었다.</p>
 *
 * <p>결과가 고약했다 — API 는 200 을 돌려주고 세션도 정상 생성되는데
 * <b>파라미터만 조용히 사라진다.</b> 손절폭 A/B 1차 시도에서 실험군 40세션(id 250~289)이
 * 그렇게 만들어져 대조군과 완전히 같은 규칙으로 돌았다. 지문({@code strategy.params})까지
 * 같아져 사후에 "이건 오염된 표본"이라고 구분할 방법조차 없었다.</p>
 *
 * <p>실패가 조용하기 때문에 회귀 테스트로 고정한다. A/B 는 이 프로젝트의 핵심 도구이고,
 * 파라미터가 유실되면 실험이 실패하는 게 아니라 <b>틀린 결론을 준다.</b></p>
 */
class PaperSessionStrategyParamsTest {

    /**
     * 세션 생성 경로 전체를 띄우지 않고, 요청 DTO → 엔티티 매핑만 검증한다.
     * (createSession 은 private 이고 repo·gate 의존이 있어 단위 테스트로 직접 부르기 어렵다.
     *  대신 그 빌더가 채우는 필드 집합을 아래 계약 테스트로 강제한다.)
     */
    @Test
    @DisplayName("VirtualBalanceEntity 는 strategyParams 를 담을 수 있어야 한다")
    void entityCarriesStrategyParams() {
        Map<String, Object> params = Map.of("slAtrMultiplier", 2.5, "tpRrMultiplier", 1.2);

        VirtualBalanceEntity session = VirtualBalanceEntity.builder()
                .strategyName("COMPOSITE_MTF_BTC")
                .coinPair("KRW-BTC")
                .timeframe("M15")
                .initialCapital(BigDecimal.valueOf(10_000_000))
                .strategyParams(params)
                .build();

        assertThat(session.getStrategyParams()).isEqualTo(params);
        assertThat(ExitRuleOverrides.from(session.getStrategyParams()).isPresent())
                .as("엔티티에 실린 파라미터가 청산 오버라이드로 읽혀야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("요청 DTO 의 strategyParams 가 청산 오버라이드로 해석된다")
    void requestParamsResolveToOverrides() {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType("COMPOSITE_MTF_BTC");
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("M15");
        req.setInitialCapital(BigDecimal.valueOf(10_000_000));
        req.setStrategyParams(Map.of("slAtrMultiplier", 2.5, "tpRrMultiplier", 1.2));

        ExitRuleOverrides ov = ExitRuleOverrides.from(req.getStrategyParams());
        assertThat(ov.isPresent()).isTrue();
        assertThat(ov.slAtrMultiplierOr(BigDecimal.ONE)).isEqualByComparingTo("2.5");
        assertThat(ov.tpRrMultiplierOr(BigDecimal.ONE)).isEqualByComparingTo("1.2");
    }

    /**
     * <b>핵심 회귀 가드</b> — {@code createSession} 소스에 {@code .strategyParams(} 빌더 호출이
     * 살아 있는지 본다. 리팩터링으로 다시 떨어져 나가면 여기서 깨진다.
     *
     * <p>소스를 문자열로 보는 방식이 투박하지만, 이 버그의 성질(조용한 유실)상
     * "빌더에 그 줄이 있는가"가 정확히 지켜야 할 계약이다.</p>
     */
    @Test
    @DisplayName("PaperTradingService.createSession 이 strategyParams 를 빌더에 넘긴다")
    void createSessionPassesStrategyParamsToBuilder() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java");
        if (!java.nio.file.Files.exists(src)) {
            src = java.nio.file.Path.of(
                    "web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java");
        }
        assertThat(src).as("PaperTradingService 소스를 찾지 못했다").exists();

        String code = java.nio.file.Files.readString(src);
        assertThat(code)
                .as("createSession 빌더에서 strategyParams 가 빠졌다 — "
                        + "A/B 파라미터가 조용히 유실되어 실험군이 대조군과 같아진다 (2026-08-24 사고)")
                .contains(".strategyParams(req.getStrategyParams())");
    }

    /**
     * 세 엔진의 세션 엔티티가 모두 {@code strategyParams} 를 갖는지 — 한 곳이라도 빠지면
     * 그 엔진에서는 A/B 자체가 불가능하다.
     */
    @Test
    @DisplayName("세 엔진 세션 엔티티가 모두 strategyParams 필드를 갖는다")
    void allThreeEnginesSupportStrategyParams() {
        for (Class<?> type : Set.of(
                VirtualBalanceEntity.class,
                com.cryptoautotrader.api.entity.LiveTradingSessionEntity.class,
                com.cryptoautotrader.api.entity.DynamicSessionEntity.class)) {
            boolean found = false;
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if ("strategyParams".equals(f.getName())) { found = true; break; }
            }
            assertThat(found).as("%s 에 strategyParams 필드가 없다", type.getSimpleName()).isTrue();
        }
    }
}
