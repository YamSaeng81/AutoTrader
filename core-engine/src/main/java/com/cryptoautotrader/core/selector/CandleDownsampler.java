package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 하위 타임프레임 캔들을 상위 타임프레임으로 집계(다운샘플)한다.
 *
 * <p>사용 예 (5m → 1h 변환):
 * <pre>{@code
 * List<Candle> htfCandles = CandleDownsampler.downsample(ltfCandles, 12); // 5m × 12 = 1h
 * }</pre>
 *
 * <p>집계 규칙:
 * <ul>
 *   <li>open  = 첫 캔들 open</li>
 *   <li>high  = 그룹 내 최고가</li>
 *   <li>low   = 그룹 내 최저가</li>
 *   <li>close = 마지막 캔들 close</li>
 *   <li>volume = 그룹 내 합산</li>
 *   <li>time  = 첫 캔들 time</li>
 * </ul>
 *
 * <p>불완전한 마지막 그룹(캔들 수 < factor)도 포함하여 현재 진행 중인 HTF 캔들을 반영한다.
 */
public class CandleDownsampler {

    private CandleDownsampler() {}

    /**
     * @param ltfCandles  하위 TF 캔들 목록
     * @param factor      업샘플 배율 (예: 12 = 5m×12 → 1h)
     */
    public static List<Candle> downsample(List<Candle> ltfCandles, int factor) {
        if (factor <= 1) {
            return new ArrayList<>(ltfCandles);
        }

        List<Candle> result = new ArrayList<>();
        int i = 0;
        while (i < ltfCandles.size()) {
            int end = Math.min(i + factor, ltfCandles.size());
            List<Candle> group = ltfCandles.subList(i, end);

            BigDecimal open   = group.get(0).getOpen();
            BigDecimal close  = group.get(group.size() - 1).getClose();
            BigDecimal high   = group.stream().map(Candle::getHigh).reduce(BigDecimal.ZERO, BigDecimal::max);
            BigDecimal low    = group.stream().map(Candle::getLow).reduce(group.get(0).getLow(), BigDecimal::min);
            BigDecimal volume = group.stream().map(Candle::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(Candle.builder()
                    .time(group.get(0).getTime())
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build());

            i += factor;
        }
        return result;
    }
}
