package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * <b>공식 점검 공백 예외</b> (2026-10-19, V93) — 캔들 최신성 관문이 결측에서 빼는 것은 <b>공식 점검 구간과 겹치고 + 거래소 조회에서도
 * 없던 칸</b>뿐이다.
 *
 * <p>구분하는 것: ① 점검 공백(두 조건 충족) → 판정 가능 ② 점검 구간은 있으나 아직 거래소 조회 전 → 판정 불가 ③ 거래소에 없지만 점검 밖
 * (무체결·미확인) → 판정 불가 ④ 진행 중인 점검(끝 시각이 미래) → 판정 불가 ⑤ 점검 예외가 있어도 <b>현재 적재 정지</b>(최신 완료 봉 없음)
 * → 판정 불가 ⑥ 점검 공백 + 점검 밖 결측 3칸 → 판정 불가 ⑦ BTC 급락 관문도 같은 규칙 ⑧ 🔴 점검 끝에 걸친 경계 봉은 빼지 않는다(재개 뒤 시간을
 * 포함한다 — 리뷰 2026-10-19) ⑨ 봉 경계에 맞춰 끝난 점검은 마지막 봉까지 뺀다.</p>
 *
 * <p>기본 점검 구간({@link #maintenanceWindowFor})은 운영 2026-10-19 처럼 <b>봉 중간에 끝난다</b>(06:55 처럼) — 마지막 점검 칸은 경계 봉이라
 * 일반 결측 1칸으로 남는다.</p>
 */
@DisplayName("공식 점검 공백 — 캔들 최신성 관문 예외")
class CandleMaintenanceGapTest extends IntegrationTestBase {

    private static final String TF = "H1";
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC);

    @Autowired private CandleFreshnessGuard guard;
    @Autowired private CandleGapBackfillService backfill;
    @Autowired private ExchangeMaintenanceWindows windows;
    @Autowired private MarketDataUpsertService upsertService;
    @Autowired private MarketDataCacheRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitRestClient upbitRestClient;

    private final Set<String> coins = new HashSet<>();

    @AfterEach
    void tearDown() {
        coins.forEach(c -> jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ?", c));
        jdbc.update("DELETE FROM exchange_maintenance_window WHERE source LIKE '시험%'");
        windows.reload(Instant.now());
        ((Map<?, ?>) ReflectionTestUtils.getField(backfill, "absentChecks")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(backfill, "lastFailure")).clear();
    }

    /** 최근 n 칸(lastClosed 까지) 중 hole 을 뺀 캐시. */
    private void seed(String coin, String tf, Instant lastClosed, int n, Set<Integer> hole) {
        coins.add(coin);
        long tfMin = tf.equals("M30") ? 30 : 60;
        List<MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            if (hole.contains(i)) continue;
            BigDecimal c = new BigDecimal("100").add(BigDecimal.valueOf(i % 3));
            rows.add(MarketDataCacheEntity.builder().time(lastClosed.minus(i * tfMin, ChronoUnit.MINUTES)).coinPair(coin).timeframe(tf)
                    .open(c).high(c).low(c).close(c).volume(BigDecimal.ONE).build());
        }
        upsertService.upsert(coin, tf, rows);
    }

    /** 거래소 대역 — 캐시에 있는 칸만 거래소에도 있다(구멍은 거래소에도 없다). */
    private void exchangeMirrorsCache(String coin, String tf) throws Exception {
        TreeSet<Instant> have = new TreeSet<>();
        repo.findCandles(coin, tf, Instant.EPOCH, Instant.now().plus(1, ChronoUnit.DAYS)).forEach(e -> have.add(e.getTime()));
        when(upbitRestClient.getCandles(org.mockito.ArgumentMatchers.eq(coin), anyString(), anyInt(), any(), anyInt())).thenAnswer(inv -> {
            Instant to = inv.getArgument(3);
            int count = inv.getArgument(4);
            List<UpbitCandleResponse> out = new ArrayList<>();
            for (Instant t : have.headSet(to, false).descendingSet()) {
                if (out.size() >= count) break;
                UpbitCandleResponse r = new UpbitCandleResponse();
                r.setMarket(coin);
                r.setCandleDateTimeUtc(UTC.format(t));
                r.setOpeningPrice(BigDecimal.TEN);
                r.setHighPrice(BigDecimal.TEN);
                r.setLowPrice(BigDecimal.TEN);
                r.setTradePrice(BigDecimal.TEN);
                r.setCandleAccTradeVolume(BigDecimal.ONE);
                out.add(r);
            }
            return out;
        });
    }

    private void window(Instant start, Instant end) {
        jdbc.update("INSERT INTO exchange_maintenance_window (exchange, scope, starts_at, ends_at, source, source_timezone, confirmed_at, confirmed_by) "
                        + "VALUES ('UPBIT', 'ALL_MARKETS', ?, ?, '시험 — 공지', 'Asia/Seoul', ?, '시험')",
                Timestamp.from(start), Timestamp.from(end), Timestamp.from(Instant.now()));
        windows.reload(Instant.now());
    }

    private List<Candle> cache(String coin, String tf) {
        return repo.findCandles(coin, tf, Instant.now().minus(60, ChronoUnit.DAYS), Instant.now()).stream()
                .map(e -> Candle.builder().time(e.getTime()).open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose()).volume(e.getVolume()).build())
                .toList();
    }

    /** 관문과 같은 계산(지금 − 2분 기준 직전에 닫힌 봉의 시작). */
    private static Instant lastClosed(long tfSec) {
        long ref = Instant.now().minus(CandleFreshnessGuard.GRACE).getEpochSecond();
        return Instant.ofEpochSecond(ref - Math.floorMod(ref, tfSec) - tfSec);
    }

    private static Instant lastClosedH1() {
        return lastClosed(3600);
    }

    /** 점검: lastClosed 기준 20~24시간 전 칸(5칸)이 비었다. 점검 구간은 24시간 전부터 19시간 5분 전까지 — 20시간 전 칸은 경계 봉이다. */
    private static final Set<Integer> MAINT = Set.of(20, 21, 22, 23, 24);

    private void maintenanceWindowFor(Instant lastClosed) {
        window(lastClosed.minus(24, ChronoUnit.HOURS), lastClosed.minus(19, ChronoUnit.HOURS).minus(5, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("① 점검 구간 + 거래소에도 없음 → 판정 가능 (결측 5칸을 뺀다)")
    void officialMaintenanceGapIsExempt() throws Exception {
        String coin = "KRW-MTA";
        Instant lc = lastClosedH1();
        seed(coin, TF, lc, 60, MAINT);
        exchangeMirrorsCache(coin, TF);
        maintenanceWindowFor(lc);
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).as("판정 가능").isNotEmpty();
        assertThat(CandleFreshnessGuard.problem(cache(coin, TF), TF, Instant.now())).as("예외 없이는 종전대로 판정 불가").contains("결측 5칸");
    }

    @Test
    @DisplayName("② 점검 구간은 있으나 거래소 조회 전 → 판정 불가")
    void maintenanceWithoutExchangeCheckBlocks() {
        String coin = "KRW-MTB";
        Instant lc = lastClosedH1();
        seed(coin, TF, lc, 60, MAINT);
        maintenanceWindowFor(lc);

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isEmpty();
    }

    @Test
    @DisplayName("③ 거래소에도 없지만 점검 구간이 아님(무체결·미확인) → 판정 불가 — 종전 기준 유지")
    void exchangeAbsentOutsideMaintenanceBlocks() throws Exception {
        String coin = "KRW-MTC";
        Instant lc = lastClosedH1();
        seed(coin, TF, lc, 60, MAINT);
        exchangeMirrorsCache(coin, TF);
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isEmpty();
    }

    @Test
    @DisplayName("④ 진행 중인 점검(끝 시각이 미래) → 쓰지 않는다")
    void ongoingMaintenanceIsNotUsed() throws Exception {
        String coin = "KRW-MTD";
        Instant lc = lastClosedH1();
        seed(coin, TF, lc, 60, MAINT);
        exchangeMirrorsCache(coin, TF);
        window(lc.minus(24, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isEmpty();
    }

    @Test
    @DisplayName("🔴 ⑤ 과거 점검 예외가 현재 적재 정지를 가리지 않는다 — 최신 완료 봉이 없으면 판정 불가")
    void currentHaltIsNotMasked() throws Exception {
        String coin = "KRW-MTE";
        Instant lc = lastClosedH1();
        Instant staleLast = lc.minus(3, ChronoUnit.HOURS);   // 3시간째 적재가 멎었다
        seed(coin, TF, staleLast, 60, Set.of(17, 18, 19, 20, 21));   // 같은 절대 시각의 점검 공백(lc 기준 20~24)
        exchangeMirrorsCache(coin, TF);
        maintenanceWindowFor(lc);
        backfill.backfill(coin, TF, Instant.now());

        List<Candle> got = cache(coin, TF);
        assertThat(guard.filter("DYNAMIC", coin, TF, got)).isEmpty();
        assertThat(guard.staleKeys().get(coin + ":" + TF)).contains("최신 완료 봉 없음");
    }

    @Test
    @DisplayName("⑥ 점검 공백은 빼도 점검 밖 결측 3칸이 남으면 판정 불가")
    void otherMissingStillCounts() throws Exception {
        String coin = "KRW-MTF";
        Instant lc = lastClosedH1();
        Set<Integer> hole = new HashSet<>(MAINT);
        hole.addAll(Set.of(5, 8, 11));
        seed(coin, TF, lc, 60, hole);
        exchangeMirrorsCache(coin, TF);
        maintenanceWindowFor(lc);
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isEmpty();
        assertThat(guard.staleKeys().get(coin + ":" + TF)).as("점검 밖 3칸 + 경계 봉 1칸")
                .contains("결측 4칸").contains("공식 점검 공백 4칸 제외 후");
    }

    @Test
    @DisplayName("🔴 ⑧ 점검 끝에 걸친 경계 봉은 빼지 않는다 — 경계 봉 1 + 점검 밖 2 = 3칸 → 판정 불가 (겹침 기준이면 통과했다)")
    void boundaryCandleIsNotExempt() throws Exception {
        String coin = "KRW-MTG";
        Instant lc = lastClosedH1();
        Set<Integer> hole = new HashSet<>(MAINT);
        hole.addAll(Set.of(5, 8));
        seed(coin, TF, lc, 60, hole);
        exchangeMirrorsCache(coin, TF);
        maintenanceWindowFor(lc);   // 19시간 5분 전에 끝남 — 20시간 전 칸 [lc-20h, lc-19h) 은 재개 뒤 55분을 포함한다
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isEmpty();
        assertThat(guard.staleKeys().get(coin + ":" + TF)).contains("결측 3칸").contains("공식 점검 공백 4칸 제외 후");
    }

    @Test
    @DisplayName("⑨ 봉 경계에 맞춰 끝난 점검 — 마지막 칸까지 뺀다(점검 밖 2칸만 남아 판정 가능)")
    void alignedWindowExemptsLastSlot() throws Exception {
        String coin = "KRW-MTH";
        Instant lc = lastClosedH1();
        Set<Integer> hole = new HashSet<>(MAINT);
        hole.addAll(Set.of(5, 8));
        seed(coin, TF, lc, 60, hole);
        exchangeMirrorsCache(coin, TF);
        window(lc.minus(24, ChronoUnit.HOURS), lc.minus(19, ChronoUnit.HOURS));
        backfill.backfill(coin, TF, Instant.now());

        assertThat(guard.filter("DYNAMIC", coin, TF, cache(coin, TF))).isNotEmpty();
    }

    @Test
    @DisplayName("⑦ BTC 급락 관문 — 점검 공백만 있으면 판정 가능, 점검 구간이 없으면 판정 불가")
    void btcGateUsesSameRule() throws Exception {
        String tf = "M30";
        Instant lc = lastClosed(1800);
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = ?", tf);
        Set<Integer> hole = Set.of(40, 41, 42, 43, 44, 45);
        seed("KRW-BTC", tf, lc, 80, hole);
        exchangeMirrorsCache("KRW-BTC", tf);
        backfill.backfill("KRW-BTC", tf, Instant.now());
        List<Candle> btc = cache("KRW-BTC", tf);

        BtcMarketGuard.Result before = guard.btcGate("DYNAMIC", tf, btc, btc);
        assertThat(before.unknown()).as("점검 구간 기록 전").isTrue();

        window(lc.minus(45 * 30, ChronoUnit.MINUTES), lc.minus(39 * 30, ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES));
        BtcMarketGuard.Result after = guard.btcGate("DYNAMIC", tf, btc, btc);
        assertThat(after.unknown()).as("점검 공백만 — 판정 가능").isFalse();
    }
}
