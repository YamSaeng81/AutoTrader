package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 PAPER 평가 루프의 간섭 확인 (2026-11-10, 총괄 v1.144 F) — 운영 메타데이터(17:39:32 KST 읽기 전용)와 같은 RUNNING 구성:
 * 사전 등록 63(21코인 × 3팔, H1, 1,000,000) + 그 밖 13(M15: ADA·AVAX·BTC·LINK·SOL 1, ETH·XRP 4) 에
 * 새 SMA20 1(H1 KRW-BTC, 20,200·0.995·상한 20,000·LIVE_ALIGNED) + A/B 8(M15 BTC·ETH) 을 더한다.
 *
 * <p>같은 캔들·같은 시각으로 {@link PaperTradingService#runStrategy}(운영 스케줄러 메서드) 한 틱을 두 번 돌린다 —
 * ① 기존 76만 ② 76 + 새 9. 기존 76의 결정(마지막 신호·사유·포지션·진입가·가용 잔액)이 두 실행에서 같아야 하고, 기존 세션의
 * 평가 순서가 새 세션보다 앞이어야 한다(시작 시각 순). REAL 표(LIVE 세션·포지션·주문)는 바뀌지 않아야 한다.</p>
 *
 * <p>한계: 사전 등록 세션의 전략 파라미터·비율 등 세부 설정은 운영에서 읽지 않았다(성과 비열람 계약 — 메타데이터만). 전략 이름·코인·
 * 주기·자본만 같고 나머지는 기본값이다. 그 밖 13세션의 전략은 메타데이터에 없어 M15 대역 전략(A/B 와 같은 두 전략)을 쓴다.
 * 캔들은 합성이다. H2·시험 컨텍스트의 시간은 운영 부하를 대표하지 않는다 — 상대 비교만 한다.</p>
 */
@DisplayName("공유 PAPER 루프 — 기존 76 + 새 9: 기존 결정 불변·순서·REAL 표 불변·틱 시간")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=SharedPaperLoopIsolationTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SharedPaperLoopIsolationTest extends IntegrationTestBase {

    static final List<String> PREREG_COINS = List.of("KRW-ANKR", "KRW-BCH", "KRW-CRO", "KRW-CVC", "KRW-DKA", "KRW-ELF", "KRW-G",
            "KRW-GLM", "KRW-HIVE", "KRW-INJ", "KRW-IOTA", "KRW-JUP", "KRW-NEAR", "KRW-ONG", "KRW-POLYX", "KRW-POWR",
            "KRW-PUNDIX", "KRW-SC", "KRW-T", "KRW-WAVES", "KRW-WAXP");
    static final List<String> PREREG_ARMS = List.of("COMPOSITE_MOMENTUM_ICHIMOKU_V2", "COMPOSITE_MTF_MOMENTUM", "COMPOSITE_MTF_MOMENTUM_CLOSED");
    static final Map<String, Integer> OTHER_M15 = new LinkedHashMap<>();
    static {
        OTHER_M15.put("KRW-ADA", 1); OTHER_M15.put("KRW-AVAX", 1); OTHER_M15.put("KRW-BTC", 1); OTHER_M15.put("KRW-ETH", 4);
        OTHER_M15.put("KRW-LINK", 1); OTHER_M15.put("KRW-SOL", 1); OTHER_M15.put("KRW-XRP", 4);
    }
    static final List<String> M15_STAND_IN = List.of("COMPOSITE_MEANREV_BB", "COMPOSITE_MTF_CONFIRMED");

    @Autowired private PaperTradingService paperTradingService;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository positionRepo;
    @Autowired private PaperOrderRepository orderRepo;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private WsSubscriptionManager wsSubscriptionManager;
    @Autowired private EntryPriorityRounds entryPriorityRounds;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private UpbitWebSocketClient upbitWebSocketClient;

    private Instant h1Last;
    private Instant m15Last;

    record Spec(String key, String strategy, String coin, String tf, BigDecimal capital, Map<String, Object> params,
                BigDecimal ratio, BigDecimal sl, Integer maxHold, boolean isNew) {}

    private List<Spec> existing() {
        List<Spec> out = new ArrayList<>();
        int skip = 0;
        for (String coin : PREREG_COINS) {
            for (String arm : PREREG_ARMS) {
                out.add(new Spec("PRE|" + arm + "|" + coin, arm, coin, "H1", new BigDecimal("1000000"), null, null, null, 0, false));
            }
        }
        for (var e : OTHER_M15.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                String strat = M15_STAND_IN.get(i % 2);
                out.add(new Spec("OTH|" + strat + "|" + e.getKey() + "|" + i, strat, e.getKey(), "M15", new BigDecimal("1000000"),
                        null, null, null, 24, false));
            }
        }
        return out;
    }

    private List<Spec> fresh() {
        List<Spec> out = new ArrayList<>();
        out.add(new Spec("NEW|SMA20", DailySmaTrendStrategy.NAME, "KRW-BTC", "H1", new BigDecimal("20200"),
                Map.of(PaperMaxInvestment.KEY, 20000, PaperExecutionMode.KEY, PaperExecutionMode.LIVE_ALIGNED),
                new BigDecimal("0.995"), new BigDecimal("5.00"), 0, true));
        for (String strat : M15_STAND_IN) {
            for (String coin : List.of("KRW-BTC", "KRW-ETH")) {
                for (String arm : List.of("A", "B")) {
                    out.add(new Spec("NEW|AB|" + strat + "|" + coin + "|" + arm, strat, coin, "M15", new BigDecimal("100000"),
                            Map.of(PaperMaxInvestment.KEY, 20000), new BigDecimal("0.8"), "B".equals(arm) ? new BigDecimal("5") : null, 24, true));
                }
            }
        }
        return out;
    }

    /** 코인마다 위상이 다른 물결 + 추세 — 일부 전략이 신호를 내도록. 같은 코인·주기는 두 실행에서 같다. */
    private void insertCandles(String coin, String tf, Instant last, long stepMin) {
        int seed = Math.abs(coin.hashCode() % 97);
        long base = 1_000_000L + seed * 10_000L;
        List<com.cryptoautotrader.api.entity.MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double wave = Math.sin((i + seed) / 9.0) * 0.03 + Math.sin((i + seed * 3) / 37.0) * 0.05 + i * 0.0004;
            double prevWave = Math.sin((i - 1 + seed) / 9.0) * 0.03 + Math.sin((i - 1 + seed * 3) / 37.0) * 0.05 + (i - 1) * 0.0004;
            BigDecimal c = BigDecimal.valueOf(Math.round(base * (1 + wave)));
            BigDecimal o = BigDecimal.valueOf(Math.round(base * (1 + prevWave)));
            BigDecimal h = c.max(o).multiply(new BigDecimal("1.004")).setScale(0, java.math.RoundingMode.HALF_UP);
            BigDecimal l = c.min(o).multiply(new BigDecimal("0.996")).setScale(0, java.math.RoundingMode.HALF_UP);
            rows.add(com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                    .time(last.minus((499 - i) * stepMin, ChronoUnit.MINUTES)).coinPair(coin).timeframe(tf)
                    .open(o).high(h).low(l).close(c).volume(new BigDecimal("1000")).build());
        }
        cacheRepository.saveAll(rows);
    }

    private Map<String, Long> createAll(List<Spec> specs) {
        Map<String, Long> ids = new LinkedHashMap<>();
        Instant base = Instant.now().minus(30, ChronoUnit.DAYS);
        int n = 0;
        for (Spec s : specs) {
            Instant started = s.isNew() ? Instant.now().minus(5, ChronoUnit.MINUTES).plusMillis(n) : base.plusMillis(n);
            n++;
            VirtualBalanceEntity v = balanceRepo.save(VirtualBalanceEntity.builder()
                    .strategyName(s.strategy()).coinPair(s.coin()).timeframe(s.tf())
                    .initialCapital(s.capital()).availableKrw(s.capital()).totalKrw(s.capital())
                    .investRatio(s.ratio()).stopLossPct(s.sl()).maxHoldHours(s.maxHold())
                    .strategyParams(s.params() == null ? null : new java.util.HashMap<>(s.params()))
                    .status("RUNNING").startedAt(started).build());
            ids.put(s.key(), v.getId());
        }
        return ids;
    }

    private Map<String, String> snapshot(Map<String, Long> ids, List<Spec> which) {
        Map<String, String> out = new TreeMap<>();
        for (Spec s : which) {
            Long id = ids.get(s.key());
            List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id);
            List<PaperPositionEntity> pos = positionRepo.findBySessionId(id);
            VirtualBalanceEntity v = balanceRepo.findById(id).orElseThrow();
            out.put(s.key(), String.format("logs=%d last=%s pos=%d entry=%s status=%s exit=%s realized=%s avail=%s total=%s", logs.size(),
                    logs.isEmpty() ? "-" : logs.get(0).getSignal() + ":" + logs.get(0).getReason(),
                    pos.size(), pos.isEmpty() ? "-" : pos.get(0).getEntryPrice().stripTrailingZeros().toPlainString(),
                    pos.isEmpty() ? "-" : pos.get(0).getStatus(), pos.isEmpty() ? "-" : pos.get(0).getExitReason(),
                    pos.isEmpty() || pos.get(0).getRealizedPnl() == null ? "-" : pos.get(0).getRealizedPnl().stripTrailingZeros().toPlainString(),
                    v.getAvailableKrw().stripTrailingZeros().toPlainString(),
                    v.getTotalKrw() == null ? "-" : v.getTotalKrw().stripTrailingZeros().toPlainString()));
        }
        return out;
    }

    private Map<String, Long> realTables() {
        Map<String, Long> m = new TreeMap<>();
        m.put("live_trading_session", jdbc.queryForObject("SELECT count(*) FROM live_trading_session", Long.class));
        m.put("position(LIVE/DYNAMIC)", jdbc.queryForObject("SELECT count(*) FROM position", Long.class));
        m.put("order(REAL)", jdbc.queryForObject("SELECT count(*) FROM \"order\"", Long.class));
        return m;
    }

    private void cleanup(Map<String, Long> ids) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Long id : ids.values()) {
            tx.executeWithoutResult(st -> orderRepo.deleteBySessionId(id));
            positionRepo.findBySessionId(id).forEach(positionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.deleteById(id);
        }
    }

    /** 한 틱 — 운영 메서드 그대로. 평가 순서는 신호 로그 생성 순으로 본다. */
    private long tick() {
        long t0 = System.nanoTime();
        paperTradingService.runStrategy();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    @DisplayName("기존 76의 결정은 새 9가 있어도 같다 · 새 세션은 기존 뒤에 평가된다 · REAL 표 불변")
    void existingDecisionsUnchangedByNewSessions() throws IOException {
        // 다른 시험이 남긴 RUNNING PAPER 가 없어야 비교가 성립한다.
        assertThat(balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")).as("시작 전 RUNNING PAPER").isEmpty();
        h1Last = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        m15Last = Instant.now().minus(16, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        StrategyRegistry.register(new DailySmaTrendStrategy(h1Last.atZone(ZoneOffset.UTC).getHour()));
        List<String> coinsH1 = new ArrayList<>(PREREG_COINS);
        coinsH1.add("KRW-BTC");
        try {
            for (String c : coinsH1) insertCandles(c, "H1", h1Last, 60);
            for (String c : OTHER_M15.keySet()) insertCandles(c, "M15", m15Last, 15);

            Map<String, Long> realBefore = realTables();

            // ① 기존 76만
            Map<String, Long> ids1 = createAll(existing());
            long ms1 = tick();
            Map<String, String> snap1 = snapshot(ids1, existing());
            cleanup(ids1);

            // ② 기존 76 + 새 9 (같은 캔들·같은 닫힌 봉)
            List<Spec> all = new ArrayList<>(existing());
            all.addAll(fresh());
            Map<String, Long> ids2 = createAll(all);
            long ms2 = tick();
            Map<String, String> snap2 = snapshot(ids2, existing());
            Map<String, String> snapNew = snapshot(ids2, fresh());

            // 평가 순서 — 신호 로그 id 순. 새 세션의 첫 로그가 기존 세션의 마지막 로그보다 뒤인가.
            List<Long> existingIds = existing().stream().map(s -> ids2.get(s.key())).toList();
            List<Long> newIds = fresh().stream().map(s -> ids2.get(s.key())).toList();
            Long maxExistingLog = jdbc.queryForObject("SELECT max(id) FROM strategy_log WHERE session_type='PAPER' AND session_id IN ("
                    + String.join(",", existingIds.stream().map(String::valueOf).toList()) + ")", Long.class);
            Long minNewLog = jdbc.queryForObject("SELECT min(id) FROM strategy_log WHERE session_type='PAPER' AND session_id IN ("
                    + String.join(",", newIds.stream().map(String::valueOf).toList()) + ")", Long.class);
            EntryPriorityRounds roundsTarget = AopTestUtils.getUltimateTargetObject(entryPriorityRounds);
            Object rounds = ReflectionTestUtils.invokeMethod(roundsTarget, "openRounds");
            List<String> subscribed = wsSubscriptionManager.getSubscribedCoins();
            Map<String, Long> realAfter = realTables();
            cleanup(ids2);

            long changed = snap1.keySet().stream().filter(k -> !snap1.get(k).equals(snap2.get(k))).count();
            long withSignal = snap1.values().stream().filter(v -> !v.contains("last=-")).count();
            long withPos = snap1.values().stream().filter(v -> !v.contains("pos=0")).count();
            StringBuilder sb = new StringBuilder();
            sb.append("tick_ms_existing_only=").append(ms1).append('\n');
            sb.append("tick_ms_existing_plus_new=").append(ms2).append('\n');
            sb.append("existing_sessions=").append(snap1.size()).append(" changed=").append(changed)
                    .append(" with_signal_log=").append(withSignal).append(" with_position=").append(withPos).append('\n');
            sb.append("eval_order max_existing_log_id=").append(maxExistingLog).append(" min_new_log_id=").append(minNewLog).append('\n');
            sb.append("entry_rounds_open_after=").append(rounds).append('\n');
            sb.append("ws_subscribed_after=").append(subscribed).append('\n');
            sb.append("real_tables_before=").append(realBefore).append(" after=").append(realAfter).append('\n');
            sb.append("--- existing (run1 | run2)\n");
            for (String k : snap1.keySet()) sb.append(k).append('\t').append(snap1.get(k)).append('\t').append(snap2.get(k)).append('\n');
            sb.append("--- new\n");
            snapNew.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
            Path out = Path.of("build", "shared-loop", "report.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[공유 루프]\n" + sb);

            assertThat(changed).as("기존 세션 결정 변화 수").isZero();
            assertThat(minNewLog == null || maxExistingLog == null || minNewLog > maxExistingLog).as("새 세션이 기존 뒤에 평가").isTrue();
            assertThat(realAfter).as("REAL 표 불변").isEqualTo(realBefore);
        } finally {
            StrategyRegistry.register(new DailySmaTrendStrategy());
            jdbc.update("DELETE FROM market_data_cache WHERE timeframe IN ('H1','M15') AND coin_pair IN ("
                    + String.join(",", java.util.stream.Stream.concat(coinsH1.stream(), OTHER_M15.keySet().stream())
                    .distinct().map(c -> "'" + c + "'").toList()) + ")");
        }
    }

    /**
     * 기존 세션 셋 중 하나에 손절가가 현재 종가 위인 열린 포지션(이 틱에 손절), 셋 중 하나에 먼 손절·익절의 열린 포지션(유지·평가손익 갱신)을
     * 넣는다 — 포지션·청산·잔액 경로를 실제로 타게 한다. 두 실행에 같은 값을 넣는다.
     */
    private int seedPositions(Map<String, Long> ids, List<Spec> which) {
        int n = 0, i = 0;
        for (Spec s : which) {
            int k = i++ % 3;
            if (k == 2) continue;
            BigDecimal close = jdbc.queryForObject("SELECT close FROM market_data_cache WHERE coin_pair = ? AND timeframe = ? ORDER BY time DESC LIMIT 1",
                    BigDecimal.class, s.coin(), s.tf());
            BigDecimal invest = new BigDecimal("100000");
            BigDecimal size = invest.divide(close, 8, java.math.RoundingMode.DOWN);
            BigDecimal sl = close.multiply(k == 0 ? new BigDecimal("1.01") : new BigDecimal("0.80"));
            BigDecimal tp = close.multiply(new BigDecimal("1.20"));
            Long id = ids.get(s.key());
            positionRepo.save(PaperPositionEntity.builder().sessionKind("PAPER").sessionId(id).coinPair(s.coin()).side("BUY")
                    .entryPrice(close).avgPrice(close).size(size).positionFee(BigDecimal.ZERO).investedKrw(invest)
                    .stopLossPrice(sl).takeProfitPrice(tp).status("OPEN").openedAt(Instant.now().minus(5, ChronoUnit.HOURS)).build());
            VirtualBalanceEntity v = balanceRepo.findById(id).orElseThrow();
            v.setAvailableKrw(v.getAvailableKrw().subtract(invest));
            balanceRepo.save(v);
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("포지션 경로 — 기존 세션 2/3 에 열린 포지션(1/3 은 이 틱에 손절)을 넣어도 기존 76 의 청산·잔액·평가손익은 새 9 와 무관하다")
    void existingPositionPathsUnchangedByNewSessions() throws IOException {
        assertThat(balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")).as("시작 전 RUNNING PAPER").isEmpty();
        h1Last = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        m15Last = Instant.now().minus(16, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        StrategyRegistry.register(new DailySmaTrendStrategy(h1Last.atZone(ZoneOffset.UTC).getHour()));
        List<String> coinsH1 = new ArrayList<>(PREREG_COINS);
        coinsH1.add("KRW-BTC");
        try {
            for (String c : coinsH1) insertCandles(c, "H1", h1Last, 60);
            for (String c : OTHER_M15.keySet()) insertCandles(c, "M15", m15Last, 15);
            Map<String, Long> realBefore = realTables();
            Map<String, Long> ids1 = createAll(existing());
            int seeded1 = seedPositions(ids1, existing());
            tick();
            Map<String, String> snap1 = snapshot(ids1, existing());
            cleanup(ids1);
            List<Spec> all = new ArrayList<>(existing());
            all.addAll(fresh());
            Map<String, Long> ids2 = createAll(all);
            int seeded2 = seedPositions(ids2, existing());
            tick();
            Map<String, String> snap2 = snapshot(ids2, existing());
            Map<String, Long> realAfter = realTables();
            cleanup(ids2);
            long changed = snap1.keySet().stream().filter(k -> !snap1.get(k).equals(snap2.get(k))).count();
            long slExits = snap1.values().stream().filter(v -> v.contains("exit=STOP_LOSS")).count();
            long open = snap1.values().stream().filter(v -> v.contains("status=OPEN")).count();
            StringBuilder sb = new StringBuilder();
            sb.append("seeded=").append(seeded1).append('/').append(seeded2).append(" changed=").append(changed)
                    .append(" stop_loss_exits=").append(slExits).append(" still_open=").append(open).append('\n');
            sb.append("real_tables_before=").append(realBefore).append(" after=").append(realAfter).append('\n');
            for (String k : snap1.keySet()) sb.append(k).append('\t').append(snap1.get(k)).append('\t').append(snap2.get(k)).append('\n');
            Path out = Path.of("build", "shared-loop", "report-positions.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[공유 루프·포지션]\n" + sb);
            assertThat(slExits).as("손절 경로가 실제로 돌았다").isGreaterThan(0);
            assertThat(open).as("유지 경로가 실제로 돌았다").isGreaterThan(0);
            assertThat(changed).as("기존 세션 결정·잔액 변화 수").isZero();
            assertThat(realAfter).as("REAL 표 불변").isEqualTo(realBefore);
        } finally {
            StrategyRegistry.register(new DailySmaTrendStrategy());
            jdbc.update("DELETE FROM market_data_cache WHERE timeframe IN ('H1','M15') AND coin_pair IN ("
                    + String.join(",", java.util.stream.Stream.concat(coinsH1.stream(), OTHER_M15.keySet().stream())
                    .distinct().map(c -> "'" + c + "'").toList()) + ")");
        }
    }
}
