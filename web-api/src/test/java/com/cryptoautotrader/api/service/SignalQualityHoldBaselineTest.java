package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HOLD 기준선(대조군) 백필 — 2026-09-07 신설.
 *
 * <p>배경: 사후수익 백필이 BUY/SELL 에만 돌아 HOLD 74,462건이 전부 미평가였다. 대조군이 없으면
 * "BUY 신호 사후 -1.25%" 가 신호 탓인지 시장 탓인지 구분되지 않는다 — 실제로 코인·시각을
 * 통제하니 -1.25% → -0.16%, MTF_BTC 는 -0.48% → +1.21% 로 부호까지 뒤집혔다.</p>
 *
 * <p>여기서 지키는 계약은 <b>HOLD 는 BUY 와 같은 롱 방향으로 계산된다</b>는 것이다.
 * SELL 방향(부호 반전)으로 계산되면 BUY 와 직접 뺄셈이 안 돼 대조군 구실을 못 한다.</p>
 */
class SignalQualityHoldBaselineTest {

    private final StrategyLogRepository repo = mock(StrategyLogRepository.class);
    private final UpbitRestClient upbit = mock(UpbitRestClient.class);
    private final SignalQualityService service = new SignalQualityService(repo, upbit);

    private StrategyLogEntity holdLog(String signal, double signalPrice) {
        return StrategyLogEntity.builder()
                .id(1L)
                .coinPair("KRW-BTC")
                .signal(signal)
                .signalPrice(BigDecimal.valueOf(signalPrice))
                .createdAt(Instant.now().minus(30, ChronoUnit.HOURS))
                .build();
    }

    private void stubPrice(double price) throws Exception {
        UpbitCandleResponse c = new UpbitCandleResponse();
        c.setTradePrice(BigDecimal.valueOf(price));
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(c));
    }

    @Test
    void HOLD_기준선은_상승하면_양수다_BUY와_같은_롱_방향() throws Exception {
        StrategyLogEntity entry = holdLog("HOLD", 100);
        when(repo.findPendingHoldFor4hEval(any(), anyInt()))
                .thenReturn(List.of(entry)).thenReturn(List.of());
        stubPrice(110); // +10%

        int processed = service.evaluateHoldBaselineLoop(true, 10);

        assertThat(processed).isEqualTo(1);
        assertThat(entry.getPriceAfter4h()).isEqualByComparingTo("110");
        assertThat(entry.getReturn4hPct()).isEqualByComparingTo("10.0000");
    }

    @Test
    void HOLD_기준선은_하락하면_음수다() throws Exception {
        StrategyLogEntity entry = holdLog("HOLD", 100);
        when(repo.findPendingHoldFor24hEval(any(), anyInt()))
                .thenReturn(List.of(entry)).thenReturn(List.of());
        stubPrice(95); // -5%

        int processed = service.evaluateHoldBaselineLoop(false, 10);

        assertThat(processed).isEqualTo(1);
        assertThat(entry.getReturn24hPct()).isEqualByComparingTo("-5.0000");
    }

    /**
     * 상장폐지 코인 등으로 캔들 조회가 전량 실패하면, 리포지토리는 다음 조회에서도 같은 행을
     * 돌려준다. 진전이 없을 때 루프를 접지 않으면 무한 루프가 된다 —
     * {@link SignalQualityService#evaluateLoop} 가 page 전진으로 푼 문제를 이쪽은 조기 종료로 푼다.
     */
    @Test
    void 전량_조회실패면_무한루프하지_않고_종료한다() throws Exception {
        StrategyLogEntity entry = holdLog("HOLD", 100);
        when(repo.findPendingHoldFor4hEval(any(), anyInt())).thenReturn(List.of(entry));
        when(upbit.getCandles(anyString(), anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of()); // 캔들 없음 → fetchClosePrice null

        int processed = service.evaluateHoldBaselineLoop(true, 10);

        assertThat(processed).isZero();
        assertThat(entry.getReturn4hPct()).isNull();
    }

    /** 예산을 넘겨 부르지 않는다 — BUY/SELL 평가를 굶기지 않기 위한 상한. */
    @Test
    void 예산을_초과해_처리하지_않는다() throws Exception {
        when(repo.findPendingHoldFor4hEval(any(), eq(2)))
                .thenReturn(List.of(holdLog("HOLD", 100), holdLog("HOLD", 100)));
        stubPrice(110);

        int processed = service.evaluateHoldBaselineLoop(true, 2);

        assertThat(processed).isEqualTo(2);
    }
}
