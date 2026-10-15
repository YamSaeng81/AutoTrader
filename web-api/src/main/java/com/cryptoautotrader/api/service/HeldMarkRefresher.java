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
 *   <li>그래도 낡으면 REST 호가의 <b>최우선 매수호가</b> — 관측 시각은 호가 시각. 체결이 없어도 지금 시장가로 팔면 받을 첫 가격이라
 *       보유 평가가 손실을 과소평가하지 않는 쪽이다(호가 깊이를 넘는 슬리피지는 넣지 않는다 — 종전 평가와 같은 한계). 호가가 비었거나
 *       호가 시각이 낡았으면 인정하지 않는다.</li>
 * </ol>
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
    public record Result(List<String> targets, List<String> byTrade, List<String> byBid, List<String> unobserved, String error) {}

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
        for (String c : heldCoins()) {
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
        List<String> byBid = new ArrayList<>();
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

        // ② 체결이 조용한 코인 — 최우선 매수호가
        for (String c : targets) {
            if (byTrade.contains(c)) continue;
            try {
                BidQuote q = bestBid(c);
                if (q == null) {
                    errors.append(c).append(" 호가 없음; ");
                } else if (Duration.between(q.at(), now).getSeconds() > markPriceBook.maxAgeSeconds()) {
                    errors.append(c).append(" 호가 시각 낡음(").append(q.at()).append("); ");
                } else {
                    markPriceBook.observe(c, q.price(), q.at(), now, MarkPriceBook.REST_ORDERBOOK_BID);
                    byBid.add(c);
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
            if (!byBid.isEmpty()) log.info("[시세 보강] 체결이 조용한 보유 {} — 최우선 매수호가로 관측", byBid);
        } else {
            failed(unobserved, error);
        }
        return new Result(targets, byTrade, byBid, unobserved, error);
    }

    record BidQuote(BigDecimal price, Instant at) {}

    /** 최우선 매수호가와 호가 시각 — 호가가 비었으면 {@code null}. */
    @SuppressWarnings("unchecked")
    private BidQuote bestBid(String coin) throws Exception {
        List<Map<String, Object>> books = upbitRestClient.getOrderbook(coin);
        if (books == null || books.isEmpty()) return null;
        Map<String, Object> book = books.get(0);
        Object units = book.get("orderbook_units");
        if (!(units instanceof List<?> list) || list.isEmpty()) return null;
        Object bid = ((Map<String, Object>) list.get(0)).get("bid_price");
        Object ts = book.get("timestamp");
        if (bid == null || !(ts instanceof Number n)) return null;
        BigDecimal price = new BigDecimal(bid.toString());
        return price.signum() > 0 ? new BidQuote(price, Instant.ofEpochMilli(n.longValue())) : null;
    }

    private List<String> heldCoins() {
        return jdbc.queryForList("SELECT DISTINCT coin_pair FROM position WHERE session_kind IN ('LIVE', 'DYNAMIC') "
                + "AND status IN ('OPEN', 'CLOSING') AND size > 0 ORDER BY coin_pair", String.class);
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
        } else {
            log.debug("[시세 보강] 관측 가격 없음 지속 {}: {}", unobserved, error);
        }
    }

    private void recovered() {
        String prev = failing.getAndSet(null);
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
