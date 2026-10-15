package com.cryptoautotrader.api.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 코인별 <b>마지막 관측 가격</b> — 공유 풀 순자산 평가·포지션별 한도·캔들 없는 회차의 가격 판정용 (2026-10-14).
 *
 * <p>실시간 시세 이벤트(웹소켓 틱 · REST 대체 조회 · 코인별 강제 갱신)와 보유 코인 시세 보강({@link HeldMarkRefresher})이 적는다.
 * 평가 자체는 새 조회를 하지 않는다 — 평가가 계좌 잠금 안에서 일어나므로 거기서 거래소를 부르면 잠금이 그 호출 동안 묶인다.</p>
 *
 * <h3>🔴 최신성은 받은 시각이 아니라 관측 시각으로 판단한다 (2026-10-15 리뷰 8회차 후속)</h3>
 * <p>업비트가 주는 가격은 마지막 체결가다. 체결이 드문 코인은 10분 전 체결가가 지금 다시 온다 — 받은 시각을 기준으로 삼으면 그 낡은
 * 가격이 신선한 가격으로 인정된다. 그래서 세 시각을 나눈다: {@code at} = 이 가격의 <b>관측 기준 시각</b>(체결가면 체결 시각, 호가면
 * 호가 시각), {@code receivedAt} = 받은 시각, {@code source} = 출처. 최신성({@link #freshPrice})은 {@code at} 으로만 본다.
 * 관측 시각이 더 이른 값은 더 늦은 값을 덮지 않는다(재조회가 낡은 체결가를 가져와도 최근 호가 관측을 지우지 않는다).</p>
 */
@Component
public class MarkPriceBook {

    /** 웹소켓 체결 틱. */
    public static final String WS_TRADE = RealtimePriceEvent.WS_TRADE;
    /** REST 시세 조회의 마지막 체결가 — {@code at} 은 그 체결 시각. */
    public static final String REST_TRADE = RealtimePriceEvent.REST_TRADE;
    /**
     * REST 호가 조회 — 장부에는 평균값이 아니라 <b>매수호가 단계 자체</b>를 적고, 가격을 요청받을 때마다 <b>그때의 합산 보유량</b>을 위에서부터
     * 채운 평균으로 다시 계산한다(2026-10-16 리뷰 지적 — 평균값을 적어 두면 보유량이 늘어도 시각상 신선한 이전 평균이 재사용됐다). 보이는
     * 잔량이 그때의 보유량보다 적으면 가격이 없다(판정 불가). {@code at} 은 호가 시각. 🔴 조회 순간의 호가로 계산한 평가액일 뿐 그 가격에
     * 팔 수 있다는 보장은 아니다({@link HeldMarkRefresher}).
     */
    public static final String REST_ORDERBOOK_DEPTH = "REST_ORDERBOOK_DEPTH";
    /** 출처를 모르는 기록(시험·재시작 시험이 직접 넣은 값). */
    public static final String RECORDED = "RECORDED";

    /**
     * @param price      체결가 관측이면 그 가격. 호가 관측이면 최우선 매수호가(표시용 — 평가에 쓰지 않는다, {@link #priceFor})
     * @param at         관측 기준 시각 — 최신성 판단은 이것으로만 한다
     * @param receivedAt 받은 시각
     * @param bids       호가 관측의 매수호가 단계 [가격, 잔량] 높은 순 — 체결가 관측이면 {@code null}
     */
    public record Mark(BigDecimal price, Instant at, Instant receivedAt, String source, List<BigDecimal[]> bids) {
        public Mark(BigDecimal price, Instant at) {
            this(price, at, at, RECORDED, null);
        }

        public boolean isBook() {
            return bids != null;
        }

        /** 이 관측으로 본 {@code qty} 의 평가 가격 — 체결가면 그 가격, 호가면 채운 평균(모자라면 {@code null}). */
        public BigDecimal priceFor(BigDecimal qty) {
            return isBook() ? fill(qty, bids).price() : price;
        }
    }

    /**
     * @param price 보유량 전체를 채운 평균(내림) — 보이는 잔량이 모자라면 {@code null}
     * @param depth 보이는 매수 잔량 합
     */
    public record BookQuote(BigDecimal price, BigDecimal depth) {}

    /** 매수호가 단계를 위에서부터 {@code qty} 만큼 채운 평균(내림). */
    public static BookQuote fill(BigDecimal qty, List<BigDecimal[]> bids) {
        BigDecimal remaining = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal depth = BigDecimal.ZERO;
        for (BigDecimal[] b : bids) {
            depth = depth.add(b[1]);
            if (remaining.signum() > 0) {
                BigDecimal take = remaining.min(b[1]);
                proceeds = proceeds.add(take.multiply(b[0]));
                remaining = remaining.subtract(take);
            }
        }
        if (qty == null || qty.signum() <= 0 || remaining.signum() > 0) return new BookQuote(null, depth);
        return new BookQuote(proceeds.divide(qty, 8, java.math.RoundingMode.DOWN), depth);
    }

    /** 현재 합산 보유량 — 호가 관측을 가격으로 바꿀 때 쓴다. 없으면(시험 단위 생성) 호가 관측은 가격이 없다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 이 코인의 실자금 열린 보유량 합계(공유 계좌) — 보유가 없으면 0. */
    public BigDecimal heldQuantity(String coinPair) {
        if (jdbc == null || coinPair == null) return null;
        BigDecimal q = jdbc.queryForObject("SELECT COALESCE(SUM(size), 0) FROM position WHERE coin_pair = ? "
                + "AND session_kind IN ('LIVE', 'DYNAMIC') AND status IN ('OPEN', 'CLOSING') AND size > 0", BigDecimal.class, coinPair);
        return q == null ? BigDecimal.ZERO : q;
    }

    private final Map<String, Mark> marks = new ConcurrentHashMap<>();

    @EventListener
    public void onPrice(RealtimePriceEvent event) {
        Instant now = Instant.now();
        // 체결 시각을 모르는 이벤트(시험용 생성자)는 받은 시각으로 적는다 — 운영의 발행 지점은 모두 체결 시각을 싣는다.
        Instant at = event.getObservedAt() != null ? event.getObservedAt() : now;
        observe(event.getCoinCode(), event.getPrice(), at, now, event.getSource() != null ? event.getSource() : RECORDED);
    }

    /**
     * 관측 하나를 적는다 — 이미 있는 값보다 관측 시각이 이르면 버린다.
     *
     * @return 적었으면 참
     */
    public boolean observe(String coinPair, BigDecimal price, Instant at, Instant receivedAt, String source) {
        if (coinPair == null || price == null || price.signum() <= 0 || at == null) return false;
        return put(coinPair, new Mark(price, at, receivedAt, source, null));
    }

    /**
     * 호가 관측을 적는다 — 깊이가 모자란 호가도 적는다(더 최근 관측이므로 이전 호가 평균을 대신한다 → 가격을 물으면 판정 불가).
     *
     * @param bids 매수호가 단계 [가격, 잔량] 높은 순(가격·잔량이 양수인 것만)
     */
    public boolean observeBook(String coinPair, List<BigDecimal[]> bids, Instant at, Instant receivedAt) {
        if (coinPair == null || bids == null || bids.isEmpty() || at == null) return false;
        return put(coinPair, new Mark(bids.get(0)[0], at, receivedAt, REST_ORDERBOOK_DEPTH, List.copyOf(bids)));
    }

    private boolean put(String coinPair, Mark fresh) {
        Mark kept = marks.merge(coinPair, fresh, (old, neu) -> neu.at().isBefore(old.at()) ? old : neu);
        return kept == fresh;
    }

    /** 무조건 덮어쓴다 — 시험·재시작 시험이 상태를 만들거나 되돌릴 때. 운영 경로는 {@link #observe} 를 쓴다. */
    public void record(String coinPair, BigDecimal price, Instant at) {
        if (coinPair == null || price == null || price.signum() <= 0) return;
        marks.put(coinPair, new Mark(price, at));
    }

    /** 무조건 되돌린다 — 재시작 시험이 임시로 넣은 값을 원래 기록으로 돌릴 때. */
    public void restore(String coinPair, Mark mark) {
        if (coinPair == null) return;
        if (mark == null) marks.remove(coinPair);
        else marks.put(coinPair, mark);
    }

    /** 없으면 {@code null}. */
    public Mark get(String coinPair) {
        return coinPair == null ? null : marks.get(coinPair);
    }

    /** 유효한 현재가로 볼 수 있는 관측 가격의 최대 나이 — 포지션별 한도의 최신성 기준과 같다. */
    @org.springframework.beans.factory.annotation.Value("${position-limit.mark-max-age-seconds:120}")
    private long maxAgeSeconds = 120;

    public long maxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * 관측 시각이 기준 이내인 가격 — 없거나 낡았으면 {@code null} (2026-10-15 리뷰 7회차). 공유 풀 평가·포지션별 한도·캔들 없는 회차의
     * 손절·익절 판정이 쓴다 — 낡은 값은 현재가가 아니다. 호가 관측이면 <b>지금의 합산 보유량</b>으로 다시 계산한다(깊이가 모자라면 {@code null}).
     */
    public BigDecimal freshPrice(String coinPair, Instant now) {
        Mark m = get(coinPair);
        if (m == null || !m.isBook()) return freshPriceFor(coinPair, now, null);
        return freshPriceFor(coinPair, now, heldQuantity(coinPair));
    }

    /** {@link #freshPrice} 의 보유량 지정판 — 평가가 같은 문장에서 읽은 보유량과 맞춘다. 체결가 관측이면 {@code qty} 는 쓰지 않는다. */
    public BigDecimal freshPriceFor(String coinPair, Instant now, BigDecimal qty) {
        Mark m = get(coinPair);
        if (m == null || Duration.between(m.at(), now).getSeconds() > maxAgeSeconds) return null;
        return m.priceFor(qty);
    }

    /** 시험용 — 한 코인의 기록을 지운다. */
    public void remove(String coinPair) {
        if (coinPair != null) marks.remove(coinPair);
    }

    /** 시험용 — 기록을 지운다. */
    public void clear() {
        marks.clear();
    }
}
