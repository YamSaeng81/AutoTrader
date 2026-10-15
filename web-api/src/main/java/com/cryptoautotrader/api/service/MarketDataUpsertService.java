package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 캔들 캐시 저장만 담당하는 <b>코인별 짧은 트랜잭션</b> — 2026-09-28 신설.
 *
 * <p><b>왜 별도 빈인가</b>: 종전에는 {@link MarketDataSyncService#syncMarketData()} 하나가
 * {@code @Transactional} 이었고 그 안에서 <b>전 코인의 REST 수집과 저장</b>이 함께 일어났다.
 * 결과는 둘이었다.
 * <ol>
 *   <li>REST 호출과 rate-limit {@code sleep} 이 <b>트랜잭션 안</b>에서 일어나 그 구간 내내
 *       행 잠금과 커넥션을 붙잡았다.</li>
 *   <li>UPDATE 는 <b>커밋 시점 flush</b> 에 몰리므로, 한 행의 잠금 대기가 per-pair
 *       {@code try/catch} 를 지나쳐 <b>그 회차의 모든 코인 쓰기를 함께 롤백</b>시켰다.
 *       2026-09-28 잠금 시험에서 실제로 관측됐다 — 실패가 {@code syncPair} 의 catch 가 아니라
 *       {@code SchedulerConfig} 의 errorHandler 에 잡혔다.</li>
 * </ol>
 *
 * <p>이 빈을 <b>프록시 경유로 코인마다 한 번</b> 호출하면 커밋도 코인마다 끝난다. 따라서
 * 호출자의 {@code try/catch} 가 <b>커밋 실패까지</b> 포착하고 다음 코인으로 진행할 수 있다.
 *
 * <p>🔴 자기 호출(self-invocation)로는 이 경계가 생기지 않는다 — 같은 클래스 안에서 부르면
 * 프록시를 지나지 않아 {@code @Transactional} 이 무시된다. 그래서 별도 빈으로 뺐다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataUpsertService {

    private final JdbcTemplate jdbc;

    /** PostgreSQL 이면 {@code ON CONFLICT}, 아니면(H2 시험 기반) {@code MERGE ... KEY}. 처음 한 번 확인한다. */
    private volatile Boolean postgres;

    /**
     * 한 (코인, 타임프레임) 의 캔들을 upsert 한다 — <b>이 호출이 하나의 트랜잭션</b>이다(호출자가 트랜잭션이 아니면).
     *
     * <p>🔴 2026-10-15 (선행 조건 5-g): 종전의 JPA 병합(saveAll)은 <b>조회한 뒤 삽입</b>이라, 두 트랜잭션이 같은 새 캔들을 동시에
     * 쓰면 늦은 쪽이 PK 중복으로 실패했다 — 동기화(60초)와 DYNAMIC 틱({@link MarketDataSyncService#fetchWithCache}, 틱 트랜잭션
     * 안)이 같은 코인의 현재 캔들을 함께 쓰는 것이 바로 그 경우다. DYNAMIC 쪽이 지면 <b>틱 트랜잭션 전체</b>가 커밋에서 실패한다.
     * 이제 한 문장의 원자적 upsert({@code INSERT ... ON CONFLICT (PK) DO UPDATE})다 — 늦은 쪽은 앞 트랜잭션의 커밋을 기다렸다가
     * 갱신한다. 행 순서는 시각 오름차순으로 고정해 두 쓰기의 잠금 순서가 같다.</p>
     *
     * @return 저장 요청한 행 수
     */
    @Transactional
    public int upsert(String coinPair, String timeframe, List<MarketDataCacheEntity> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        List<MarketDataCacheEntity> sorted = rows.stream()
                .sorted(java.util.Comparator.comparing(MarketDataCacheEntity::getTime)).toList();
        String sql = isPostgres()
                ? "INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (time, coin_pair, timeframe) DO UPDATE SET "
                        + "open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume"
                : "MERGE INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume) "
                        + "KEY (time, coin_pair, timeframe) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        boolean pg = isPostgres();
        jdbc.batchUpdate(sql, sorted, sorted.size(), (ps, r) -> {
            if (pg) {
                ps.setObject(1, OffsetDateTime.ofInstant(r.getTime(), ZoneOffset.UTC));
            } else {
                // H2 의 열은 시간대 없는 TIMESTAMP 이고, JPA 는 JVM 시간대로 읽고 쓴다 — 같은 방식으로 맞춘다.
                ps.setTimestamp(1, Timestamp.from(r.getTime()));
            }
            ps.setString(2, r.getCoinPair());
            ps.setString(3, r.getTimeframe());
            ps.setBigDecimal(4, r.getOpen());
            ps.setBigDecimal(5, r.getHigh());
            ps.setBigDecimal(6, r.getLow());
            ps.setBigDecimal(7, r.getClose());
            ps.setBigDecimal(8, r.getVolume());
        });
        return rows.size();
    }

    private boolean isPostgres() {
        Boolean v = postgres;
        if (v == null) {
            v = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
                    c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres")));
            postgres = v;
        }
        return v;
    }
}
