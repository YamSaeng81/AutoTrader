package com.cryptoautotrader.core;

import com.cryptoautotrader.core.risk.ExitRuleFormula;
import com.cryptoautotrader.core.selector.CompositePresets;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4 — <b>전략 거동이 바뀌면 여기가 먼저 운다.</b>
 *
 * <p><b>무엇이 비어 있었나</b><br>
 * {@code BACKTEST_RULESET_VERSION} 은 "같은 입력에 다른 결과를 내는 변경이면 올린다"는
 * 규칙인데, <b>그 판단을 강제하는 장치가 없었다.</b> {@code RulesetFingerprintTest} 는
 * 세션 파라미터 지문이고, {@code ExitRuleFormula} 상수만 보는 가드는 전략 내부를 못 본다.
 *
 * <p>실제로 2026-09-21 Wave 3 의 세 수정 — MTF 상위봉 경계(L) · GRID 레벨 해제(I) ·
 * 무변동 경계(H) — 은 <b>전부 전략 거동을 바꿨는데 어떤 테스트도 울지 않았다.</b>
 * v3 → v4 버전 업은 순전히 사람의 판단이었고, 놓쳤다면 낡은 규칙의 백테스트 결과가
 * 유효한 근거로 남았을 것이다. 게이트는 조합별 최신 실행 하나만 보기 때문이다.
 *
 * <p><b>이 테스트가 하는 일</b><br>
 * 고정된 캔들 시나리오를 슬라이딩 윈도우로 훑으며 각 전략의 신호(행동 + 강도)를 해시한다.
 * 해시가 달라지면 <b>그 전략의 거동이 바뀐 것</b>이므로 실패한다.
 *
 * <p>🔴 <b>실패했다고 해시만 갈아끼우지 말 것.</b> 먼저 판단해야 한다:
 * <ul>
 *   <li><b>의도한 변경인가</b> → {@code BACKTEST_RULESET_VERSION} 을 올릴지 결정하고,
 *       올렸다면 저장된 백테스트 결과가 무효가 된다는 뜻이다(재실행 필요)</li>
 *   <li><b>의도하지 않았는가</b> → 회귀다. 해시가 아니라 코드를 고쳐야 한다</li>
 * </ul>
 * 판단을 마친 뒤에 {@link #EXPECTED} 를 갱신한다. 실패 메시지가 새 값을 그대로 알려준다.
 *
 * <p>⚠️ <b>확인된 판별력 (2026-09-22 뉴테이션 실측)</b>
 *
 * <table><caption>Wave 3 수정을 되돌렸을 때</caption>
 *   <tr><th>수정</th><th>결과</th></tr>
 *   <tr><td>H — RSI 무변동 중립(50)</td><td>✓ 잡힘</td></tr>
 *   <tr><td>I — GRID 레벨 해제</td><td>🔴 <b>못 잡음</b></td></tr>
 * </table>
 *
 * <p>🔴 <b>I 를 못 잡는 것은 시나리오 부족이 아니라 결함 자체의 성질이다.</b>
 * GRID 의 SELL 은 {@code positionRatio >= 0.7} <b>그리고</b>
 * {@code distanceFromLevel <= triggerThreshold}(격자선 근처) 일 때만 발화한다.
 * 저변동 구간 300봉을 넣고 3봉 간격으로 훑어도 BUY·SELL 이 각각 몇 차례뿐이라,
 * "SELL 로 해제된 레벨에서 다시 BUY" 가 일어나는 창이 거의 열리지 않는다.
 *
 * <p>이것은 2026-09-22 v4 실측이 말한 것과 <b>같은 사실</b>이다 —
 * GRID 수정은 메이저·3.7년 구간에서 거래를 <b>단 하나도 바꾸지 않았다</b>
 * ({@code RANGE_RESET_THRESHOLD} 1% 가 {@code activeLevels} 를 상시 초기화해
 * 버그가 가려져 있었다). 즉 <b>잡을 거동 변화가 실질적으로 없다.</b>
 * 이를 억지로 잡게 하려면 GRID 전용 인공 시나리오를 까야 하는데,
 * 그런 테스트는 이미 {@code GridStrategyLevelReleaseTest} 가 담당한다.
 * <b>여기서 중복으로 뒤쪽는 것은 가치보다 노이즈가 크다.</b>
 *
 * <p>⚠️ <b>일반 한계</b>: 이 시나리오가 건드리지 않는 분기의 변경은 못 잡는다.
 */
class StrategyBehaviourFingerprintTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * 고정 윈도우 폭. 등록된 전략의 최대 요구량(HEIKIN_ASHI_STOCH 205)보다 커야 하고,
     * 아래 무변동 구간(260봉)보다는 작아야 한다 — 그래야 무변동만으로 채워진 윈도우가 생긴다.
     */
    private static final int WINDOW = 250;

    /** 테스트 전용 더미는 제외 — 시간 기반이라 결정적이지 않다. */
    private static final List<String> EXCLUDED = List.of("TEST_TIMED");

    /**
     * 전략별 거동 지문. <b>갱신 전에 위 javadoc 의 판단 절차를 먼저 밟을 것.</b>
     *
     * <p>2026-09-22 기준 = 규칙 v{@value ExitRuleFormula#BACKTEST_RULESET_VERSION} 의 거동.
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("ATR_BREAKOUT", "186cc371d1de190e"),
            Map.entry("BOLLINGER", "735e475610026893"),
            Map.entry("COMPOSITE", "f577fc9327b4af90"),
            Map.entry("COMPOSITE_BREAKOUT", "b116e94ce262c8ab"),
            Map.entry("COMPOSITE_BREAKOUT_ICHIMOKU", "b116e94ce262c8ab"),
            Map.entry("COMPOSITE_ETH", "a5502a23463dc386"),
            Map.entry("COMPOSITE_MEANREV_BB", "15f6e9641f3ba93c"),
            Map.entry("COMPOSITE_MOMENTUM", "bdc06c8e3ad88d15"),
            Map.entry("COMPOSITE_MOMENTUM_ICHIMOKU", "e6e31f1d28c1a7be"),
            Map.entry("COMPOSITE_MOMENTUM_ICHIMOKU_V2", "243001c860725068"),
            Map.entry("COMPOSITE_MTF_BTC", "b116e94ce262c8ab"),
            Map.entry("COMPOSITE_MTF_BTC_STRICT", "b116e94ce262c8ab"),
            Map.entry("COMPOSITE_MTF_CONFIRMED", "6123e8904316362f"),
            Map.entry("COMPOSITE_MTF_MOMENTUM", "243001c860725068"),
            Map.entry("COMPOSITE_PULLBACK_MTF", "aafccdc608b5d427"),
            Map.entry("COMPOSITE_REGIME_ROUTER", "6123e8904316362f"),
            Map.entry("EMA_CROSS", "aa4802dd98e6b240"),
            Map.entry("FAIR_VALUE_GAP", "aad70c51231eec30"),
            Map.entry("GRID", "9f3ea497b55eb3a2"),
            Map.entry("HEIKIN_ASHI_STOCH", "19a73f4c76caecd4"),
            Map.entry("MACD", "efef3e258b4b3b82"),
            Map.entry("MACD_STOCH_BB", "ff3c0430839cbc17"),
            Map.entry("ORDERBOOK_IMBALANCE", "eee9cdc34fca4a79"),
            Map.entry("RSI", "83e0145ac6c551b1"),
            Map.entry("STOCHASTIC_RSI", "8beb5854f4f36191"),
            Map.entry("SUPERTREND", "79cd729696159b5d"),
            Map.entry("VOLUME_DELTA", "5a3d05f53f5d7d8e"),
            Map.entry("VWAP", "574394f466be8bf4")
    ));

    @Test
    @DisplayName("전략 거동 지문이 v4 기준과 같다 — 다르면 규칙 버전 판단이 필요하다")
    void behaviourFingerprintsAreStable() {
        Map<String, String> actual = computeFingerprints();

        // 실패 시 그대로 복사해 쓸 수 있도록 먼저 출력한다.
        if (!actual.equals(EXPECTED)) {
            System.out.println("=== 현재 거동 지문 (판단 후 EXPECTED 에 반영할 것) ===");
            actual.forEach((k, v) -> System.out.printf("            Map.entry(\"%s\", \"%s\"),%n", k, v));
        }

        assertThat(actual)
                .as("전략 거동이 바뀌었다. 해시를 갈아끼우기 전에 판단할 것 — "
                        + "의도한 변경이면 BACKTEST_RULESET_VERSION(현재 %d) 을 올릴지 결정하고, "
                        + "아니면 회귀이므로 코드를 고칠 것. 위 stdout 에 새 값이 있다.",
                        ExitRuleFormula.BACKTEST_RULESET_VERSION)
                .containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    @Test
    @DisplayName("지문 계산이 결정적이다 — 두 번 돌려 같은 값")
    void fingerprintIsDeterministic() {
        assertThat(computeFingerprints())
                .as("같은 입력에 지문이 달라진다 — 전략이 실행 간 상태를 흘리거나 "
                        + "시계·난수에 의존한다는 뜻이다")
                .isEqualTo(computeFingerprints());
    }

    /**
     * <b>프리셋 실효 중복 감사</b> (Wave 4, 2026-09-22).
     *
     * <p>등록된 프리셋 중 여럿이 <b>이 시나리오에서 구별되지 않는다.</b> 등록은 14종인데
     * 실제로 서로 다른 전략은 그보다 적다는 뜻이고, 그 사실을 모르면
     * "A 와 B 를 비교했다"가 사실은 같은 것을 두 번 잰 것이 된다.
     *
     * <p>이미 두 건이 독립적으로 확인됐다:
     * <ul>
     *   <li>{@code MTF_BTC_STRICT} ≡ {@code MTF_BTC} — 2026-08-24, 코드로 증명
     *       ({@code SupertrendStrictHtfNoOpTest}). strictHtf 두 분기가 도달 불가</li>
     *   <li>{@code REGIME_ROUTER} ≡ {@code MOMENTUM_ICHIMOKU} — 2026-09-22,
     *       5코인 × 3.7년 WF 에서 OOS 거래수·기대값이 소수점까지 일치</li>
     * </ul>
     *
     * <p>⚠️ <b>주장의 범위를 지킬 것.</b> 여기서 해시가 같다는 것은
     * <b>이 시나리오에서 구별되지 않았다</b>는 뜻이지 "수학적으로 동일"의 증명이 아니다.
     * 두 전략이 이 데이터에서 나란히 계속 HOLD 만 해도 같은 해시가 나온다.
     * 동치를 주장하려면 {@code SupertrendStrictHtfNoOpTest} 처럼 도달 불가를 코드로 보여야 한다.
     *
     * <p>이 테스트의 목적은 <b>새 중복이 조용히 생기는 것</b>을 막는 것이다 —
     * 성분 하나를 갈아끼웠는데 결과가 그대로면 그 변경은 아무 일도 하지 않은 것이다.
     */
    @Test
    @DisplayName("프리셋 실효 중복 그룹이 알려진 목록과 같다 — 새 중복은 실패로 드러난다")
    void knownDuplicateGroupsAreUnchanged() {
        Map<String, String> fp = computeFingerprints();

        // 해시 → 그 해시를 공유하는 전략들
        Map<String, java.util.SortedSet<String>> byHash = new TreeMap<>();
        fp.forEach((name, hash) -> byHash
                .computeIfAbsent(hash, h -> new java.util.TreeSet<>())
                .add(name));

        java.util.SortedSet<String> groups = new java.util.TreeSet<>();
        byHash.values().stream()
                .filter(g -> g.size() > 1)
                .forEach(g -> groups.add(String.join(" == ", g)));

        java.util.SortedSet<String> known = new java.util.TreeSet<>(List.of(
                "COMPOSITE_BREAKOUT == COMPOSITE_BREAKOUT_ICHIMOKU == COMPOSITE_MTF_BTC == COMPOSITE_MTF_BTC_STRICT",
                "COMPOSITE_MOMENTUM_ICHIMOKU_V2 == COMPOSITE_MTF_MOMENTUM",
                "COMPOSITE_MTF_CONFIRMED == COMPOSITE_REGIME_ROUTER"
        ));

        assertThat(groups)
                .as("프리셋 중복 구성이 바뀌었다. 새 그룹이 생겼다면 어떤 변경이 "
                        + "아무 효과도 내지 못했다는 뜻이고, 그룹이 사라졌다면 거동이 갈라진 것이다 — "
                        + "어느 쪽이든 의도한 것인지 확인할 것")
                .containsExactlyElementsOf(known);
    }

    // ── 지문 계산 ───────────────────────────────────────────────────────────

    private static Map<String, String> computeFingerprints() {
        CompositePresets.ensureRegistered();
        List<Candle> series = scenario();
        Map<String, String> out = new TreeMap<>();

        for (String name : new TreeMap<>(StrategyRegistry.getAll()).keySet()) {
            if (EXCLUDED.contains(name)) continue;

            // 🔴 반드시 새 인스턴스로 시작한다. 공유 인스턴스를 쓰면 GRID 의 activeLevels 나
            //    MACD_STOCH_BB 의 쿨다운이 앞선 전략의 평가에서 넘어와 지문이 흔들린다
            //    (Wave 1 에서 실행 단위 격리를 도입한 것과 같은 이유).
            Strategy s = StrategyRegistry.hasFactory(name)
                    ? StrategyRegistry.createNew(name)
                    : StrategyRegistry.get(name);

            StringBuilder trace = new StringBuilder();
            // 🔴 **고정폭** 슬라이딩 윈도우 — 매매 엔진과 같은 방식이다
            //    (BacktestEngine.MAX_LOOKBACK · TradingConstants.CANDLE_LOOKBACK 둘 다 500).
            //
            //    첫 판은 subList(0, end) 로 **윈도우를 키웠는데**, 그러면 index 0 이
            //    항상 추세 구간이라 Wilder 평활이 0 에 도달하지 못한다. 그 결과
            //    "무변동 → RSI 중립"(Wave 3-H) 분기가 한 번도 실행되지 않았고,
            //    그 수정을 되돌리는 뮤테이션이 **통과했다.** 잡으려던 바로 그 결함 종류다.
            //
            //    고정폭이면 윈도우가 무변동 구간 안에 통째로 들어갈 수 있고,
            //    "조회 시점마다 결과가 달라지는" 결함(Wave 3-L)도 더 잘 드러난다.
            for (int end = WINDOW; end <= series.size(); end += 3) {
                StrategySignal sig = s.evaluate(series.subList(end - WINDOW, end), Map.of());
                BigDecimal strength = sig.getStrength() == null
                        ? BigDecimal.ZERO
                        : sig.getStrength().setScale(4, RoundingMode.HALF_UP);
                // 🔴 사유(reason)까지 넣는다 — 넣지 않으면 판별력이 사실상 없다.
                //    첫 판은 행동+강도만 해시했는데 **28개 중 23개가 같은 해시**였다.
                //    이 시나리오에서 그 전략들이 전부 HOLD·강도 0 이라 궤적이 구별되지 않았다.
                //    사유에는 계산된 지표값이 찍히므로(RSI 52.31, ADX 18.4 …) 수학이 바뀌면 같이 바뀐다 —
                //    Wave 3 의 L·H 가 정확히 그런 변경이었다.
                //
                //    ⚠️ 대가: **로그 문구만 고쳐도 지문이 바뀐다.** 그건 오탐이 아니라
                //    "판단하라"는 신호로 다룬다 — 실패 메시지가 판단 절차를 안내한다.
                //    판별력 0 인 지문보다 가끔 울리는 지문이 낫다.
                trace.append(sig.getAction()).append(':').append(strength)
                        .append(':').append(sig.getReason()).append('|');
            }
            out.put(name, sha256Short(trace.toString()));
        }
        return out;
    }

    private static String sha256Short(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 추세 → 진동 → <b>완전 무변동</b> → 진동.
     *
     * <p>세 구간이 각각 다른 결함 종류를 자극한다:
     * <ul>
     *   <li>추세·진동 — GRID 의 매수/매도 순환(Wave 3-I), MTF 상위봉 경계(L)</li>
     *   <li><b>무변동</b> — RSI·VolumeDelta 의 경계 처리(Wave 3-H). OHLC 가 전부 같은 봉이다</li>
     * </ul>
     */
    /**
     * 추세 → 진동 → <b>완전 무변동</b> → <b>저변동</b> → 진동. 총 1000봉.
     *
     * <p>네 구간이 각각 다른 결함 종류를 자극한다. <b>구간 길이가 {@link #WINDOW} 보다 길어야
     * 그 구간만으로 채워진 윈도우가 생긴다</b> — 그래야 해당 분기가 실제로 실행된다.
     * <ul>
     *   <li><b>무변동 260봉</b> — RSI·VolumeDelta 의 경계 처리(Wave 3-H).
     *       OHLC 가 전부 같고 거래량도 0 인 봉이다</li>
     *   <li><b>저변동 300봉</b> — GRID 레벨 해제(Wave 3-I).
     *       🔴 이게 없으면 그 수정을 되돌려도 지문이 안 바뀐다 — `RANGE_RESET_THRESHOLD` 가
     *       1% 라서, 진폭이 큰 구간에서는 100봉 레인지가 매 봉 1% 넘게 움직여
     *       `activeLevels` 가 상시 초기화되고 <b>버그가 가려진다.</b>
     *       진폭을 ±0.35% 로 눌러야 레벨이 쌓여 해제 로직이 실제로 실행된다.
     *       (2026-09-22 v4 실측에서 GRID 수정이 메이저에서 무효였던 이유와 같은 기전이다)</li>
     *   <li>추세·진동 — MTF 상위봉 경계(Wave 3-L), 각종 추세 필터</li>
     * </ul>
     */
    private static List<Candle> scenario() {
        List<Candle> out = new ArrayList<>();
        double prev = 1000.0;
        for (int i = 0; i < 1000; i++) {
            double next;
            boolean flat = (i >= 340 && i < 600);     // 260봉 > WINDOW
            boolean lowVol = (i >= 600 && i < 900);   // 300봉 > WINDOW
            if (i < 200) {
                next = 1000.0 + 1.2 * i;                                     // 추세
            } else if (i < 340) {
                next = 1240.0 + 30.0 * Math.sin(i * 2 * Math.PI / 25.0);     // 진동
            } else if (flat) {
                next = 1240.0;                                               // 완전 무변동
            } else if (lowVol) {
                // ±0.35% — 100봉 레인지 변동이 1%(RANGE_RESET_THRESHOLD) 를 넘지 않도록 억제
                next = 1240.0 + 4.3 * Math.sin(i * 2 * Math.PI / 23.0);
            } else {
                next = 1240.0 + 22.0 * Math.sin(i * 2 * Math.PI / 19.0);     // 다시 진동
            }
            double open = flat ? next : prev;
            double wick = lowVol ? 0.4 : 3.0;
            double high = flat ? next : Math.max(open, next) + wick;
            double low  = flat ? next : Math.min(open, next) - wick;
            out.add(Candle.builder()
                    .time(BASE.plusSeconds((long) i * 3600L))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(next))
                    .volume(BigDecimal.valueOf(flat ? 0 : 1000 + (i % 11) * 40))
                    .build());
            prev = next;
        }
        return out;
    }
}
