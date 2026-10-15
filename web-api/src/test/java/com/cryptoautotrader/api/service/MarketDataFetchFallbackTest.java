package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 캔들 캐시 — 선행 조건 5-g(충돌 시 갱신)와 5-h 진입 전 시험에서 찾은 결함(2026-10-15).
 *
 * <p>재시작 시험 S16 에서 DYNAMIC 의 손절·익절·시간 초과가 하나도 나가지 않았다. 원인: {@link MarketDataSyncService#fetchWithCache}
 * 가 REST 수집 실패를 그대로 던져, 그 예외가 트랜잭션 프록시를 지나며 호출자(processTick)의 트랜잭션을 롤백 전용으로 만들었다.
 * 호출자가 예외를 잡아도 틱 커밋이 실패했고, 캐시에 캔들이 충분해도 판정이 빠졌다.</p>
 */
@DisplayName("캔들 캐시 — REST 실패 시 캐시로 대신, 원자적 upsert")
class MarketDataFetchFallbackTest extends IntegrationTestBase {

    private static final String COIN = "KRW-MDFB";
    private static final String TF = "H1";

    @Autowired private MarketDataSyncService syncService;
    @Autowired private MarketDataUpsertService upsertService;
    @Autowired private MarketDataCacheRepository repo;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitRestClient upbitRestClient;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ?", COIN);
    }

    private static List<MarketDataCacheEntity> rows(Instant last, int n, String close) {
        List<MarketDataCacheEntity> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal c = new BigDecimal(close);
            out.add(MarketDataCacheEntity.builder().time(last.minus(i, ChronoUnit.HOURS)).coinPair(COIN).timeframe(TF)
                    .open(c).high(c).low(c).close(c).volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    @Test
    @DisplayName("🔴 REST 수집이 실패해도 캐시 캔들을 돌려주고, 바깥 트랜잭션을 롤백 전용으로 만들지 않는다")
    void restFailureFallsBackToCacheWithoutPoisoningCaller() throws Exception {
        Instant last = Instant.now().truncatedTo(ChronoUnit.HOURS);
        upsertService.upsert(COIN, TF, rows(last, 520, "310"));
        when(upbitRestClient.getCandles(anyString(), anyString(), anyInt(), any(), anyInt()))
                .thenThrow(new RuntimeException("Upbit API 호출 실패: 429"));

        AtomicBoolean rollbackOnly = new AtomicBoolean(true);
        List<Candle> got = new TransactionTemplate(txManager).execute(st -> {
            List<Candle> cs = syncService.fetchWithCache(COIN, TF, 500);
            rollbackOnly.set(st.isRollbackOnly());
            return cs;
        });

        assertThat(rollbackOnly.get()).as("호출자 트랜잭션이 롤백 전용이면 DYNAMIC 틱 커밋이 실패한다").isFalse();
        assertThat(got).as("캐시 500개 창").hasSizeGreaterThanOrEqualTo(500);
        assertThat(got.get(got.size() - 1).getClose()).isEqualByComparingTo("310");
    }

    @Test
    @DisplayName("upsert 는 같은 키를 갱신한다 — 두 번 써도 행이 늘지 않고 마지막 값이 남는다")
    void upsertUpdatesExistingRows() {
        Instant last = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        upsertService.upsert(COIN, TF, rows(last, 3, "100"));
        upsertService.upsert(COIN, TF, rows(last, 3, "120"));
        List<MarketDataCacheEntity> got = repo.findCandles(COIN, TF, last.minus(5, ChronoUnit.HOURS), last.plus(1, ChronoUnit.HOURS));
        assertThat(got).hasSize(3);
        assertThat(got).allSatisfy(r -> assertThat(r.getClose()).isEqualByComparingTo("120"));
        assertThat(got.get(got.size() - 1).getTime()).as("시각이 어긋나지 않는다(JPA 와 같은 방식으로 저장)").isEqualTo(last);
    }
}
