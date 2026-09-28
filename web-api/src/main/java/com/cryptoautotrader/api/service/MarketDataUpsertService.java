package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final MarketDataCacheRepository marketDataCacheRepo;

    /**
     * 한 (코인, 타임프레임) 의 캔들을 upsert 한다 — <b>이 호출이 하나의 트랜잭션</b>이다.
     *
     * <p>PK(time + coinPair + timeframe) 충돌 시 UPDATE 로 병합된다(JPA merge).
     *
     * @return 저장 요청한 행 수
     */
    @Transactional
    public int upsert(String coinPair, String timeframe, List<MarketDataCacheEntity> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        marketDataCacheRepo.saveAll(rows);
        return rows.size();
    }
}
