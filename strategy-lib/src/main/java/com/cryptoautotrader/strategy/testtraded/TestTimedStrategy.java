package com.cryptoautotrader.strategy.testtraded;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 실전매매 동작 검증용 테스트 전략.
 * - 세션 시작 직후 무조건 매수
 * - 세션 시작 3분 경과 후 무조건 매도
 * - 코인: KRW-ETH 고정 / 타임프레임: M1 / 원금: 10,000 KRW 고정
 */
public class TestTimedStrategy implements Strategy {

    private static final long EXIT_AFTER_MILLIS = 3 * 60 * 1_000L; // 3분

    @Override
    public String getName() {
        return "TEST_TIMED";
    }

    @Override
    public int getMinimumCandleCount() {
        return 1;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        Object startedAtObj = params.get("sessionStartedAt");

        // sessionStartedAt이 없으면 아직 세션이 시작 직전 → 즉시 매수
        if (startedAtObj == null) {
            return StrategySignal.buy(BigDecimal.valueOf(100),
                    "테스트 전략: 세션 시작 시각 미확인 → 즉시 매수");
        }

        long sessionStartedAt = ((Number) startedAtObj).longValue();
        long elapsedMs = System.currentTimeMillis() - sessionStartedAt;
        long elapsedSec = elapsedMs / 1_000;
        long remainSec = (EXIT_AFTER_MILLIS - elapsedMs) / 1_000;

        if (elapsedMs >= EXIT_AFTER_MILLIS) {
            return StrategySignal.sell(BigDecimal.valueOf(100),
                    String.format("테스트 전략: 3분 경과 → 강제 매도 (경과: %d초)", elapsedSec));
        }

        return StrategySignal.buy(BigDecimal.valueOf(100),
                String.format("테스트 전략: 즉시 매수 (경과: %d초, %d초 후 매도)", elapsedSec, remainSec));
    }
}
