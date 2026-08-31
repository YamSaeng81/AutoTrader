package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-06 회귀 테스트 — <b>세션 간 동일코인 노출 상한</b>.
 *
 * <p><b>사고 재현</b>: 세션 39와 45가 08-04 11:01:01 / 11:01:05 — <b>4초 간격</b>으로
 * 같은 가격(101)·같은 수량(79.20792079)의 KRW-DOGE를 각각 매수했다. 두 세션은 전략이
 * 다른데도(MOMENTUM_ICHIMOKU_V2 / MOMENTUM_ICHIMOKU) 워치리스트가 겹치면 같은 tick 에
 * 같은 신호를 보고 동시에 반응한다. 결과적으로 단일 코인에 16,000원 — 동적 자본 70,000원의
 * <b>23%</b> — 가 몰렸고, 그 뒤 둘 다 −1.58%로 같이 물렸다.</p>
 *
 * <h3>2026-08-25 → 08-30: 면제했다가 실현손익으로 되돌렸다</h3>
 *
 * <p>08-25 에 PAPER 를 면제했다(근거: 차단된 BUY 의 24h <b>사후수익률</b> +4.51%·승률 69.3%).
 * 그 판단은 틀렸다 — 면제 후 <b>실현손익</b>을 동시진입 세션 수로 갈라보니 정반대였다:</p>
 * <pre>
 *   단독      61건  승률 37.7%   −0.35%/건
 *   2세션     21건  승률 47.6%   <b>+1.75%</b>/건
 *   3세션     30건  승률 20.0%   <b>−1.33%</b>/건
 *   4세션     16건  승률  6.3%   <b>−2.13%</b>/건
 * </pre>
 *
 * <p>사후수익률은 24시간 뒤 가격일 뿐 경로를 무시한다 — 여러 세션이 몰리는 시점은 변동성이
 * 커서 <b>24시간 뒤엔 올라 있어도 그 전에 SL 을 맞는다</b>. 그래서 상한을 <b>2</b>로 되돌렸다:
 * 1은 과하고(2세션 구간이 가장 좋다), 3 이상은 명확히 해롭다. PAPER·REAL 공통이다.</p>
 */
class CrossSessionExposureTest {

    @Test
    @DisplayName("아무 세션도 안 들고 있으면 통과한다 — 정상 진입에 부작용 없음")
    void 미보유_통과() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(0)).isNull();
    }

    @Test
    @DisplayName("✅ 다른 세션이 1건 보유 중이면 통과한다 — 2세션 구간이 실측 최고(+1.75%)")
    void 한세션_보유시_통과() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(1))
                .as("상한 1 시절엔 여기서 막혔다 — 실측상 가장 좋은 구간을 버리고 있었다")
                .isNull();
    }

    @Test
    @DisplayName("🔴 다른 세션이 2건 보유 중이면 차단한다 — 3세션 집중은 −1.33%")
    void 두세션_보유시_차단() {
        String reason = DynamicTradingService.crossSessionExposureBlockReason(2);
        assertThat(reason).isNotNull();
        assertThat(reason).contains("동일코인 노출 상한");
    }

    @Test
    @DisplayName("이미 여러 세션이 물려 있어도 계속 차단한다 — 4세션 집중은 −2.13%")
    void 다수_보유시도_차단() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(3)).isNotNull();
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(8)).isNotNull();
    }

    @Test
    @DisplayName("차단 사유에 보유 건수가 남아 blocked_reason 으로 원인 추적이 된다")
    void 차단사유에_건수_포함() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(3)).contains("3건");
    }

    @Test
    @DisplayName("상한값이 2다 — 39·45 DOGE 사고(단일 코인 자본 23% 집중)는 여전히 막힌다")
    void 상한값_고정() {
        assertThat(DynamicTradingService.MAX_SESSIONS_PER_COIN)
                .as("이 값을 바꾸면 지문(scan.maxSessionsPerCoin)이 갈려 표본이 분리된다")
                .isEqualTo(2L);
    }
}
