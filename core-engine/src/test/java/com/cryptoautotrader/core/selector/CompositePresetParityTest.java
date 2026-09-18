package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.core.backtest.BacktestConfig;
import com.cryptoautotrader.core.backtest.BacktestEngine;
import com.cryptoautotrader.core.backtest.BacktestResult;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트와 운영이 <b>같은 이름으로 같은 전략</b>을 실행하는지, 그리고 실행 간 상태가
 * 격리되는지 고정한다.
 *
 * <h3>이 테스트가 없던 동안 무슨 일이 있었나</h3>
 * <p>COMPOSITE_BREAKOUT의 구성이 세 곳에 따로 있었고 전부 달랐다.
 * <ul>
 *   <li>운영({@code CompositePresetRegistrar}): ATR(0.5)+VD(0.3)+MACD(0.2) + RSI Veto</li>
 *   <li>백테스트({@code BacktestService.compositeBreakoutBt()}): ATR(0.4)+VD(0.3)+RSI(0.2)+EMA(0.1)</li>
 *   <li>단위 테스트({@code StrategySelector} static 폴백): 백테스트와 같은 구버전</li>
 * </ul>
 * core-engine 테스트 전체가 운영이 실행하지 않는 전략을 검증하고 있었고, 저장된 백테스트
 * 성과도 운영 전략의 것이 아니었다. 그런데도 테스트는 전부 통과했다 — 구성을 고정하는
 * 단정이 하나도 없었기 때문이다.
 *
 * <h3>판별력 확인</h3>
 * <p>단정이 실제로 결함을 잡는지 뮤테이션으로 확인했다.
 * <ul>
 *   <li>구성 패리티: 구버전 구성과 비교하면 400봉 중 200회 평가에서 <b>114회</b> 신호가 달라진다
 *       (CompositeStrategy의 reason에 성분 목록이 들어가므로 HOLD끼리도 구별된다).</li>
 *   <li>상태 격리: GRID를 공유 인스턴스로 두 번 돌리면 1회차 2건 → 2회차 <b>1건</b>으로 갈린다.
 *       매수한 레벨이 해제되지 않아 재진입이 막히기 때문이다.</li>
 * </ul>
 * 체결 건수로만 비교하는 방식은 버렸다 — COMPOSITE_BREAKOUT은 ADX·EMA 필터와 RSI Veto가
 * 강해 합성 데이터에서 체결이 0건이라, 빈 목록끼리 비교하는 무력한 테스트가 된다.
 */
class CompositePresetParityTest {

    private final BacktestEngine engine = new BacktestEngine();

    @BeforeAll
    static void registerPresets() {
        // 이 테스트는 Spring 없이 돈다. 등록을 클래스 로딩 순서에 맡기지 않는다.
        CompositePresets.ensureRegistered();
    }

    // ── 구성 패리티 ──────────────────────────────────────────────────────

    @Test
    void COMPOSITE_BREAKOUT은_RSI_Veto를_두른_구성이다() {
        // P1-2에서 RSI는 가중 성분(0.2)에서 Veto 게이트로 분리됐다. 가중치 0.2로는
        // 수학적으로 단독 BUY 차단이 불가능했기 때문이다(confidence > 2.0 필요).
        // 구버전 폴백이 되살아나면 여기서 잡힌다 — 구버전은 RsiVeto로 감싸여 있지 않다.
        assertThat(StrategyRegistry.get("COMPOSITE_BREAKOUT"))
                .as("COMPOSITE_BREAKOUT은 RSI Veto 래퍼여야 한다 (P1-2)")
                .isInstanceOf(RsiVetoStrategy.class);
    }

    @Test
    void 모든_복합_프리셋이_팩토리로_등록된다() {
        // 팩토리가 없으면 실행·세션마다 새 트리를 뽑을 수 없어 상태가 공유된다.
        for (String name : CompositePresets.factories().keySet()) {
            assertThat(StrategyRegistry.hasFactory(name))
                    .as("%s 에 팩토리가 없으면 실행 단위 상태 격리가 불가능하다", name)
                    .isTrue();
        }
    }

    @Test
    void 레지스트리_인스턴스와_프리셋_팩토리가_같은_신호를_낸다() {
        // 레지스트리에서 꺼낸 것(운영·백테스트가 받는 것)과 프리셋 정의가 어긋나면 잡는다.
        List<Candle> candles = volatileCandles(400, 12345L);

        Strategy fromRegistry = StrategyRegistry.createNew("COMPOSITE_BREAKOUT");
        Strategy fromPreset   = CompositePresets.factories().get("COMPOSITE_BREAKOUT").get();

        assertThat(signalTrace(fromRegistry, candles))
                .as("같은 이름이면 어느 경로로 만들어도 같은 신호여야 한다")
                .isEqualTo(signalTrace(fromPreset, candles));
    }

    @Test
    void 모든_프리셋이_레지스트리와_정의가_일치한다() {
        List<Candle> candles = volatileCandles(300, 4242L);

        CompositePresets.factories().forEach((name, factory) -> {
            String viaRegistry = signalTrace(StrategyRegistry.createNew(name), candles);
            String viaPreset   = signalTrace(factory.get(), candles);
            assertThat(viaRegistry).as("%s 의 등록 구성이 프리셋 정의와 다르다", name).isEqualTo(viaPreset);
        });
    }

