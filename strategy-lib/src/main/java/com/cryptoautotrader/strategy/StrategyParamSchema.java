package com.cryptoautotrader.strategy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전략 파라미터의 <b>이름 → 단위</b> 단일 진실 원천 (Wave 4-M, 2026-09-22).
 *
 * <p><b>왜 있나</b> — 같은 이름의 파라미터가 곳에 따라 단위가 달랐다.
 * {@code stopLossPct} 가 {@code HeikinAshiStochStrategy} 에서는 1.5(퍼센트),
 * {@code MacdStochBbStrategy} 에서는 0.02(비율)였다. 이름이 같으므로
 * <b>같은 params 맵이 두 전략에 서로 다른 뜻으로 읽힌다.</b>
 *
 * <p>세션의 {@code strategy_params} 가 전략 평가 params 를 그대로 시드하므로
 * ({@code LiveTradingService}), {@code stopLossPct: 5.0}(5% 의도)을 저장해 두면
 * MacdStochBb 는 <b>500%</b> 로 읽는다. 예외도, 경고 로그도, 이상한 숫자도 나오지 않는다 —
 * 손절이 사실상 사라질 뿐이다. Wave 0~3 에서 고친 결함들과 같은 부류다.
 *
 * <p><b>규약: 이름이 {@code ...Pct} 로 끝나면 퍼센트다.</b> 2 는 2% 이고 0.02 가 아니다.
 * 프로젝트 전반이 이미 그렇다 — {@code ExitRuleConfig.stopLossPct = 5.0},
 * 모든 요청 DTO·엔티티, {@code CompositeStrategy} 의 {@code band / 100.0} 까지.
 * MacdStochBb 하나만 달랐고, 2026-09-22 에 퍼센트로 통일했다(기본값 거동은 불변).
 *
 * <p><b>🔴 여기에 기본값은 두지 않는다.</b> 기본값은 전략마다 정당하게 다르다
 * (손절 폭은 HeikinAshiStoch 1.5% · MacdStochBb 2.0%). 하나로 선언하면 거짓이 되고,
 * 전략이 그 값을 따르지 않는 순간 문서가 코드를 배신한다.
 * <b>이름마다 하나뿐이어야 하는 것은 단위이지 값이 아니다</b> — 결함도 단위에서 났다.
 *
 * <p><b>새 퍼센트 파라미터를 추가할 때</b>
 * <ol>
 *   <li>이름을 {@code ...Pct} 로 짓고 여기 등록한다</li>
 *   <li>읽을 때 {@link StrategyParamUtils#getPercentAsRatio} 를 쓴다 —
 *       {@code getDouble} 로 읽고 호출자가 나누면 단위가 다시 갈라진다</li>
 * </ol>
 *
 * <p>{@code StrategyParamSchemaTest} 가 등록 누락과 단위 역전을,
 * {@code PercentUnitConsistencyTest} 가 전략 간 해석 불일치를 잡는다.
 *
 * <p>⚠️ 이 클래스는 <b>선언</b>이지 실행 시 검증 게이트가 아니다. 전략이 여기를 거치지 않고
 * {@code getDouble} 로 직접 읽어도 컴파일은 된다. 그 경로를 막는 것은 테스트다.
 */
public final class StrategyParamSchema {

    /** 파라미터 값의 단위. */
    public enum Unit {
        /** 퍼센트. 2.0 = 2%. 쓸 때 100 으로 나눈다. */
        PERCENT,
        /** 비율. 0.02 = 2%. 그대로 곱한다. */
        RATIO,
        /** 봉 개수·기간 등 무차원 정수. */
        COUNT,
        /** 배수 (예: 볼린저 2.0σ, TP = SL × 2). */
        FACTOR
    }

    /**
     * @param name params 맵의 키
     * @param unit 값의 단위 — <b>이 클래스가 보장하는 것은 이것뿐이다</b>
     * @param note 무엇을 뜻하는가
     */
    public record Param(String name, Unit unit, String note) {}

    private static final Map<String, Param> BY_NAME = new LinkedHashMap<>();

    private static void declare(String name, Unit unit, String note) {
        BY_NAME.put(name, new Param(name, unit, note));
    }

    static {
        // ── 손절·익절 ─────────────────────────────────────────────────────
        // 🔴 충돌이 있었던 두 이름이다. 2026-09-22 이전에는 전략마다 단위가 달랐다.
        //    기본값: HeikinAshiStoch 1.5/3.0 · MacdStochBb 2.0/4.0 — 다른 게 정상이다.
        declare("stopLossPct",   Unit.PERCENT, "진입가 대비 손절 거리");
        declare("takeProfitPct", Unit.PERCENT, "진입가 대비 익절 거리");

        // ── 임계·허용폭 ───────────────────────────────────────────────────
        declare("triggerPct",            Unit.PERCENT, "GRID 레벨 간격 트리거");
        declare("thresholdPct",          Unit.PERCENT, "신호 발생 임계");
        declare("minGapPct",             Unit.PERCENT, "FVG 최소 갭 폭");
        declare("pullbackTolerancePct",  Unit.PERCENT, "눌림목 허용 범위");
        declare("minStrengthPct",        Unit.PERCENT, "신뢰도 하한 (0 이면 비활성)");
        declare("emaFilterDeadbandPct",  Unit.PERCENT, "EMA 방향 필터 데드밴드");
    }

    private StrategyParamSchema() {}

    /** 등록된 파라미터. 없으면 null. */
    public static Param of(String name) {
        return BY_NAME.get(name);
    }

    /** 등록 순서를 보존한 전체 목록. */
    public static Collection<Param> all() {
        return BY_NAME.values();
    }

    /** 등록돼 있고 퍼센트 단위인가. */
    public static boolean isPercent(String name) {
        Param p = BY_NAME.get(name);
        return p != null && p.unit() == Unit.PERCENT;
    }
}
