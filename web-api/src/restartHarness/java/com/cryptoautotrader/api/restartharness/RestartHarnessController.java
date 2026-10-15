package com.cryptoautotrader.api.restartharness;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.service.MarkPriceBook;
import com.cryptoautotrader.api.service.MarketDataUpsertService;
import com.cryptoautotrader.api.service.OrderExecutionEngine;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실제 프로세스 재시작 시험의 진입점 (2026-10-13) — <b>운영 jar 에 없다</b>({@code restartHarness} 소스 세트).
 *
 * <p>평가 루프의 매수와 같은 경계를 지난다: 한 트랜잭션에서 포지션(슬롯)·예약·의도를 쓰고 커밋한 뒤, 커밋 후
 * 실행기가 claim·제출한다({@code reserveAndPrepareIntent} → {@code submitPreparedOrderAfterCommit}). 신호
 * 판단만 건너뛴다.</p>
 */
@RestController
@Profile("restart-harness")
@RequestMapping("/api/v1/restart-harness")
public class RestartHarnessController {

    private final OrderExecutionEngine engine;
    private final PositionRepository positionRepository;
    private final TransactionTemplate tx;
    private final MarkPriceBook markPriceBook;
    private final MarketDataUpsertService upsertService;
    private final MarketDataCacheRepository cacheRepository;
    private final JdbcTemplate jdbc;

    public RestartHarnessController(OrderExecutionEngine engine, PositionRepository positionRepository,
                                    PlatformTransactionManager txManager, MarkPriceBook markPriceBook,
                                    MarketDataUpsertService upsertService, MarketDataCacheRepository cacheRepository,
                                    JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.upsertService = upsertService;
        this.cacheRepository = cacheRepository;
        this.engine = engine;
        this.positionRepository = positionRepository;
        this.tx = new TransactionTemplate(txManager);
        this.markPriceBook = markPriceBook;
        StrategyRegistry.register(new HarnessSignal());
    }

    /** 시험용 전략 이름 — 엔진 평가가 이 전략을 부르면 {@link #signal} 을 그대로 낸다(2026-10-15, 5-h 엔진 자체 청산 S16). */
    public static final String STRATEGY = "HARNESS_SIGNAL";
    private static volatile String signal = "HOLD";

