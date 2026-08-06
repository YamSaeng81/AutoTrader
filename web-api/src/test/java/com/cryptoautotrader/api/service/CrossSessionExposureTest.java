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
 * <p>7세션 분산 운용의 목적은 전략·종목 분산인데, 노출 상한이 없으면 분산되는 것은
 * 세션 수뿐이고 리스크는 한 종목에 합쳐진다. 이 테스트가 잠그는 불변식이 그것이다.</p>
 */
class CrossSessionExposureTest {

    @Test
    @DisplayName("아무 세션도 안 들고 있으면 통과한다 — 정상 진입에 부작용 없음")
    void 미보유_통과() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(0)).isNull();
    }

    @Test
    @DisplayName("다른 세션이 1건 보유 중이면 차단한다 — 39·45 DOGE 동시 진입 재발 방지")
    void 타세션_보유시_차단() {
        String reason = DynamicTradingService.crossSessionExposureBlockReason(1);
        assertThat(reason).isNotNull();
        assertThat(reason).contains("동일코인 노출 상한");
    }

    @Test
    @DisplayName("이미 여러 세션이 물려 있어도 계속 차단한다 — 상한이 뚫린 뒤에도 추가 진입 금지")
    void 다수_보유시도_차단() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(3)).isNotNull();
    }

    @Test
    @DisplayName("차단 사유에 보유 건수가 남아 blocked_reason 으로 원인 추적이 된다")
    void 차단사유에_건수_포함() {
        assertThat(DynamicTradingService.crossSessionExposureBlockReason(2)).contains("2건");
    }
}
