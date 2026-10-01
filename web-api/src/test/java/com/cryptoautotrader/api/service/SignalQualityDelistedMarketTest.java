package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>404 는 영구 실패다</b> — 상장폐지 마켓을 같은 실행 안에서 반복 호출하지 않는다
 * (2026-10-01 신설).
 *
 * <h3>왜 이 성질이 필요한가</h3>
 * 404 를 일시 실패처럼 다시 시도하고 있었다. {@code fetchClosePrice} 가 null 이면 그 행은 저장되지
 * 않아 다음 조회 대상에 남으므로, 상장폐지 코인 하나가 <b>매 실행 수백 번</b> 호출됐다.
 * 2026-10-01 06시 운영 실측: KRW-BONK 520건 · KRW-STORJ 154건. 앱 전체가 업비트 호출을 하나의
 * 공유 스로틀로 직렬화하므로 그 낭비가 <b>429 를 유발해 다른 소비자를 실패시켰다</b> —
 * 06:20:17 에 KRW-G·KRW-INJ 의 캔들 수집이 429 로 실패해 그 회차 동기화가 누락됐다
 * (전향 검증 22코인 중 둘이다).
 *
 * <h3>이 테스트가 지키는 계약</h3>
 * <ul>
 *   <li>404 는 마켓당 <b>1회만</b> 호출한다 — 같은 실행의 나머지 행은 호출 없이 건너뛴다.</li>
 *   <li>🔴 429·5xx 는 <b>일시</b> 실패이므로 건너뛰지 않는다. 여기서 함께 캐시하면 한 번의
 *       한도 초과로 그 코인을 프로세스 수명 내내 포기하게 된다.</li>
 * </ul>
 * ⚠️ 판정은 {@code UpbitRestClient} 의 예외 문구("호출 실패: 404")에 결합돼 있다. 그 문구가
 * 바뀌면 이 테스트가 깨지도록 두는 것이 목적이다 — 조용히 404 재시도로 되돌아가지 않게.
 */
class SignalQualityDelistedMarketTest {

    private final StrategyLogRepository repo = mock(StrategyLogRepository.class);
    private final UpbitRestClient upbit = mock(UpbitRestClient.class);
    private final SignalQualityService service = new SignalQualityService(repo, upbit);

    private StrategyLogEntity holdLog(long id) {
        return StrategyLogEntity.builder()
                .id(id)
                .coinPair("KRW-GONE")
                .signal("HOLD")
                .signalPrice(BigDecimal.valueOf(100))
                .createdAt(Instant.now().minus(30, ChronoUnit.HOURS))
                .build();
    }

    private void pendingRows(int n) {
        List<StrategyLogEntity> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= n; i++) rows.add(holdLog(i));
        when(repo.findPendingHoldFor4hEval(any(), anyInt()))
                .thenReturn(rows).thenReturn(List.of());
    }

    @Test
    @DisplayName("🔴 404 마켓은 같은 실행에서 한 번만 호출한다 — 행이 몇 개든")
    void delistedMarketIsCalledOnlyOnce() throws Exception {
        pendingRows(5);
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt()))
                .thenThrow(new RuntimeException(
                        "Upbit API 호출 실패: 404 (https://api.upbit.com/v1/candles/minutes/60"
                                + "?market=KRW-GONE&count=1&to=2026-10-01T06:00:00Z)"));

        service.evaluateHoldBaselineLoop(true, 200);

        verify(upbit, times(1))
                .getCandles(anyString(), anyString(), anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("429 는 일시 실패다 — 건너뛰지 않고 행마다 다시 시도한다")
    void rateLimitIsNotTreatedAsPermanent() throws Exception {
        pendingRows(5);
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt()))
                .thenThrow(new RuntimeException("Upbit API 호출 실패: 429 (…)"));

        service.evaluateHoldBaselineLoop(true, 200);

        verify(upbit, times(5))
                .getCandles(anyString(), anyString(), anyInt(), any(), anyInt());
    }
}