    // ── 실행 간 상태 격리 ─────────────────────────────────────────────────
    // GRID로 검증한다 — 상태(activeLevels)가 실제로 새는 전략이고,
    // 공유 인스턴스에서는 1회차 2건 → 2회차 1건으로 결과가 갈리는 것을 확인했다.

    @Test
    void 같은_백테스트를_반복해도_결과가_같다() {
        List<Candle> candles = oscillatingCandles(400);
        BacktestConfig config = config("GRID", candles);

        String first  = tradeTrace(engine.run(config, candles));
        String second = tradeTrace(engine.run(config, candles));

        assertThat(first).as("첫 실행에서 체결이 없으면 이 테스트는 아무것도 검증하지 못한다").isNotEmpty();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 다른_전략을_사이에_끼워_실행해도_결과가_같다() {
        List<Candle> candles = oscillatingCandles(400);
        BacktestConfig grid     = config("GRID", candles);
        BacktestConfig momentum = config("COMPOSITE_MOMENTUM", candles);

        String a1 = tradeTrace(engine.run(grid, candles));
        engine.run(momentum, candles);
        String a2 = tradeTrace(engine.run(grid, candles));

        assertThat(a1).isNotEmpty();
        assertThat(a2).as("A→B→A 실행에서 A의 결과가 달라지면 상태가 새고 있다").isEqualTo(a1);
    }

    @Test
    void 선행_구간_실행이_후행_구간_결과에_영향을_주지_않는다() {
        // Walk-Forward의 IS→OOS 구조. WalkForwardTestRunner는 같은 엔진·같은 config로
        // IS 백테스트 직후 OOS 백테스트를 돌린다. OOS 결과가 OOS 단독 실행과 같아야
        // "독립 검증"이라는 전제가 성립한다.
        List<Candle> all = oscillatingCandles(600);
        List<Candle> inSample  = all.subList(0, 300);
        List<Candle> outSample = all.subList(300, 600);

        BacktestConfig config = config("GRID", all);

        String standalone = tradeTrace(engine.run(config, outSample));

        engine.run(config, inSample);   // IS 먼저 실행
        String afterInSample = tradeTrace(engine.run(config, outSample));

        assertThat(standalone).isNotEmpty();
        assertThat(afterInSample)
                .as("IS 실행이 남긴 상태가 OOS로 넘어가면 OOS는 독립 표본이 아니다")
                .isEqualTo(standalone);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private BacktestConfig config(String strategyName, List<Candle> candles) {
        return BacktestConfig.builder()
                .strategyName(strategyName)
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of())
                .build();
    }

    /**
     * 슬라이딩 창마다의 신호를 문자열로 이어붙인다.
     * reason에 성분 목록·점수가 들어가므로 구성이 다르면 HOLD끼리도 값이 갈린다.
     */
    private String signalTrace(Strategy strategy, List<Candle> candles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 200; i < candles.size(); i++) {
            List<Candle> window = candles.subList(Math.max(0, i - 300), i);
            StrategySignal s = strategy.evaluate(window, Map.of("coinPair", "KRW-BTC"));
            sb.append(s.getAction()).append('|')
              .append(s.getStrength()).append('|')
              .append(s.getReason()).append('\n');
        }
        return sb.toString();
    }

    /** 체결 시퀀스 요약 — 거래 하나라도 어긋나면 달라진다. */
    private String tradeTrace(BacktestResult result) {
        StringBuilder sb = new StringBuilder();
        for (TradeRecord t : result.getTrades()) {
            sb.append(t.getSide()).append('@')
              .append(t.getExecutedAt()).append('=')
              .append(t.getPrice()).append(';');
        }
        return sb.toString();
    }

    /** 100~110 고정 범위를 오가는 파동 — GRID의 하단 BUY / 상단 SELL 이 반복 성립한다. */
    private List<Candle> oscillatingCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            BigDecimal p = BigDecimal.valueOf(105 + 5 * Math.sin(i * 2 * Math.PI / 24));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(p)
                    .high(BigDecimal.valueOf(110))
                    .low(BigDecimal.valueOf(100))
                    .close(p)
                    .volume(BigDecimal.valueOf(100 + (i % 23) * 7))
                    .build());
        }
        return candles;
    }

    /** 시드 고정 난수 변동 — 지표가 다양한 구간을 지나도록. 시드를 고정해 flaky를 막는다. */
    private List<Candle> volatileCandles(int count, long seed) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        Random rnd = new Random(seed);
        double price = 50_000_000;

        for (int i = 0; i < count; i++) {
            price *= 1 + (rnd.nextDouble() - 0.47) * 0.03;
            if (price < 1) price = 1;
            double high = price * (1 + rnd.nextDouble() * 0.02);
            double low  = price * (1 - rnd.nextDouble() * 0.02);

            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(BigDecimal.valueOf(price))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(price))
                    .volume(BigDecimal.valueOf(50 + rnd.nextDouble() * 300))
                    .build());
        }
        return candles;
    }
}
