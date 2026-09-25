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
 *   <li>time  = <b>그룹이 속한 시각 경계</b>(그룹 첫 캔들의 time 이 아니다)</li>
 * </ul>
 *
 * <h3>⚠️ 2026-09-21 — 리스트 위치가 아니라 시각으로 묶는다 (Wave 3-L)</h3>
 *
 * <p>이전 구현은 <b>리스트의 0번 인덱스부터 {@code factor} 개씩</b> 잘랐다. 그런데 호출자가
 * 넘기는 리스트는 고정 길이 슬라이딩 창이다({@code BacktestEngine.MAX_LOOKBACK}=500 ·
 * {@code TradingConstants.CANDLE_LOOKBACK}=500). 창이 매 캔들 1칸씩 미끄러지므로
 * <b>묶음 경계도 매 캔들 1칸씩 밀렸다.</b>
 *
 * <pre>
 * i=600  창=[101..600]  H4 = (101-104) (105-108) ...
 * i=601  창=[102..601]  H4 = (102-105) (106-109) ...
 * </pre>
 *
 * <p>상위봉 구성이 <b>언제 조회했느냐</b>에 따라 달라졌다. 가격이 전혀 움직이지 않아도 위상만
 * 돌아서 HTF 추세 판정이 뒤집힐 수 있었다 — 노이즈를 거르라고 둔 게이트가 노이즈를 주입한 셈이다.
 * 게다가 창이 500 에 차기 전에는 {@code windowStart} 가 0 에 고정돼 위상이 안정적이므로,
 * <b>500번째 캔들을 넘는 순간 거동이 바뀌었다.</b>
 *
 * <p>지금은 캔들의 {@code time} 을 epoch 기준으로 {@code ltfInterval × factor} 에 내림 정렬해
 * 묶는다. 업비트의 상위봉도 UTC 00:00 기준으로 정렬되므로 경계가 거래소와 일치한다.
 * <b>창이 어디서 시작하든 같은 구간은 같은 HTF 봉을 낸다.</b>
 *
 * <h3>선두 부분 그룹은 버린다</h3>
 *
 * <p>창의 첫 캔들이 경계 한가운데에서 시작하면 그 그룹은 <b>잘린 봉</b>이다 — open 이 실제
 * 상위봉 open 이 아니고 high/low 도 일부만 본 값이다. 그대로 두면 창 시작 위치가 결과에
 * 다시 스며든다. 500개 중 최대 {@code factor-1} 개를 버리는 비용으로 결정성을 얻는다.
 *
 * <p>반면 <b>말미의 부분 그룹은 남긴다</b> — 그것은 잘린 봉이 아니라 <b>형성 중인 봉</b>이고,
 * 실거래에서 트레이더가 보는 것과 같다. 이 비대칭은 의도된 것이다.
 */
public class CandleDownsampler {

    private CandleDownsampler() {}

    /**
     * @param ltfCandles  하위 TF 캔들 목록 (시각 오름차순)
     * @param factor      집계 배율 (예: 12 = 5m×12 → 1h)
     */
    public static List<Candle> downsample(List<Candle> ltfCandles, int factor) {
        if (factor <= 1 || ltfCandles.size() < 2) {
            return new ArrayList<>(ltfCandles);
        }

        long ltfSeconds = inferIntervalSeconds(ltfCandles);
        if (ltfSeconds <= 0) {
            // 시각을 신뢰할 수 없다 — 묶지 않고 원본을 돌려준다. 잘못된 경계로 묶는 것보다
            // HTF 를 포기하는 편이 낫다(호출자는 캔들 부족으로 보고 보수적으로 처리한다).
            return new ArrayList<>(ltfCandles);
        }
        long bucketSeconds = ltfSeconds * factor;

        List<Candle> result = new ArrayList<>();
        int i = 0;

        // ── 선두 부분 그룹 건너뛰기 ──
        long firstBucket = bucketOf(ltfCandles.get(0), bucketSeconds);
        if (ltfCandles.get(0).getTime().getEpochSecond() != firstBucket) {
            while (i < ltfCandles.size() && bucketOf(ltfCandles.get(i), bucketSeconds) == firstBucket) {
                i++;
            }
        }

        while (i < ltfCandles.size()) {
            long bucket = bucketOf(ltfCandles.get(i), bucketSeconds);
            int end = i;
            while (end < ltfCandles.size() && bucketOf(ltfCandles.get(end), bucketSeconds) == bucket) {
                end++;
            }
            List<Candle> group = ltfCandles.subList(i, end);

            BigDecimal high = group.get(0).getHigh();
            BigDecimal low  = group.get(0).getLow();
            BigDecimal volume = BigDecimal.ZERO;
            for (Candle c : group) {
                if (c.getHigh().compareTo(high) > 0) high = c.getHigh();
                if (c.getLow().compareTo(low) < 0)   low  = c.getLow();
                volume = volume.add(c.getVolume());
            }

            result.add(Candle.builder()
                    .time(java.time.Instant.ofEpochSecond(bucket))
                    .open(group.get(0).getOpen())
                    .high(high)
                    .low(low)
                    .close(group.get(group.size() - 1).getClose())
                    .volume(volume)
                    .build());

            i = end;
        }
        return result;
    }

