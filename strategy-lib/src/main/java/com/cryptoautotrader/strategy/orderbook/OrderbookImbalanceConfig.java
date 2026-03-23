package com.cryptoautotrader.strategy.orderbook;

import com.cryptoautotrader.strategy.StrategyConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Orderbook Imbalance 전략 파라미터
 * - imbalanceThreshold: 불균형 판단 임계값 (기본 0.65 = 65%)
 * - lookback: 캔들 기반 근사치 계산 기간 (기본 5)
 * - depthLevels: 호가 몇 단계까지 포함 (기본 10, WebSocket 연동 시 활성화)
 */
@Getter
@Setter
public class OrderbookImbalanceConfig extends StrategyConfig {

    private double imbalanceThreshold = 0.65;
    private int lookback = 5;
    private int depthLevels = 10;

    @Override
    public String getStrategyType() {
        return "ORDERBOOK_IMBALANCE";
    }

    @Override
    public Map<String, Object> toParamMap() {
        return Map.of(
                "imbalanceThreshold", imbalanceThreshold,
                "lookback", lookback,
                "depthLevels", depthLevels
        );
    }

    public static OrderbookImbalanceConfig fromParams(Map<String, Object> params) {
        OrderbookImbalanceConfig config = new OrderbookImbalanceConfig();
        if (params == null) {
            return config;
        }
        Object thresholdVal = params.get("imbalanceThreshold");
        if (thresholdVal instanceof Number) {
            config.setImbalanceThreshold(((Number) thresholdVal).doubleValue());
        }
        Object lookbackVal = params.get("lookback");
        if (lookbackVal instanceof Number) {
            config.setLookback(((Number) lookbackVal).intValue());
        }
        Object depthVal = params.get("depthLevels");
        if (depthVal instanceof Number) {
            config.setDepthLevels(((Number) depthVal).intValue());
        }
        return config;
    }
}