    static final class HarnessSignal implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            String s = signal;
            if ("SELL".equals(s)) return StrategySignal.sell(new BigDecimal("100"), "재시작 시험 신호 SELL");
            if ("BUY".equals(s)) return StrategySignal.buy(new BigDecimal("100"), "재시작 시험 신호 BUY");
            return StrategySignal.hold("재시작 시험 신호 HOLD");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    /**
     * 같은 새 캔들을 여러 스레드가 동시에 쓴다 — 선행 조건 5-g (2026-10-15). {@code mode=legacy} 는 종전 저장(JPA 병합 = 조회 후 삽입,
     * 각자 트랜잭션), {@code mode=upsert} 는 지금의 {@link MarketDataUpsertService#upsert}. 회차마다 새 시각 3개를 모든 스레드가 장벽에서
     * 함께 출발해 쓴다. 실패한 쓰기의 예외 종류를 센다.
     */
    @PostMapping("/candle-race")
    public Map<String, Object> candleRace(@RequestParam String mode, @RequestParam String coin,
                                          @RequestParam(defaultValue = "8") int threads,
                                          @RequestParam(defaultValue = "30") int rounds) throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(10_000, ChronoUnit.HOURS);
        AtomicInteger ok = new AtomicInteger();
        Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int r = 0; r < rounds; r++) {
                Instant t = base.plus(3L * r, ChronoUnit.HOURS);
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<?>> fs = new ArrayList<>();
                for (int k = 0; k < threads; k++) {
                    BigDecimal close = new BigDecimal(100 + k);
                    fs.add(pool.submit(() -> {
                        List<MarketDataCacheEntity> rows = new ArrayList<>();
                        for (int i = 0; i < 3; i++) {
                            rows.add(MarketDataCacheEntity.builder().time(t.plus(i, ChronoUnit.HOURS)).coinPair(coin).timeframe("H1")
                                    .open(close).high(close).low(close).close(close).volume(BigDecimal.ONE).build());
                        }
                        try {
                            start.await();
                            if ("legacy".equals(mode)) {
                                tx.executeWithoutResult(st -> cacheRepository.saveAll(rows));
                            } else {
                                upsertService.upsert(coin, "H1", rows);
                            }
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            Throwable c = e;
                            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
                            errors.computeIfAbsent(e.getClass().getSimpleName() + " ← " + c.getClass().getSimpleName()
                                    + ": " + String.valueOf(c.getMessage()).split("\n")[0], x -> new AtomicInteger()).incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> errs = new TreeMap<>();
        errors.forEach((k, v) -> errs.put(k, v.get()));
        long rowsNow = cacheRepository.findCandles(coin, "H1", base.minus(1, ChronoUnit.HOURS),
                base.plus(3L * rounds + 3, ChronoUnit.HOURS)).size();
        return Map.of("mode", mode, "writes", threads * rounds, "ok", ok.get(),
                "failed", errs.values().stream().mapToInt(Integer::intValue).sum(), "errors", errs, "rows", rowsNow);
    }

    /**
     * 같은 포지션에 청산 매도 요청 {@code n} 개를 <b>동시에</b> 낸다 (2026-10-15 리뷰 7회차, S19). 장벽에서 함께 출발한 스레드가 엔진 본체의
     * {@code submitOrder} 를 같은 스레드에서 끝까지 부른다(비동기 실행기를 거치지 않아 경합이 가장 크다). 운영의 청산 경로들(정지·손절·
     * 정리)은 모두 이 메서드로 들어온다.
     */
    @PostMapping("/concurrent-sell")
    public Map<String, Object> concurrentSell(@RequestParam String kind, @RequestParam Long sessionId, @RequestParam Long positionId,
                                              @RequestParam(defaultValue = "KRW-DOGE") String coin,
                                              @RequestParam BigDecimal qty, @RequestParam(defaultValue = "8") int n) throws Exception {
        Object target = engine;
        while (org.springframework.aop.support.AopUtils.isAopProxy(target)) {
            Object next = org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(target);
            if (next == null) break;
            target = next;
        }
        OrderExecutionEngine direct = (OrderExecutionEngine) target;
        // 실제 청산 경로처럼 먼저 원자적으로 CLOSING 표시 — 체결 반영(reconcileClosingPositions)은 CLOSING 만 본다.
        //    이 표시를 통과한 뒤 여러 경로가 엔진까지 동시에 오는 경우를 재현해 엔진의 제출 잠금만 시험한다.
        Integer marked = tx.execute(st -> positionRepository.markClosingIfOpen(positionId, Instant.now()));
        if (marked == null || marked != 1) throw new IllegalStateException("CLOSING 표시 실패 (posId=" + positionId + ")");
        CyclicBarrier start = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        Map<String, AtomicInteger> outcome = new ConcurrentHashMap<>();
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    OrderRequest r = new OrderRequest();
                    r.setCoinPair(coin);
                    r.setSide("SELL");
                    r.setOrderType("MARKET");
                    r.setQuantity(qty);
                    r.setReason("재시작 시험 동시 청산");
                    r.setSessionId(sessionId);
                    r.setSessionKind(kind);
                    r.setPositionId(positionId);
                    try {
                        start.await();
                        direct.submitOrder(r);
                        outcome.computeIfAbsent("returned", x -> new AtomicInteger()).incrementAndGet();
                    } catch (Exception e) {
                        outcome.computeIfAbsent(e.getClass().getSimpleName(), x -> new AtomicInteger()).incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> out = new TreeMap<>();
        outcome.forEach((k, v) -> out.put(k, v.get()));
        return Map.of("requests", n, "outcome", out);
    }

    @PostMapping("/signal")
    public Map<String, Object> signal(@RequestParam String action) {
        signal = action;
        return Map.of("signal", action);
    }

    /**
     * 실시간 시세 한 건 — 대역에는 시세 구독이 없으므로 시험이 넣는다(2026-10-15, 포지션별 한도 S15). {@code ageSeconds} 를 주면 그만큼
     * 지난 시각의 시세로 적는다(낡은 시세 시험, 리뷰 8회차 S14).
     */
    @PostMapping("/mark")
    public Map<String, Object> mark(@RequestParam String coin, @RequestParam BigDecimal price,
                                    @RequestParam(defaultValue = "0") long ageSeconds) {
        markPriceBook.record(coin, price, Instant.now().minusSeconds(ageSeconds));
        return Map.of("coin", coin, "price", price, "ageSeconds", ageSeconds);
    }

    /** 매수 순간에만 넣은 시세 — 코인별 참조 횟수와 넣기 전 기록(없었으면 null). 동시 매수가 서로의 시세를 지우지 않게 센다. */
    private final Map<String, Object[]> fedMarks = new java.util.HashMap<>();

    /**
     * 🔴 대역에는 시세 구독이 없다. 공유 풀 손실 한도는 유효한 실시간 시세 없이 대체 가격으로만 평가되는 보유가 있으면 진입을 막는다
     * (리뷰 8회차). 실제 운영의 "시세 경로가 살아 있는" 상태를 <b>이 매수의 판정 순간에만</b> 흉내 낸다 — 유효한 시세가 없는 보유
     * 코인에 대역 체결가(signalPrice)를 지금 시각으로 넣고, 매수가 끝나면 넣기 전 상태로 되돌린다. 시세가 이미 유효한 코인은
     * 건드리지 않는다(S15 처럼 시험이 직접 넣은 시세를 덮지 않는다). 시세 없는 판정을 시험하는 시나리오(S16b·S14 대체 시세)는
     * {@code feedMarks=false} 로 이 흉내를 끈다.
     */
    private List<String> feedMarks(BigDecimal price) {
        List<String> coins = jdbc.queryForList("SELECT DISTINCT coin_pair FROM position WHERE session_kind IN ('LIVE', 'DYNAMIC') "
                + "AND status IN ('OPEN', 'CLOSING') AND size > 0", String.class);
        List<String> fed = new ArrayList<>();
        synchronized (fedMarks) {
            Instant now = Instant.now();
            for (String c : coins) {
                Object[] e = fedMarks.get(c);
                if (e != null) {
                    e[0] = (Integer) e[0] + 1;
                    markPriceBook.record(c, price, now);
                    fed.add(c);
                } else if (markPriceBook.freshPrice(c, now) == null) {
                    fedMarks.put(c, new Object[]{1, markPriceBook.get(c)});
                    markPriceBook.record(c, price, now);
                    fed.add(c);
                }
            }
        }
        return fed;
    }

    private void unfeedMarks(List<String> fed) {
        synchronized (fedMarks) {
            for (String c : fed) {
                Object[] e = fedMarks.get(c);
                if (e == null) continue;
                int n = (Integer) e[0] - 1;
                if (n > 0) {
                    e[0] = n;
                    continue;
                }
                fedMarks.remove(c);
                MarkPriceBook.Mark prev = (MarkPriceBook.Mark) e[1];
                if (prev == null) markPriceBook.remove(c);
                else markPriceBook.record(c, prev.price(), prev.at());
            }
        }
    }

    @PostMapping("/buy")
    public Map<String, Object> buy(@RequestParam String kind, @RequestParam Long sessionId,
                                   @RequestParam BigDecimal krw, @RequestParam(defaultValue = "KRW-DOGE") String coin,
                                   @RequestParam(defaultValue = "300") BigDecimal signalPrice,
                                   @RequestParam(defaultValue = "true") boolean feedMarks) {
        List<String> fed = feedMarks ? feedMarks(signalPrice) : List.of();
        try {
            return buyInTx(kind, sessionId, krw, coin, signalPrice);
        } finally {
            unfeedMarks(fed);
        }
    }

    private Map<String, Object> buyInTx(String kind, Long sessionId, BigDecimal krw, String coin, BigDecimal signalPrice) {
        return tx.execute(st -> {
            PositionEntity pos = positionRepository.save(PositionEntity.builder()
                    .coinPair(coin).side("BUY")
                    .entryPrice(signalPrice).avgPrice(signalPrice)
                    .size(BigDecimal.ZERO).investedKrw(krw)
                    .entryAllocationKrw(krw)   // 엔진과 같이 진입 배정액을 남긴다 (V90)
                    .status("OPEN").sessionKind(kind).sessionId(sessionId)
                    .openedAt(Instant.now()).build());
            OrderRequest req = new OrderRequest();
            req.setCoinPair(coin);
            req.setSide("BUY");
            req.setOrderType("MARKET");
            req.setQuantity(krw);
            req.setReason("재시작 시험 매수");
            req.setSignalPrice(signalPrice);
            req.setSessionId(sessionId);
            req.setSessionKind(kind);
            req.setPositionId(pos.getId());
            Long orderId = engine.reserveAndPrepareIntent(req);
            engine.submitPreparedOrderAfterCommit(orderId, req);
            return Map.<String, Object>of("orderId", orderId, "positionId", pos.getId());
        });
    }
}
