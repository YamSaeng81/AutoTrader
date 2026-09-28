package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>코인별 커밋 격리</b> — 한 코인의 저장 실패가 다른 코인의 커밋을 되돌리지 않는다
 * (2026-09-28 신설).
 *
 * <h3>왜 이 성질이 필요한가</h3>
 * 2026-09-28 잠금 시험에서, 한 행의 잠금 대기가 <b>그 회차 전체</b>를 롤백시켰다 —
 * {@code syncMarketData} 하나가 트랜잭션이라 UPDATE 가 커밋 시점 flush 에 몰렸고, 실패가
 * per-pair {@code try/catch} 를 지나쳐 {@code SchedulerConfig} errorHandler 에 잡혔다.
 * 잠금이 계속 유지되면 <b>여러 회차가 연속 실패</b>할 수 있으므로, 실패 범위를 코인별로 줄인다.
 *
 * <h3>이 테스트가 확인하는 것과 하지 않는 것</h3>
 * <ul>
 *   <li>확인한다: {@link MarketDataUpsertService#upsert} 한 번이 <b>하나의 커밋 단위</b>이고,
 *       뒤 호출의 실패가 <b>앞 호출의 커밋을 되돌리지 않는다</b>.</li>
 *   <li>🔴 확인하지 않는다: 실제 <b>행 잠금</b> 상황에서의 거동. H2 로는 재현되지 않으며
 *       (2026-09-28 test A 에서 원인 미확정), 운영에서 psql 로 한 코인을 잠가
 *       <b>그 코인만 롤백되고 다른 코인은 커밋되는지</b>를 따로 확인한다.</li>
 * </ul>
 */
class MarketDataUpsertIsolationTest extends IntegrationTestBase {

    private static final String TF = "H1";

    @Autowired
    private MarketDataUpsertService upsertService;

    @Autowired
    private MarketDataCacheRepository repo;

    private final Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(5, ChronoUnit.HOURS);

    private MarketDataCacheEntity row(String coin, int i, boolean valid) {
        return MarketDataCacheEntity.builder()
                .time(base.plus(i, ChronoUnit.HOURS))
                .coinPair(coin).timeframe(TF)
                .open(new BigDecimal("100")).high(new BigDecimal("110")).low(new BigDecimal("90"))
                // 🔴 close 는 NOT NULL 이다 — null 이면 그 트랜잭션만 실패한다.
                .close(valid ? new BigDecimal("105") : null)
                .volume(new BigDecimal("1000"))
                .build();
    }

    private List<MarketDataCacheEntity> rows(String coin, boolean valid) {
        return List.of(row(coin, 0, true), row(coin, 1, valid));
    }

    private long count(String coin) {
        return repo.findCandles(coin, TF, base.minus(1, ChronoUnit.HOURS),
                base.plus(10, ChronoUnit.HOURS)).size();
    }

    @Test
    @DisplayName("🔴 한 코인의 저장 실패가 먼저 커밋된 다른 코인을 되돌리지 않는다")
    void oneCoinFailureDoesNotRollBackAnother() {
        String ok = "KRW-ISOA";
        String bad = "KRW-ISOB";

        assertThat(upsertService.upsert(ok, TF, rows(ok, true)))
                .as("정상 코인은 저장된다")
                .isEqualTo(2);

        assertThatThrownBy(() -> upsertService.upsert(bad, TF, rows(bad, false)))
                .as("NOT NULL 위반으로 이 코인의 트랜잭션은 실패해야 한다 — "
                        + "실패하지 않으면 이 테스트의 전제가 무너진다")
                .isInstanceOf(Exception.class);

        assertThat(count(ok))
                .as("🔴 앞서 커밋된 코인은 남아 있어야 한다. 0 이면 한 회차가 통째로 "
                        + "롤백되는 종전 구조로 되돌아간 것이다")
                .isEqualTo(2);

        assertThat(count(bad))
                .as("실패한 코인은 그 트랜잭션만 롤백된다 — 부분 저장이 남으면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("실패 이후에도 다음 코인은 정상 저장된다 — 루프가 이어질 수 있다")
    void nextCoinStillCommitsAfterFailure() {
        String bad = "KRW-ISOC";
        String next = "KRW-ISOD";

        assertThatThrownBy(() -> upsertService.upsert(bad, TF, rows(bad, false)))
                .isInstanceOf(Exception.class);

        assertThat(upsertService.upsert(next, TF, rows(next, true))).isEqualTo(2);
        assertThat(count(next))
                .as("앞 코인의 실패가 뒤 코인의 커밋을 막지 않아야 한다")
                .isEqualTo(2);
    }

    /**
     * 🔴 구조 가드 — 격리는 <b>호출자가 트랜잭션이 아니라는 전제</b>에 의존한다.
     *
     * <p>{@code upsert} 는 {@code REQUIRED} 다. 따라서 {@code syncMarketData} 에
     * {@code @Transactional} 이 다시 붙으면 코인별 커밋이 <b>하나의 트랜잭션으로 합쳐져</b>
     * 격리가 조용히 사라진다 — 위 두 테스트는 {@code upsert} 를 직접 부르므로 그것을 잡지 못한다.
     *
     * <p>⚠️ {@code REQUIRES_NEW} 로 바꿔 강제 격리하는 방법도 있지만 <b>택하지 않았다</b>:
     * {@code fetchWithCache} 는 호출자(DYNAMIC) 트랜잭션 안에서 같은 테이블을 쓰므로,
     * 그 위에 REQUIRES_NEW 를 겹치면 2026-09-26 에 겪은 <b>같은 행 잠금 순환</b>을 다시 만든다.
     * 그래서 경계는 REQUIRED 로 두고, 대신 이 가드로 전제를 지킨다.
     */
    @Test
    @DisplayName("🔴 syncMarketData 에 @Transactional 이 붙어 있으면 안 된다 — 코인별 격리의 전제")
    void syncMarketDataMustNotBeTransactional() throws Exception {
        Method m = MarketDataSyncService.class.getDeclaredMethod("syncMarketData");
        assertThat(m.getAnnotation(Transactional.class))
                .as("메서드에 @Transactional 이 붙으면 코인별 커밋이 한 트랜잭션으로 합쳐진다")
                .isNull();
        assertThat(MarketDataSyncService.class.getAnnotation(Transactional.class))
                .as("클래스 레벨 @Transactional 도 같은 효과를 낸다")
                .isNull();
    }
}
