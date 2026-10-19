package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 판정 창 안의 결측 재수집 (2026-10-19) — {@link CandleGapBackfillService}.
 *
 * <p>확인하는 것: ① 거래소에 있는 봉은 다시 받아 채우고, 이미 있는 봉은 바꾸지 않는다 → 최신성 판정이 다시 가능해진다
 * ② 거래소에도 없는 칸(점검·무체결)은 "조회 시점에 없음" 으로 기록한다 — 오래된 칸은 프로세스마다 한 번, 최근 칸은 제한된 재확인.
 * 재수집만으로는 판정 불가가 그대로다(점검 공백 예외는 {@code CandleMaintenanceGapTest})
 * ③ 수집 실패는 아무것도 쓰지 않고, 재시도 간격 안에서는 다시 부르지 않는다 ④ REST 호출은 트랜잭션 밖이다.</p>
 */
@DisplayName("판정 창 안의 결측 재수집")
class CandleGapBackfillTest extends IntegrationTestBase {

    private static final String COIN = "KRW-GAPFILL";
    private static final String TF = "H1";
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC);

    @Autowired private CandleGapBackfillService backfill;
    @Autowired private MarketDataUpsertService upsertService;
    @Autowired private MarketDataCacheRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitRestClient upbitRestClient;

    private final AtomicInteger restCalls = new AtomicInteger();
    private final AtomicBoolean restInTx = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ?", COIN);
        // 조회 기록은 빈의 메모리에 있다 — 시험마다 비운다(같은 시각 칸을 쓰는 시험끼리 간섭하지 않게).
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(backfill, "absentChecks")).clear();
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(backfill, "lastFailure")).clear();
    }

    private static MarketDataCacheEntity row(Instant t, String close) {
        BigDecimal c = new BigDecimal(close);
        return MarketDataCacheEntity.builder().time(t).coinPair(COIN).timeframe(TF)
                .open(c).high(c).low(c).close(c).volume(BigDecimal.ONE).build();
    }

    /** 최근 n 칸(마지막 완료 봉까지) 중 hole 칸을 뺀 캐시를 만든다. */
    private Instant seedWithHole(Instant now, int n, Set<Integer> hole) {
        Instant lastClosed = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        List<MarketDataCacheEntity> rows = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            if (hole.contains(i)) continue;
            rows.add(row(lastClosed.minus(i, ChronoUnit.HOURS), "100"));
        }
        upsertService.upsert(COIN, TF, rows);
        return lastClosed;
    }

    /** 거래소 대역 — available 의 칸만 있다. 업비트처럼 to 이전 봉을 최신순으로 count 개. */
    private void exchangeHas(TreeSet<Instant> available, String close) throws Exception {
        when(upbitRestClient.getCandles(eq(COIN), anyString(), anyInt(), any(), anyInt())).thenAnswer(inv -> {
            restCalls.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) restInTx.set(true);
            Instant to = inv.getArgument(3);
            int count = inv.getArgument(4);
            List<UpbitCandleResponse> out = new ArrayList<>();
            for (Instant t : available.headSet(to, false).descendingSet()) {
                if (out.size() >= count) break;
                UpbitCandleResponse r = new UpbitCandleResponse();
                r.setMarket(COIN);
                r.setCandleDateTimeUtc(UTC.format(t));
                BigDecimal c = new BigDecimal(close);
                r.setOpeningPrice(c);
                r.setHighPrice(c);
                r.setLowPrice(c);
                r.setTradePrice(c);
                r.setCandleAccTradeVolume(BigDecimal.ONE);
                out.add(r);
            }
            return out;
        });
    }

    private TreeSet<Instant> slots(Instant lastClosed, int n, Set<Integer> except) {
        TreeSet<Instant> s = new TreeSet<>();
        for (int i = n - 1; i >= 0; i--) if (!except.contains(i)) s.add(lastClosed.minus(i, ChronoUnit.HOURS));
        return s;
    }

    private List<Candle> cache(Instant now) {
        return repo.findCandles(COIN, TF, now.minus(30, ChronoUnit.DAYS), now).stream()
                .map(e -> Candle.builder().time(e.getTime()).open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose()).volume(e.getVolume()).build())
                .sorted(Comparator.comparing(Candle::getTime)).toList();
    }

    @Test
    @DisplayName("① 거래소에 있는 봉 — 빈 칸만 채우고 기존 봉은 그대로, 판정이 다시 가능해진다")
    void fillsHoleFromExchangeWithoutTouchingExistingRows() throws Exception {
        Instant now = Instant.now();
        Instant lastClosed = seedWithHole(now, 40, Set.of(10, 11, 12, 13));
        assertThat(CandleFreshnessGuard.problem(cache(now), TF, now)).as("시작: 결측 4칸 → 판정 불가").contains("결측 4칸");
        exchangeHas(slots(lastClosed, 40, Set.of()), "999");   // 거래소 값은 999 — 기존 봉이 바뀌면 드러난다

        CandleGapBackfillService.Result r = backfill.backfill(COIN, TF, now);

        assertThat(r.missing()).isEqualTo(4);
        assertThat(r.filled()).isEqualTo(4);
        assertThat(r.absentAtCheck()).isZero();
        List<Candle> after = cache(now);
        assertThat(after).hasSize(40);
        assertThat(after.stream().filter(c -> c.getClose().compareTo(new BigDecimal("999")) == 0).count())
                .as("빈 칸 4개만 거래소 값 — 이미 있는 36개는 바꾸지 않는다").isEqualTo(4);
        assertThat(CandleFreshnessGuard.problem(after, TF, now)).isNull();
        assertThat(restInTx.get()).as("🔴 REST 호출은 트랜잭션 밖").isFalse();
    }

    @Test
    @DisplayName("② 거래소에도 없는 오래된 칸 — 조회 시점 기록, 이 프로세스에서는 다시 묻지 않는다. 판정 불가는 그대로다")
    void exchangeAbsentSlotsAreRecordedOnce() throws Exception {
        Instant now = Instant.now();
        Set<Integer> maintenance = Set.of(20, 21, 22, 23, 24);
        Instant lastClosed = seedWithHole(now, 40, maintenance);
        exchangeHas(slots(lastClosed, 40, maintenance), "999");

        CandleGapBackfillService.Result r = backfill.backfill(COIN, TF, now);
        assertThat(r.filled()).isZero();
        assertThat(r.absentAtCheck()).isEqualTo(5);
        assertThat(backfill.absentAtLastCheck(COIN, TF, lastClosed.minus(22, ChronoUnit.HOURS)).checks()).isEqualTo(1);
        int calls = restCalls.get();

        CandleGapBackfillService.Result again = backfill.backfill(COIN, TF, now.plus(2, ChronoUnit.HOURS));
        assertThat(again.missing()).as("최근 구간(6시간) 밖의 칸은 이 프로세스에서 다시 묻지 않는다").isZero();
        assertThat(restCalls.get()).isEqualTo(calls);
        assertThat(CandleFreshnessGuard.problem(cache(now), TF, now))
                .as("🔴 재수집으로 메워지지 않는다 — 판정 관문의 결측 규칙은 이 작업이 바꾸지 않는다").contains("결측 5칸");
    }

    @Test
    @DisplayName("③ 수집 실패 — 아무것도 쓰지 않고, 재시도 간격 안에서는 다시 부르지 않으며, 간격 뒤에 다시 시도한다")
    void fetchFailureWaitsForRetryInterval() throws Exception {
        Instant now = Instant.now();
        Instant lastClosed = seedWithHole(now, 40, Set.of(5, 6));
        when(upbitRestClient.getCandles(eq(COIN), anyString(), anyInt(), any(), anyInt())).thenAnswer(inv -> {
            restCalls.incrementAndGet();
            throw new RuntimeException("Upbit API 호출 실패: 503");
        });

        CandleGapBackfillService.Result r = backfill.backfill(COIN, TF, now);
        assertThat(r.fetchFailed()).isTrue();
        assertThat(cache(now)).hasSize(38);
        assertThat(restCalls.get()).isEqualTo(1);

        CandleGapBackfillService.Result waiting = backfill.backfill(COIN, TF, now.plus(5, ChronoUnit.MINUTES));
        assertThat(waiting.waitingRetry()).isTrue();
        assertThat(restCalls.get()).as("재시도 간격(10분) 안").isEqualTo(1);

        exchangeHas(slots(lastClosed, 40, Set.of()), "999");
        CandleGapBackfillService.Result retried = backfill.backfill(COIN, TF,
                now.plus(CandleGapBackfillService.RETRY_AFTER_FAILURE).plus(1, ChronoUnit.MINUTES));
        assertThat(retried.filled()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 최근 칸 — 영구 확정하지 않는다: 30분 간격으로 최대 4회 다시 묻고, 그 사이 거래소에 봉이 생기면 채운다")
    void recentAbsentSlotsAreRecheckedWithLimit() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES);   // 경계에 가장 불리한 시각으로 고정
        // 마지막 완료 봉 1~2시간 앞 — 시험 내내(최대 +62분) 최근 구간(6시간) 안에 있다. 칸이 6시간에 가까우면 시각에 따라 경계를 넘는다
        // (2026-10-19 전체 실행 23:58 UTC 에 4시간 58분 전 칸이 +62분에 경계를 넘어 재확인 대상에서 빠졌다 — 정책대로의 동작).
        Set<Integer> hole = Set.of(1, 2);
        Instant lastClosed = seedWithHole(now, 40, hole);
        exchangeHas(slots(lastClosed, 40, hole), "999");
        Instant slot = lastClosed.minus(1, ChronoUnit.HOURS);

        assertThat(backfill.backfill(COIN, TF, now).absentAtCheck()).isEqualTo(2);
        int calls = restCalls.get();
        assertThat(backfill.backfill(COIN, TF, now.plus(10, ChronoUnit.MINUTES)).missing()).as("간격 30분 안").isZero();
        assertThat(restCalls.get()).isEqualTo(calls);

        assertThat(backfill.backfill(COIN, TF, now.plus(31, ChronoUnit.MINUTES)).absentAtCheck()).isEqualTo(2);
        assertThat(backfill.absentAtLastCheck(COIN, TF, slot).checks()).isEqualTo(2);

        // 늦게 생긴 봉 — 거래소에 이제 있다
        exchangeHas(slots(lastClosed, 40, Set.of()), "777");
        CandleGapBackfillService.Result late = backfill.backfill(COIN, TF, now.plus(62, ChronoUnit.MINUTES));
        assertThat(late.filled()).isEqualTo(2);
        assertThat(backfill.absentAtLastCheck(COIN, TF, slot)).as("채워지면 기록을 지운다").isNull();
    }

    @Test
    @DisplayName("최근 칸의 재확인은 최대 4회 — 그 뒤에는 이 프로세스에서 묻지 않는다")
    void recheckLimit() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES);   // 경계에 가장 불리한 시각으로 고정
        Set<Integer> hole = Set.of(1, 2);   // 시험 내내(최대 +155분) 최근 구간 안 — 한도만 시험한다
        Instant lastClosed = seedWithHole(now, 40, hole);
        exchangeHas(slots(lastClosed, 40, hole), "999");
        for (int k = 0; k < CandleGapBackfillService.MAX_CHECKS; k++) {
            backfill.backfill(COIN, TF, now.plus(31L * k, ChronoUnit.MINUTES));
        }
        assertThat(backfill.absentAtLastCheck(COIN, TF, lastClosed.minus(1, ChronoUnit.HOURS)).checks())
                .isEqualTo(CandleGapBackfillService.MAX_CHECKS);
        int calls = restCalls.get();
        assertThat(backfill.backfill(COIN, TF, now.plus(31L * 5, ChronoUnit.MINUTES)).missing()).isZero();
        assertThat(restCalls.get()).isEqualTo(calls);
    }

    @Test
    @DisplayName("결측이 없으면 거래소를 부르지 않는다")
    void noHoleNoCall() throws Exception {
        Instant now = Instant.now();
        Instant lastClosed = seedWithHole(now, 40, Set.of());
        exchangeHas(slots(lastClosed, 40, Set.of()), "999");
        assertThat(backfill.backfill(COIN, TF, now).missing()).isZero();
        assertThat(restCalls.get()).isZero();
    }
}
