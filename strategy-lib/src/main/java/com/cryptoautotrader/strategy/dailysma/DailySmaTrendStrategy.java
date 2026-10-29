package com.cryptoautotrader.strategy.dailysma;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일봉 종가 ÷ 20일 단순이동평균 보유/현금 — 12월 제한 실거래 시험 후보 (docs/LIMITED_LIVE_TEST_PLAN.md §2-2).
 *
 * <h3>규칙 (계산 전 고정, d0472fd)</h3>
 * <ul>
 *   <li>일봉 경계는 UTC 00:00. 일봉 종가 = 23:00 UTC 에 시작하는 H1 봉의 종가.</li>
 *   <li>t일 종가 &gt; 최근 20개 일봉 종가(t일 포함)의 단순 평균 → BUY(보유), 아니면 SELL(현금).</li>
 *   <li>신호는 <b>일봉 마감 봉(23:00 UTC)이 마지막 닫힌 봉일 때만</b> 낸다. 그 밖의 봉은 HOLD 다 —
 *       엔진이 매 H1 마감마다 평가하므로, 마감 봉이 아닐 때 신호를 내면 하루에 24번 매매 기회가 생긴다.</li>
 *   <li>20일 중 하루라도 마감 봉이 없으면 HOLD(관망). 빈 날을 앞뒤 값으로 메우지 않는다.</li>
 * </ul>
 *
 * <p>BUY/SELL 은 "그 상태여야 한다"는 뜻이다. 이미 보유 중인 BUY, 보유가 없는 SELL 은 엔진이 무시한다.
 * 엔진의 관문(EMA200·RANGE·급락)·손절·익절·시간 청산·전략 SELL 차단은 이 클래스 밖에서 그대로 적용되므로,
 * 실제 주문은 이 신호와 다를 수 있다 — 시험 대상은 "이 신호 + 운영 규칙"의 결합이다(문서 §2-3).</p>
 *
 * <p>상태가 없다. 손절·익절 제안값을 내지 않는다(엔진 기본 ATR 규칙을 쓴다).</p>
 */
public class DailySmaTrendStrategy implements Strategy {

    public static final String NAME = "DAILY_SMA20";
    static final int SMA_DAYS = 20;
    /** 일봉 마감 봉 시작 시각(UTC 시). 운영 값은 23 하나다. */
    static final int CLOSE_HOUR_UTC = 23;

    private final int closeHourUtc;

    public DailySmaTrendStrategy() {
        this(CLOSE_HOUR_UTC);
    }

    /**
     * 엔진 경로 시험 전용 — 시험은 벽시계(now) 기준으로 "닫힌 최신 봉"을 만들어야 해서, 마감 봉 시각을 옮길 수 있게 한다.
     * 규칙은 같고 하루의 경계만 {@code 23 − closeHourUtc} 시간 옮긴다. 운영 등록은 기본 생성자만 쓴다.
     */
    public DailySmaTrendStrategy(int closeHourUtc) {
        if (closeHourUtc < 0 || closeHourUtc > 23) {
            throw new IllegalArgumentException("closeHourUtc 범위 밖: " + closeHourUtc);
        }
        this.closeHourUtc = closeHourUtc;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
        Object tf = params.get("timeframe");
        if (tf != null && !"H1".equals(tf)) {
            return StrategySignal.hold("H1 전용 전략: timeframe=" + tf);
        }
        if (candles.isEmpty()) {
            return StrategySignal.hold("데이터 부족");
        }
        Candle last = candles.get(candles.size() - 1);
        if (!isDailyClose(last.getTime())) {
            return StrategySignal.hold("일봉 마감 봉 아님");
        }

        Map<LocalDate, BigDecimal> dailyClose = new HashMap<>();
        for (Candle c : candles) {
            if (isDailyClose(c.getTime())) {
                dailyClose.put(dayOf(c.getTime()), c.getClose());
            }
        }
        LocalDate today = dayOf(last.getTime());
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < SMA_DAYS; k++) {
            BigDecimal close = dailyClose.get(today.minusDays(k));
            if (close == null) {
                return StrategySignal.hold(String.format("일봉 종가 누락: %s (최근 %d일 중)", today.minusDays(k), SMA_DAYS));
            }
            sum = sum.add(close);
        }
        BigDecimal sma = sum.divide(BigDecimal.valueOf(SMA_DAYS), 8, RoundingMode.HALF_UP);
        BigDecimal close = last.getClose();
        BigDecimal strength = close.subtract(sma).abs()
                .divide(sma, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(1000))
                .min(BigDecimal.valueOf(100));

        if (close.compareTo(sma) > 0) {
            return StrategySignal.buy(strength, String.format("일봉 %s 종가 %s > SMA%d %s — 보유", today, close.toPlainString(),
                    SMA_DAYS, sma.setScale(0, RoundingMode.HALF_UP).toPlainString()));
        }
        return StrategySignal.sell(strength, String.format("일봉 %s 종가 %s ≤ SMA%d %s — 현금", today, close.toPlainString(),
                SMA_DAYS, sma.setScale(0, RoundingMode.HALF_UP).toPlainString()));
    }

    /**
     * 마감 봉과 19일 전 마감 봉까지 — 19 × 24 + 1 봉. 운영 조회 한도(H1 500개) 안이다.
     */
    @Override
    public int getMinimumCandleCount() {
        return (SMA_DAYS - 1) * 24 + 1;
    }

    private Instant shifted(Instant t) {
        return t.plus(CLOSE_HOUR_UTC - closeHourUtc, ChronoUnit.HOURS);
    }

    /** 봉 시작 시각의 시(hour)만 본다 — H1 봉은 정시에 시작한다. */
    private boolean isDailyClose(Instant t) {
        return shifted(t).atZone(ZoneOffset.UTC).getHour() == CLOSE_HOUR_UTC;
    }

    private LocalDate dayOf(Instant t) {
        return shifted(t).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
