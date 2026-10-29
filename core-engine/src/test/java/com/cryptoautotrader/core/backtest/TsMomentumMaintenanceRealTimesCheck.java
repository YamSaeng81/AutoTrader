package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Bar;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Gate;
import com.cryptoautotrader.core.backtest.TsMomentumCalculator.Maintenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 리뷰 39차 교차 확인 — 실제 자료의 <b>시각 열만</b> 읽어 계산기 관문의 최신성 부분을 돌린다.
 *
 * <p>scripts/tsmom/oos_calendar_from_times.py 와 독립된 구현(계산기 {@link TsMomentumCalculator#gate})으로 OOS 매수 대기 창이
 * 통째로 막히는 주를 다시 센다. 가격 칸은 읽지 않는다 — 관측된 시각마다 시가·고가·저가·종가 1, 거래량 1 인 봉을 둔다(급락 관문이
 * 걸리지 않게 하려는 것이고, 빈 시각에는 봉을 두지 않는다). 그래서 이 시험은 최신성 관문만 본다.</p>
 *
 * <p>리뷰 40차: 상한은 막힌 주 수가 아니라 실제 배치로 구한다({@link TsMomentumCalculator#maxNaturalRoundTrips}).
 * 점검 예외는 저장소 고정 입력(인정 구간 CSV·재조회 summary)을 실행기와 같은 {@code Maintenance.load} 로 읽는다.</p>
 *
 * <p>로컬 가격 파일(d:/tmp/stval_btc·stval_eth, 커밋하지 않음)이 없으면 건너뛴다.</p>
 */
class TsMomentumMaintenanceRealTimesCheck {

    static final Path BTC_CSV = Path.of("d:/tmp/stval_btc/btc_h1.csv");
    static final Path ETH_CSV = Path.of("d:/tmp/stval_eth/eth_h1.csv");
    static final Instant S0 = Instant.parse("2023-12-31T15:00:00Z"), E0 = Instant.parse("2026-09-22T00:00:00Z");
    static final Duration H = Duration.ofHours(1), DAY = Duration.ofDays(1);

    static List<Instant> times(Path p) throws IOException {
        List<Instant> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Instant t = Instant.parse(line.substring(0, line.indexOf(',')) + "Z");
            if (!t.isBefore(S0) && t.isBefore(E0)) out.add(t);
        }
        return out;
    }

    /** 저장소 고정 입력(리뷰 40차: 줄바꿈 변환 없이 바이트 그대로) — 시험 작업 폴더가 core-engine 이면 한 단계 위. */
    static Path repo(String rel) {
        Path p = Path.of(rel);
        return Files.exists(p) ? p : Path.of("..").resolve(rel);
    }

    static Maintenance maintenance() throws IOException {
        return Maintenance.load(repo("scripts/tsmom/maintenance_windows_tsmom.csv"), repo(RECHECK_REPO));
    }

    static final String RECHECK_REPO = "scripts/tsmom/maintenance_recheck_summary.json";

    /** 판단 한 회 — 판단 주(월요일)와 매수 창 [t, t+24h) 의 관측된 봉이 모두 관문에 막혔는지. */
    record Week(Instant t, boolean blocked) {}

    /**
     * OOS 구간별 판단 주(시각 기준 이력 있음만) — 실행기 runRolling 행 분할(윈도우 3, IS 0.7).
     * 판단 봉(시작 ≤ t−1h, 24시간 안)과 체결 봉(시작 ≥ t, 24시간 안)이 모두 OOS 행이고 t−28일 판단 봉이 있으면 센다.
     */
    static List<List<Week>> oosWeeks(TsMomentumCalculator c, String coin, List<Instant> ts) {
        int n = ts.size(), win = n / 3, is = (int) (win * 0.7);
        List<List<Week>> out = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            int a = w * win + is, b = Math.min(w * win + win, n);
            List<Week> weeks = new ArrayList<>();
            for (Instant t = TsMomentumCalculator.nextMonday(ts.get(a)); !t.isAfter(ts.get(b - 1)); t = t.plus(Duration.ofDays(7))) {
                int d = idxFloor(ts, t.minus(H)), f = idxCeil(ts, t);
                if (d < a || ts.get(d).isBefore(t.minus(DAY)) || f >= b || !ts.get(f).isBefore(t.plus(DAY))) continue;
                int back = idxFloor(ts, t.minus(Duration.ofDays(28)).minus(H));
                if (back < 0 || ts.get(back).isBefore(t.minus(Duration.ofDays(28)).minus(DAY))) continue;
                boolean blocked = true;
                for (int i = f; i < n && ts.get(i).isBefore(t.plus(DAY)); i++) {
                    if (c.gate(coin, ts.get(i)) == Gate.CLEAR) { blocked = false; break; }
                }
                weeks.add(new Week(t, blocked));
            }
            out.add(weeks);
        }
        return out;
    }

    static List<String> blockedWeeks(List<List<Week>> oos) {
        return oos.stream().flatMap(List::stream).filter(Week::blocked).map(w -> w.t().toString().substring(0, 10)).toList();
    }

    /** OOS 구간별 실제 배치 상한(리뷰 40차) — 막힌 주는 매수 불가, 매도는 관문을 받지 않는다. */
    static List<Integer> bounds(List<List<Week>> oos) {
        return oos.stream().map(ws -> TsMomentumCalculator.maxNaturalRoundTrips(
                ws.stream().map(w -> new TsMomentumCalculator.Decision(!w.blocked(), true)).toList())).toList();
    }

    static int idxFloor(List<Instant> ts, Instant t) {
        int i = java.util.Collections.binarySearch(ts, t);
        return i >= 0 ? i : -i - 2;
    }

    static int idxCeil(List<Instant> ts, Instant t) {
        int i = java.util.Collections.binarySearch(ts, t);
        return i >= 0 ? i : -i - 1;
    }

    static List<Bar> unitBars(List<Instant> ts) {
        return ts.stream().map(t -> new Bar(t, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)).toList();
    }

    @Test
    @DisplayName("실제 시각 열: 예외 없음이면 6주 막힘·실제 배치 상한 6+7+6=19(리뷰 38·39차와 같다) · 확인된 점검 예외를 넣으면 0주·7+7+7=21")
    void realTimesBlockedWeeks() throws IOException {
        assumeTrue(Files.exists(BTC_CSV) && Files.exists(ETH_CSV), "로컬 자료 없음");
        List<Instant> btc = times(BTC_CSV), eth = times(ETH_CSV);
        Map<String, List<Bar>> d = new TreeMap<>();
        d.put("KRW-BTC", unitBars(btc));
        d.put("KRW-ETH", unitBars(eth));
        var none = new TsMomentumCalculator(d);
        var with = new TsMomentumCalculator(d, maintenance());
        List<String> expected = List.of("2024-10-14", "2024-10-21", "2024-10-28", "2026-07-06", "2026-07-13", "2026-07-20");
        for (var e : Map.of("KRW-BTC", btc, "KRW-ETH", eth).entrySet()) {
            var no = oosWeeks(none, e.getKey(), e.getValue());
            var yes = oosWeeks(with, e.getKey(), e.getValue());
            assertThat(no).extracting(List::size).containsExactly(14, 14, 15);
            assertThat(blockedWeeks(no)).containsExactlyElementsOf(expected);
            assertThat(bounds(no)).containsExactly(6, 7, 6);
            assertThat(blockedWeeks(yes)).isEmpty();
            assertThat(bounds(yes)).containsExactly(7, 7, 7);
        }
    }
}
