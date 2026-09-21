package com.cryptoautotrader.strategy.grid;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StatefulStrategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 그리드 전략
 * - 가격 범위를 N등분하여 그리드 레벨 설정
 * - 그리드 하단 근접 → BUY, 그리드 상단 근접 → SELL
 * - StatefulStrategy 구현: 레벨별 진입 이력 추적으로 동일 레벨 중복 매매 방지
 */
public class GridStrategy implements StatefulStrategy {

    private static final int SCALE = 8;
    // 그리드 범위가 이 비율 이상 변하면 상태 초기화 (1%)
    private static final BigDecimal RANGE_RESET_THRESHOLD = new BigDecimal("0.01");

    // 현재 진입 중인 그리드 레벨 인덱스 집합
    private final Set<Integer> activeLevels = new HashSet<>();
    // 직전 그리드 범위 (범위 재설정 감지용)
    private BigDecimal lastHighest = null;
    private BigDecimal lastLowest = null;

    @Override
    public String getName() {
        return "GRID";
    }

    @Override
    public void resetState() {
        activeLevels.clear();
        lastHighest = null;
        lastLowest = null;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        int lookbackPeriod = getInt(params, "lookbackPeriod", 100);
        int gridCount = getInt(params, "gridCount", 10);
        double triggerPct = getDouble(params, "triggerPct", 5.0);
        // 레벨 중복 진입 방지(dedup). 복합 전략의 투표 성분으로 쓰일 때는 BUY "투표"만으로
        // 레벨이 진입됨 처리되어(실제 매수 여부와 무관) 이후 기여가 침묵하는 상태 오염이 있다.
        // false 로 끄면 상태 추적 없이 순수 위치 신호만 반환한다 — A/B 백테스트 비교용.
        boolean levelDedup = getBoolean(params, "levelDedupEnabled", true);

        if (candles.size() < lookbackPeriod) {
            return StrategySignal.hold("데이터 부족");
        }

        // lookback 기간 동안의 고저가로 그리드 범위 설정
        int start = candles.size() - lookbackPeriod;
        BigDecimal highest = candles.get(start).getHigh();
        BigDecimal lowest = candles.get(start).getLow();
        for (int i = start + 1; i < candles.size(); i++) {
            highest = highest.max(candles.get(i).getHigh());
            lowest = lowest.min(candles.get(i).getLow());
        }

        BigDecimal range = highest.subtract(lowest);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return StrategySignal.hold("가격 범위 = 0");
        }

        // 그리드 범위가 1% 이상 변경되면 상태 초기화 (새 그리드 시작)
        if (isRangeChanged(highest, lowest)) {
            activeLevels.clear();
        }
        lastHighest = highest;
        lastLowest = lowest;

        BigDecimal gridSize = range.divide(BigDecimal.valueOf(gridCount), SCALE, RoundingMode.HALF_UP);
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        // 현재가가 어떤 그리드 레벨에 위치하는지
        BigDecimal gridPosition = currentPrice.subtract(lowest)
                .divide(gridSize, SCALE, RoundingMode.HALF_UP);

        // 가장 가까운 그리드 레벨과의 거리
        BigDecimal nearestLevel = gridPosition.setScale(0, RoundingMode.HALF_UP);
        int levelIndex = nearestLevel.intValue();
        BigDecimal distanceFromLevel = gridPosition.subtract(nearestLevel).abs();
        BigDecimal triggerThreshold = BigDecimal.valueOf(triggerPct / 100.0);

        BigDecimal positionRatio = gridPosition.divide(BigDecimal.valueOf(gridCount), SCALE, RoundingMode.HALF_UP);

        // 하단 그리드 (하위 30%) 근접 → BUY (이미 진입한 레벨은 중복 매매 방지)
        if (positionRatio.compareTo(BigDecimal.valueOf(0.3)) <= 0
                && distanceFromLevel.compareTo(triggerThreshold) <= 0) {

            if (levelDedup && activeLevels.contains(levelIndex)) {
                return StrategySignal.hold(
                        String.format("그리드 레벨 %d 이미 진입됨 (중복 방지)", levelIndex));
            }

            BigDecimal strength = BigDecimal.ONE.subtract(positionRatio).multiply(BigDecimal.valueOf(100));
            if (levelDedup) {
                activeLevels.add(levelIndex);
            }
            return StrategySignal.buy(strength,
                    String.format("그리드 하단: 레벨 %.1f/%d, 가격=%.2f", gridPosition, gridCount, currentPrice));
        }

        // 상단 그리드 (상위 30%) 근접 → SELL (진입 레벨 해제)
        if (positionRatio.compareTo(BigDecimal.valueOf(0.7)) >= 0
                && distanceFromLevel.compareTo(triggerThreshold) <= 0) {

            // ⚠️ 2026-09-21 (Wave 3-I) — remove(levelIndex) 는 **아무것도 지우지 못했다.**
            //
            // 매수는 positionRatio <= 0.3, 즉 레벨 0~3 에서만 일어난다. 그런데 해제는
            // positionRatio >= 0.7, 즉 레벨 7~10 에서만 시도됐다. **두 집합이 구조적으로
            // 겹치지 않는다** — gridCount 가 3 보다 크면 언제나 그렇다.
            //
            // 결과: 한 번 잡은 레벨은 범위가 1% 넘게 변해 activeLevels 가 통째로 비워질
            // 때까지 영원히 잠긴다. GRID 는 레벨 0~3 에 각각 한 번씩, 평생 최대 4번 BUY 를
            // 내고 그 뒤로는 "이미 진입됨" HOLD 만 반복했다. COMPOSITE 계열에서 0.2 가중
            // 성분으로 쓰이므로, 그 지분이 조용히 사라진 채 돌고 있었다.
            //
            // 올바른 그리드 의미: 가격이 위로 올라가 청산했으면 **그 아래 레벨은 전부**
            // 다시 살 수 있어야 한다. 현재 레벨보다 위에 잡아둔 것은 아직 청산 대상이
            // 아니므로 남긴다.
            activeLevels.removeIf(level -> level <= levelIndex);
            BigDecimal strength = positionRatio.multiply(BigDecimal.valueOf(100));
            return StrategySignal.sell(strength,
                    String.format("그리드 상단: 레벨 %.1f/%d, 가격=%.2f", gridPosition, gridCount, currentPrice));
        }

        return StrategySignal.hold(String.format("그리드 중립: 레벨 %.1f/%d", gridPosition, gridCount));
    }

    /**
     * 이전 그리드 범위 대비 현재 범위가 RANGE_RESET_THRESHOLD 이상 변경되었으면 true 반환
     */
    private boolean isRangeChanged(BigDecimal highest, BigDecimal lowest) {
        if (lastHighest == null || lastLowest == null) {
            return false;
        }
        BigDecimal highDiff = highest.subtract(lastHighest).abs()
                .divide(lastHighest, SCALE, RoundingMode.HALF_UP);
        BigDecimal lowDiff = lowest.subtract(lastLowest).abs()
                .divide(lastLowest, SCALE, RoundingMode.HALF_UP);
        return highDiff.compareTo(RANGE_RESET_THRESHOLD) > 0
                || lowDiff.compareTo(RANGE_RESET_THRESHOLD) > 0;
    }

    @Override
    public int getMinimumCandleCount() {
        return 100;
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultVal;
    }
}
