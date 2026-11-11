import java.time.*;
import java.util.*;
public class Probe {
    record RoundKey(String timeframe, Instant candleOpenTime) {}
    public static void main(String[] a) {
        Instant start = Instant.parse("2026-11-11T00:00:00Z");
        String[][] pairs = {{"M15","45"},{"M30","30"},{"H4","-180"}};
        for (String[] p : pairs) {
            int otherFirst = 0, total = 0;
            for (int h = 0; h < 24 * 365; h++) {
                Instant h1Open = start.minus(Duration.ofHours(h));
                Instant otherOpen = h1Open.plus(Duration.ofMinutes(Long.parseLong(p[1])));
                Map<RoundKey, Integer> m = new HashMap<>();
                m.put(new RoundKey("H1", h1Open), 1);
                m.put(new RoundKey(p[0], otherOpen), 2);
                Iterator<RoundKey> it = m.keySet().iterator();
                if (!it.next().timeframe().equals("H1")) otherFirst++;
                total++;
            }
            System.out.println("H1 vs " + p[0] + ": 다른 회차가 먼저 = " + otherFirst + " / " + total);
        }
        // 세 회차(H1 + M15 + DYNAMIC 다른 H1 세션은 같은 키) — H1 + M15 + M30 동시
        int c = 0, n = 0;
        for (int h = 0; h < 24 * 365; h++) {
            Instant h1Open = start.minus(Duration.ofHours(h));
            Map<RoundKey, Integer> m = new HashMap<>();
            m.put(new RoundKey("H1", h1Open), 1);
            m.put(new RoundKey("M15", h1Open.plus(Duration.ofMinutes(45))), 2);
            m.put(new RoundKey("M30", h1Open.plus(Duration.ofMinutes(30))), 3);
            if (!m.keySet().iterator().next().timeframe().equals("H1")) c++;
            n++;
        }
        System.out.println("H1+M15+M30: H1 이 첫째가 아님 = " + c + " / " + n);
    }
}