    /**
     * {@link #downsample} 과 같지만 <b>말미의 형성 중(미완결) 상위봉을 버린다.</b>
     *
     * <p>2026-09-25 추가 — 전향 검증의 팔 A 전용이다.
     * {@link #downsample} 은 말미의 부분 그룹을 <b>남긴다</b>(형성 중인 봉 = 실거래에서
     * 트레이더가 보는 것). 그것은 미래 정보가 아니므로 잘못된 동작이 아니다.
     * 이 메서드는 <b>대안</b>으로, 완결된 상위봉만 쓰는 변경안을 검증하기 위한 것이다.
     *
     * <p>🔴 {@link #downsample} 의 동작은 바꾸지 않는다 — 기존 전략은 전부 그대로다.
     *
     * <p>완결 판정: 마지막 LTF 캔들의 시각이 그 상위봉 구간의 <b>마지막 슬롯</b>
     * (경계 + (factor−1) × ltfInterval) 이어야 한다. 결측이 있으면 완결로 보지 않는다.
     */
    public static List<Candle> downsampleClosedOnly(List<Candle> ltfCandles, int factor) {
        List<Candle> all = downsample(ltfCandles, factor);
        if (all.isEmpty() || ltfCandles.size() < 2 || factor <= 1) {
            return all;
        }
        long ltfSeconds = inferIntervalSeconds(ltfCandles);
        if (ltfSeconds <= 0) {
            return all;                      // 간격을 못 믿으면 downsample 과 동일하게 둔다
        }
        long bucketSeconds = ltfSeconds * factor;
        long lastLtf = ltfCandles.get(ltfCandles.size() - 1).getTime().getEpochSecond();
        long boundary = Math.floorDiv(lastLtf, bucketSeconds) * bucketSeconds;
        boolean complete = (lastLtf - boundary) == ltfSeconds * (factor - 1);
        return complete ? all : all.subList(0, all.size() - 1);
    }

    /** 캔들이 속한 상위봉의 시작 시각(epoch 초). */
    private static long bucketOf(Candle c, long bucketSeconds) {
        return Math.floorDiv(c.getTime().getEpochSecond(), bucketSeconds) * bucketSeconds;
    }

    /**
     * 하위 TF 간격을 캔들 시각에서 추론한다 — <b>연속 차이의 최솟값</b>.
     *
     * <p>결측이 있으면 일부 간격이 2배·3배로 벌어지므로 평균이나 첫 차이를 쓰면 틀린다.
     * 최솟값은 결측이 있어도 참값을 준다.
     */
    private static long inferIntervalSeconds(List<Candle> candles) {
        long min = Long.MAX_VALUE;
        for (int i = 1; i < candles.size(); i++) {
            long d = candles.get(i).getTime().getEpochSecond()
                   - candles.get(i - 1).getTime().getEpochSecond();
            if (d > 0 && d < min) min = d;
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }
}
