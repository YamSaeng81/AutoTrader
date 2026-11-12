package com.cryptoautotrader.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실시간 처리기의 보호선 첫 통과 판단 (총괄 v1.155 §4-2 → v1.157 §125 보완, 2026-11-12).
 *
 * <p>LIVE·DYNAMIC 실시간 처리기는 코인별 점검 간격(평상시 5초·LIVE 급등락 1초) 안의 시세를 버린다. 다만 보호선을 <b>처음</b> 발동하는
 * 유효 시세는 간격과 무관하게 한 번 점검한다. "발동"은 근사선이 아니라 실제 판정 규칙({@link #stopHit}·{@link #takeProfitHit})
 * 그대로다 — 처리기의 실제 손절·익절 판정도 같은 메서드를 부른다. 그래서 판정에 이르지 못하는 시세가 점검을 불러 직전 유효 시세를
 * 보호선 아래로 옮겨 놓고, 그 뒤 실제 첫 발동 시세를 "첫 통과 아님"으로 놓치는 일이 없다(b5f8a8fd 의 근사선 ×1.000001 반례:
 * 평균 300·손절 5%·286→285.00025→284 에서 매도 0).</p>
 *
 * <p>보호선은 마지막 전체 점검 뒤 OPEN 으로 남은 포지션의 판정 입력(평균가·절대 손절가·손절%·익절가)이다. 매 틱 DB 를 조회하지 않는다.
 * 포지션마다 따로 보므로 같은 코인의 여러 보호선 중 하나라도 처음 발동하면 점검한다. 첫 발동 뒤 계속 발동 구간에 머무는 시세는
 * 종전 간격대로 점검한다(유계). 낡은·비가격 시세는 처리기가 이 앞에서 버려 직전 유효 시세를 바꾸지 않는다.</p>
 *
 * <p>한계: 마지막 전체 점검 뒤 새로 열린 포지션, 바뀐 평균가·손절가·익절가는 다음 전체 점검(최대 간격 1회)부터 반영된다.</p>
 */
final class RealtimeProtectLines {

    /** 한 포지션의 판정 입력. avgPrice 는 양수(처리기가 0·null 포지션은 판정하지 않는다). */
    record Line(BigDecimal avgPrice, BigDecimal stopLossPrice, BigDecimal stopLossPct, BigDecimal takeProfitPrice) {
        boolean hit(BigDecimal price) {
            return takeProfitHit(price, takeProfitPrice) || stopHit(price, avgPrice, stopLossPrice, stopLossPct);
        }
    }

    private final Map<String, List<Line>> lines = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastValidPrice = new ConcurrentHashMap<>();

    /** 실시간 손익률(%) — 처리기 로그·판정에 쓰는 기존 식 그대로: (가격−평균)/평균 을 6자리 HALF_UP 반올림 뒤 ×100. */
    static BigDecimal pnlPct(BigDecimal price, BigDecimal avgPrice) {
        return price.subtract(avgPrice).divide(avgPrice, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    /** 실제 손절 판정 — 절대 손절가가 있으면 가격 ≤ 절대가, 없으면 손익률 ≤ −손절%. 둘 다 없으면 미발동. */
    static boolean stopHit(BigDecimal price, BigDecimal avgPrice, BigDecimal stopLossPrice, BigDecimal stopLossPct) {
        if (stopLossPrice != null) return price.compareTo(stopLossPrice) <= 0;
        return stopLossPct != null && pnlPct(price, avgPrice).compareTo(stopLossPct.negate()) <= 0;
    }

    /** 실제 익절 판정 — 익절가가 있으면 가격 ≥ 익절가. */
    static boolean takeProfitHit(BigDecimal price, BigDecimal takeProfitPrice) {
        return takeProfitPrice != null && price.compareTo(takeProfitPrice) >= 0;
    }

    /**
     * 이번 유효 시세가 어떤 보호선을 처음 발동하는가 — 직전 유효 시세에서는 그 보호선이 발동하지 않았고 이번에는 발동한다.
     * 직전 유효 시세를 이번 시세로 바꾼다(점검 여부와 무관).
     */
    boolean firstHit(String coinCode, BigDecimal price) {
        BigDecimal prev = lastValidPrice.put(coinCode, price);
        List<Line> ls = lines.get(coinCode);
        if (ls == null) return false;
        for (Line l : ls) {
            if (l.hit(price) && (prev == null || !l.hit(prev))) return true;
        }
        return false;
    }

    /** 한 번의 전체 점검에서 모은, 점검 뒤에도 OPEN 으로 남은 포지션의 보호선. */
    Collector collector() {
        return new Collector();
    }

    final class Collector {
        private final List<Line> next = new ArrayList<>();

        void add(BigDecimal avgPrice, BigDecimal stopLossPrice, BigDecimal stopLossPct, BigDecimal takeProfitPrice) {
            next.add(new Line(avgPrice, stopLossPrice, stopLossPct, takeProfitPrice));
        }

        void store(String coinCode) {
            if (next.isEmpty()) lines.remove(coinCode);
            else lines.put(coinCode, List.copyOf(next));
        }
    }
}
