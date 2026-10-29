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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 리뷰 39차 교차 확인 — 실제 자료의 <b>시각 열만</b> 읽어 계산기 관문의 최신성 부분을 돌린다.
 *
 * <p>scripts/tsmom/oos_calendar_from_times.py 와 독립된 구현(계산기 {@link TsMomentumCalculator#gate})으로 OOS 매수 대기 창이
 * 통째로 막히는 주를 다시 센다. 가격 칸은 읽지 않는다 — 관측된 시각마다 시가·고가·저가·종가 1, 거래량 1 인 봉을 둔다(급락 관문이
 * 걸리지 않게 하려는 것이고, 빈 시각에는 봉을 두지 않는다). 그래서 이 시험은 최신성 관문만 본다.</p>
 *
 * <p>로컬 자료(d:/tmp/stval_btc·stval_eth·stval_maint, 커밋하지 않음)가 없으면 건너뛴다.</p>
 */
class TsMomentumMaintenanceRealTimesCheck {

    static final Path BTC_CSV = Path.of("d:/tmp/stval_btc/btc_h1.csv");
    static final Path ETH_CSV = Path.of("d:/tmp/stval_eth/eth_h1.csv");
    static final Path RECHECK = Path.of("d:/tmp/stval_maint/recheck/summary.json");
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

    static Path windowsCsv() {
        Path p = Path.of("scripts/tsmom/maintenance_windows_tsmom.csv");
        return Files.exists(p) ? p : Path.of("../scripts/tsmom/maintenance_windows_tsmom.csv");
    }

    static Maintenance maintenance() throws IOException {
        List<Maintenance.Window> w = new ArrayList<>();
        List<String> lines = Files.readAllLines(windowsCsv(), StandardCharsets.UTF_8);
        List<String> head = List.of(lines.get(0).split(","));
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",");
            w.add(new Maintenance.Window(c[0], Instant.parse(c[head.indexOf("starts_at_utc")]), Instant.parse(c[head.indexOf("ends_at_utc")])));
        }
        Map<String, Set<Instant>> absent = new HashMap<>();
        String json = Files.readString(RECHECK, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"market\":\\s*\"([A-Z-]+)\"[^\\]]*?\"absent\":\\s*\\[([^\\]]*)\\]", Pattern.DOTALL).matcher(json);
        while (m.find()) {
            Matcher a = Pattern.compile("\"(\\d{4}-\\d{2}-\\d{2}T\\d{2})\"").matcher(m.group(2));
            while (a.find()) absent.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(Instant.parse(a.group(1) + ":00:00Z"));
        }
        return new Maintenance(w, absent);
    }

    /** OOS 매수 대기 창 [t, t+24h) 의 관측된 봉이 모두 관문에 막히는 판단 주(시각 기준 이력 있음만). */
    static List<String> blockedWeeks(TsMomentumCalculator c, String coin, List<Instant> ts) {
        int n = ts.size(), win = n / 3, is = (int) (win * 0.7);
        List<String> out = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            int a = w * win + is, b = Math.min(w * win + win, n);
            for (Instant t = TsMomentumCalculator.nextMonday(ts.get(a)); !t.isAfter(ts.get(b - 1)); t = t.plus(Duration.ofDays(7))) {
                int d = idxFloor(ts, t.minus(H)), f = idxCeil(ts, t);
                if (d < a || ts.get(d).isBefore(t.minus(DAY)) || f >= b || !ts.get(f).isBefore(t.plus(DAY))) continue;
                int back = idxFloor(ts, t.minus(Duration.ofDays(28)).minus(H));
                if (back < 0 || ts.get(back).isBefore(t.minus(Duration.ofDays(28)).minus(DAY))) continue;
                boolean blocked = true;
                for (int i = f; i < n && ts.get(i).isBefore(t.plus(DAY)); i++) {
                    if (c.gate(coin, ts.get(i)) == Gate.CLEAR) { blocked = false; break; }
                }
                if (blocked) out.add(t.toString().substring(0, 10));
            }
        }
        return out;
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
    @DisplayName("실제 시각 열: 예외 없음이면 6주 막힘(상한 19, 리뷰 38차와 같다) · 확인된 점검 예외를 넣으면 0주(상한 21)")
    void realTimesBlockedWeeks() throws IOException {
        assumeTrue(Files.exists(BTC_CSV) && Files.exists(ETH_CSV) && Files.exists(RECHECK), "로컬 자료 없음");
        List<Instant> btc = times(BTC_CSV), eth = times(ETH_CSV);
        Map<String, List<Bar>> d = new TreeMap<>();
        d.put("KRW-BTC", unitBars(btc));
        d.put("KRW-ETH", unitBars(eth));
        var none = new TsMomentumCalculator(d);
        var with = new TsMomentumCalculator(d, maintenance());
        List<String> expected = List.of("2024-10-14", "2024-10-21", "2024-10-28", "2026-07-06", "2026-07-13", "2026-07-20");
        assertThat(blockedWeeks(none, "KRW-BTC", btc)).containsExactlyElementsOf(expected);
        assertThat(blockedWeeks(none, "KRW-ETH", eth)).containsExactlyElementsOf(expected);
        assertThat(blockedWeeks(with, "KRW-BTC", btc)).isEmpty();
        assertThat(blockedWeeks(with, "KRW-ETH", eth)).isEmpty();
    }
}
