package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.util.TradingConstants;
import com.cryptoautotrader.strategy.StrategyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 총괄 v1.177 §3-3·v1.178 §4-3 — 새 모의 9세션 사전 봉 확인 SQL(preflight v2)이 쓰는 <b>전략별 최소 봉 수</b>를 운영 코드에서 읽어 고정한다
 * (2026-11-17, 시험만). 엔진은 {@link TradingConstants#minimumCandlesFor} 를 쓰며, 알 수 없는 이름이면 조용히 대체값(205)이 된다 —
 * 그래서 세 전략이 운영 컨텍스트의 레지스트리에 실제로 있는지(대체값이 아닌지)도 본다. 출력 줄은 SQL 의 상수와 대조한다.
 */
@DisplayName("사전 봉 확인 — 9세션 전략의 최소 봉 수(운영 레지스트리 값)")
class BundleMinCandlesTest extends IntegrationTestBase {

    @Test
    void minimumCandlesForBundleStrategies() {
        for (String name : List.of("DAILY_SMA20", "COMPOSITE_MEANREV_BB", "COMPOSITE_MTF_CONFIRMED")) {
            assertThat(StrategyRegistry.get(name)).as(name + " 레지스트리 등록").isNotNull();
            int n = TradingConstants.minimumCandlesFor(name);
            assertThat(n).as(name).isEqualTo(StrategyRegistry.get(name).getMinimumCandleCount());
            System.out.println("[최소 봉] " + name + "=" + n);
        }
        assertThat(TradingConstants.minimumCandlesFor("DAILY_SMA20")).isEqualTo(457);
    }
}
