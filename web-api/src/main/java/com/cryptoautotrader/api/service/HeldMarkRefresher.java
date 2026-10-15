package com.cryptoautotrader.api.service;

import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>보유 코인 시세 보강</b> — 실자금 보유의 관측 가격을 웹소켓과 별개로 최신으로 유지한다 (2026-10-15 리뷰 8회차 후속).
 *
 * <h3>왜</h3>
 * <p>공유 풀 손실 한도는 유효한 관측 가격(관측 시각 120초 이내) 없이 평가되는 보유가 하나라도 있으면 풀 전체 신규 진입을 막는다(§6.9-①).
 * 웹소켓 틱은 <b>체결이 있을 때만</b> 온다 — 연결이 정상이어도 체결이 드문 보유 코인은 관측 가격이 낡고, 재시작 직후에는 장부가 비어
 * 있다. 기존 REST 대체 조회는 웹소켓이 끊겼을 때만 돈다. 차단 기준을 낮추지 않고 판정에 필요한 데이터를 공급한다.</p>
 *
 * <h3>무엇을 관측으로 인정하는가</h3>
 * <ol>
 *   <li>REST 시세의 마지막 체결가 — 관측 시각은 <b>체결 시각</b>({@code trade_timestamp}). 🔴 10분 전 체결가를 지금 다시 받았다고
 *       받은 시각으로 신선하다고 보지 않는다 — 장부의 최신성은 체결 시각으로만 판단한다({@link MarkPriceBook}).</li>
 *   <li>그래도 낡으면 REST 호가로 <b>보유량 전체를 매수호가에 위에서부터 차례로 채운 평균 가격</b>(2026-10-16 리뷰 지적 — 최우선
 *       매수호가 하나만 쓰면 그 잔량보다 많이 보유할 때 더 낮은 가격으로 팔릴 부분을 보지 못한다). 보유량은 같은 코인의 실자금 열린
 *       포지션 합계(공유 계좌). 관측 시각은 호가 시각. 🔴 <b>보이는 호가 잔량 합이 보유량보다 적으면 인정하지 않는다</b>(판정 불가 —
 *       평가하지 않고 차단 유지). 호가가 비었거나 호가 시각이 낡았어도 인정하지 않는다.</li>
 * </ol>
 *
 * <h3>🔴 이 가격이 보장하지 않는 것</h3>
 * <p>호가 평균 가격은 <b>조회 순간 보이는 호가</b>로 계산한 평가액이다. 실제로 그 가격에 팔 수 있다는 보장이 아니다 — 주문이 닿기 전에
 * 호가가 바뀌거나 빠질 수 있고, 다른 매도와 겹칠 수 있고, 업비트가 돌려주는 호가 단계 밖의 깊이는 보지 않는다. 체결가 경로도 마찬가지로
 * 마지막 체결의 가격일 뿐 지금 팔 수 있는 가격이 아니다. 둘 다 "손실을 과소평가하지 않는다" 고 말할 수 있는 값이 아니다 — 시각이 최신인
 * 관측 가격으로만 인정한다.</p>
 * <p>둘 다 실패하면 그 보유는 계속 "관측 가격 없음" 이고 풀 진입 차단이 유지된다(차단 기준은 그대로). 실패가 시작되면 ERROR·경보 한 번,
 * 회복되면 기록한다. 이 보강은 장부만 채운다 — 손절·익절 이벤트를 내지 않는다(그 경로는 종전 그대로).</p>
 *
 * <p>대상: LIVE·DYNAMIC 실자금 열린 포지션(OPEN·CLOSING, 잔량 &gt; 0)의 코인 중 관측 가격이 {@link #REFRESH_AFTER} 보다 오래된 것 —
 * 기준(120초)이 끝나기 전에 미리 채워 공백을 줄인다. 주기 15초, 기동 5초 뒤 첫 실행(재시작 직후의 빈 장부).</p>
 */
@Slf4j
@Service
public class HeldMarkRefresher {

    /** 관측 가격이 이만큼 지나면 다시 조회한다 — 최신성 기준 120초의 절반. */
    static final Duration REFRESH_AFTER = Duration.ofSeconds(60);

    private final MarkPriceBook markPriceBook;
    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    /** 공급 실패가 시작된 시각과 사유 — 경보는 실패가 시작될 때 한 번. */
    private final AtomicReference<String> failing = new AtomicReference<>();

    public HeldMarkRefresher(MarkPriceBook markPriceBook, JdbcTemplate jdbc) {
        this.markPriceBook = markPriceBook;
        this.jdbc = jdbc;
    }

    /** 한 회차의 결과 — 시험·로그용. */
    public record Result(List<String> targets, List<String> byTrade, List<String> byBook, List<String> unobserved, String error) {}

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void refresh() {
        try {
            refreshNow(Instant.now());
        } catch (Exception e) {
            log.error("[시세 보강] 회차 오류: {}", e.getMessage());
        }
    }

    public Result refreshNow(Instant now) {
        List<String> targets = new ArrayList<>();
        Map<String, BigDecimal> held = heldQuantities();
        for (String c : held.keySet()) {
            MarkPriceBook.Mark m = markPriceBook.get(c);
            if (m == null || Duration.between(m.at(), now).compareTo(REFRESH_AFTER) > 0) targets.add(c);
        }
        if (targets.isEmpty()) {
            recovered();
            return new Result(List.of(), List.of(), List.of(), List.of(), null);
        }
        if (upbitRestClient == null) {
            return new Result(targets, List.of(), List.of(), targets, "시세 REST 클라이언트 없음");
        }
        List<String> byTrade = new ArrayList<>();
        List<String> byBook = new ArrayList<>();
        StringBuilder errors = new StringBuilder();

        // ① 시세 — 체결 시각으로 적는다. 낡은 체결가는 적혀도 신선하지 않다.
        try {
            for (Map<String, Object> t : upbitRestClient.getTicker(String.join(",", targets))) {
                String market = (String) t.get("market");
                Object px = t.get("trade_price");
                Instant tradeAt = RealtimePriceEvent.tradeTime(t);
                if (market == null || px == null || tradeAt == null) continue;
                markPriceBook.observe(market, new BigDecimal(px.toString()), tradeAt, now, MarkPriceBook.REST_TRADE);
                if (markPriceBook.freshPrice(market, now) != null && !tradeAt.isBefore(now.minus(REFRESH_AFTER))) {
                    byTrade.add(market);
                }
            }
        } catch (Exception e) {
            errors.append("시세 조회 실패: ").append(e.getMessage()).append("; ");
        }

        // ② 체결이 조용한 코인 — 보유량 전체를 매수호가에 차례로 채운 평균 가격
        for (String c : targets) {
            if (byTrade.contains(c)) continue;
            try {
                BookQuote q = bookPrice(c, held.get(c));
                if (q == null) {
                    errors.append(c).append(" 호가 없음; ");
                } else if (q.price() == null) {
                    errors.append(c).append(" 호가 깊이 부족(보이는 매수 잔량 ").append(q.depth().toPlainString())
                            .append(" < 보유 ").append(held.get(c).toPlainString()).append("); ");
                } else if (Duration.between(q.at(), now).getSeconds() > markPriceBook.maxAgeSeconds()) {
                    errors.append(c).append(" 호가 시각 낡음(").append(q.at()).append("); ");
                } else {
                    markPriceBook.observe(c, q.price(), q.at(), now, MarkPriceBook.REST_ORDERBOOK_DEPTH);
                    byBook.add(c);
                }
            } catch (Exception e) {
                errors.append(c).append(" 호가 조회 실패: ").append(e.getMessage()).append("; ");
            }
        }

        List<String> unobserved = new ArrayList<>();
        for (String c : targets) {
            if (markPriceBook.freshPrice(c, now) == null) unobserved.add(c);
        }
        String error = errors.length() == 0 ? null : errors.toString();
        if (unobserved.isEmpty()) {
            recovered();
            if (!byBook.isEmpty()) log.info("[시세 보강] 체결이 조용한 보유 {} — 보유량을 매수호가에 채운 평균 가격으로 관측", byBook);
        } else {
            failed(unobserved, error);
        }
        return new Result(targets, byTrade, byBook, unobserved, error);
    }

    /**
     * @param price 보유량 전체를 채운 평균 가격 — 보이는 매수 잔량이 모자라면 {@code null}
     * @param depth 보이는 매수 잔량 합
     */
    record BookQuote(BigDecimal price, BigDecimal depth, Instant at) {}

    /** 호가로 본 보유량 평균 매도 가격 — 호가가 없으면 {@code null}. */
    @SuppressWarnings("unchecked")
    private BookQuote bookPrice(String coin, BigDecimal qty) throws Exception {
        List<Map<String, Object>> books = upbitRestClient.getOrderbook(coin);
        if (books == null || books.isEmpty()) return null;
        Map<String, Object> book = books.get(0);
        Object units = book.get("orderbook_units");
        Object ts = book.get("timestamp");
        if (!(units instanceof List<?> list) || list.isEmpty() || !(ts instanceof Number n)) return null;
        return fill(qty, (List<Map<String, Object>>) list, Instant.ofEpochMilli(n.longValue()));
    }

    /** 매수호가 단계를 위에서부터 채운다(업비트 순서 = 매수호가 높은 순). 평균은 내림으로 자른다. */
    static BookQuote fill(BigDecimal qty, List<Map<String, Object>> units, Instant at) {
        BigDecimal remaining = qty;
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal depth = BigDecimal.ZERO;
        for (Map<String, Object> u : units) {
            Object p = u.get("bid_price");
            Object s = u.get("bid_size");
            if (p == null || s == null) continue;
            BigDecimal price = new BigDecimal(p.toString());
            BigDecimal size = new BigDecimal(s.toString());
            if (price.signum() <= 0 || size.signum() <= 0) continue;
            depth = depth.add(size);
            if (remaining.signum() > 0) {
                BigDecimal take = remaining.min(size);
                proceeds = proceeds.add(take.multiply(price));
                remaining = remaining.subtract(take);
            }
        }
        if (qty == null || qty.signum() <= 0 || remaining.signum() > 0) return new BookQuote(null, depth, at);
        return new BookQuote(proceeds.divide(qty, 8, java.math.RoundingMode.DOWN), depth, at);
    }

    /** 코인별 실자금 열린 보유량 합계(공유 계좌). */
    private Map<String, BigDecimal> heldQuantities() {
        Map<String, BigDecimal> out = new java.util.TreeMap<>();
        jdbc.query("SELECT coin_pair, SUM(size) FROM position WHERE session_kind IN ('LIVE', 'DYNAMIC') "
                + "AND status IN ('OPEN', 'CLOSING') AND size > 0 GROUP BY coin_pair",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getBigDecimal(2)));
        return out;
    }

    private void failed(List<String> unobserved, String error) {
        String desc = Instant.now() + " 관측 가격 없음 " + unobserved + (error == null ? "" : " — " + error);
        if (failing.compareAndSet(null, desc)) {
            log.error("🔴 [시세 보강] 보유 코인의 관측 가격을 얻지 못했다 {} — 공유 풀 신규 진입이 막힌다(청산·추적은 계속): {}",
                    unobserved, error);
            if (telegram != null) {
                try {
                    telegram.sendCustomNotification("⚠️ 보유 코인 시세 공급 실패 " + unobserved
                            + " — 관측 가격이 없어 공유 풀 신규 진입이 막혀 있다(청산·추적은 계속). 원인: " + error);
                } catch (Exception e) {
                    log.warn("시세 보강 경보 전송 실패: {}", e.getMessage());
                }
            }
        } else if (!java.util.Objects.equals(lastReason.get(), reasonKey(unobserved, error))) {
            // 사유가 바뀌면 남긴다(예: 조회 실패 → 호가 깊이 부족) — 경보는 실패가 시작될 때 한 번
            log.warn("[시세 보강] 관측 가격 없음 지속 — 사유 변경 {}: {}", unobserved, error);
        } else {
            log.debug("[시세 보강] 관측 가격 없음 지속 {}: {}", unobserved, error);
        }
        lastReason.set(reasonKey(unobserved, error));
    }

    /** 직전 실패 사유 — 사유가 바뀔 때만 다시 남기려고. 숫자(잔량 등)는 빼고 비교한다. */
    private final AtomicReference<String> lastReason = new AtomicReference<>();

    private static String reasonKey(List<String> unobserved, String error) {
        return unobserved + "|" + (error == null ? "" : error.replaceAll("[0-9.]+", "#"));
    }

    private void recovered() {
        String prev = failing.getAndSet(null);
        lastReason.set(null);
        if (prev != null) log.warn("[시세 보강] 회복 — 보유 코인 관측 가격 공급 재개 (종전: {})", prev);
    }

    /** 지금 공급 실패 중이면 그 내용 — 상태 확인·시험용. */
    public String failingSince() {
        return failing.get();
    }

    /** 시험용. */
    void setUpbitRestClient(UpbitRestClient c) {
        this.upbitRestClient = c;
    }
}
