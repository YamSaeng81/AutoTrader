package com.cryptoautotrader.api.util;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 틱 캔들 캐시 — 2026-09-08 신설 (세 엔진 공용화).
 *
 * <h3>왜 필요한가</h3>
 * <p>PAPER 에만 있던 캐시다. 08-19 정합성 감사가 "한 엔진에만 적용하고 나머지를 잊는다" 목록에
 * 올려 둔 마지막 항목이었다. LIVE·DYNAMIC 은 세션마다 같은 {@code (코인, 타임프레임)} 을
 * 다시 조회했고, 동적 세션은 워치리스트를 세션마다 통째로 훑어 낭비가 세션 수에 비례했다.</p>
 *
 * <p><b>정확성이 걸린 부분</b>은 스코프 종료다. 스코프가 남으면 다음 틱이 옛 캔들로 매매한다 —
 * 성능 개선이 조용한 오작동으로 바뀌는 지점이라 여기를 집중적으로 본다.</p>
 */
class TickCandleCacheTest {

    @AfterEach
    void cleanup() {
        TickCandleCache.end();
    }

    private static List<Candle> candles(String tag) {
        return List.of(Candle.builder()
                .time(Instant.parse("2026-09-08T00:00:00Z"))
                .open(BigDecimal.ONE).high(BigDecimal.ONE)
                .low(BigDecimal.ONE).close(new BigDecimal(tag.length()))
                .volume(BigDecimal.ONE)
                .build());
    }

    @Test
    @DisplayName("한 틱 안에서 같은 (코인,타임프레임) 은 한 번만 조회한다")
    void 틱_안에서는_한번만_조회한다() {
        AtomicInteger loads = new AtomicInteger();
        TickCandleCache.begin();

        for (int i = 0; i < 5; i++) {
            TickCandleCache.get("KRW-BTC", "H1", () -> {
                loads.incrementAndGet();
                return candles("btc");
            });
        }

        assertThat(loads.get())
                .as("세션 5개가 같은 조합을 봐도 조회는 1회여야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("코인이나 타임프레임이 다르면 각각 조회한다")
    void 조합이_다르면_따로_조회한다() {
        AtomicInteger loads = new AtomicInteger();
        TickCandleCache.begin();

        TickCandleCache.get("KRW-BTC", "H1", () -> { loads.incrementAndGet(); return candles("a"); });
        TickCandleCache.get("KRW-BTC", "M15", () -> { loads.incrementAndGet(); return candles("b"); });
        TickCandleCache.get("KRW-ETH", "H1", () -> { loads.incrementAndGet(); return candles("c"); });

        assertThat(loads.get()).isEqualTo(3);
        assertThat(TickCandleCache.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("틱이 끝나면 캐시가 사라진다 — 다음 틱이 옛 캔들을 보면 안 된다 (핵심 회귀)")
    void 틱이_끝나면_캐시가_사라진다() {
        AtomicInteger loads = new AtomicInteger();

        TickCandleCache.begin();
        TickCandleCache.get("KRW-BTC", "H1", () -> { loads.incrementAndGet(); return candles("old"); });
        TickCandleCache.end();

        TickCandleCache.begin();
        TickCandleCache.get("KRW-BTC", "H1", () -> { loads.incrementAndGet(); return candles("new"); });

        assertThat(loads.get())
                .as("스코프가 남아 있으면 다음 틱이 지난 틱의 캔들로 매매한다 — "
                        + "성능 개선이 조용한 오작동이 되는 지점이다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("스코프 밖에서는 매번 조회한다 — 웹소켓 콜백 등 틱이 아닌 경로")
    void 스코프_밖에서는_캐시하지_않는다() {
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            TickCandleCache.get("KRW-BTC", "H1", () -> { loads.incrementAndGet(); return candles("x"); });
        }

        assertThat(loads.get())
                .as("캐시가 없으면 종전 동작과 같아야 한다 — 조회 횟수만 줄이는 변경이다")
                .isEqualTo(3);
        assertThat(TickCandleCache.size()).isEqualTo(-1);
    }

    @Test
    @DisplayName("스코프는 스레드마다 독립이다 — 엔진 셋이 각자 스케줄러에서 돈다")
    void 스코프는_스레드마다_독립이다() throws InterruptedException {
        TickCandleCache.begin();
        TickCandleCache.get("KRW-BTC", "H1", () -> candles("main"));

        AtomicInteger other = new AtomicInteger(-99);
        Thread t = new Thread(() -> other.set(TickCandleCache.size()));
        t.start();
        t.join();

        assertThat(other.get())
                .as("다른 스레드가 이 스코프를 보면 엔진끼리 캔들이 섞인다")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("가드: 세 엔진이 모두 이 캐시를 쓴다 — 한 곳만 빠지면 그 엔진만 낭비한다")
    void 세_엔진이_캐시를_쓴다() throws IOException {
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            String src = Files.readString(Path.of(
                    "src/main/java/com/cryptoautotrader/api/service/" + engine + "TradingService.java"));
            assertThat(src)
                    .as("%sTradingService 가 틱 스코프를 열지 않는다", engine)
                    .contains("TickCandleCache.begin()");
            assertThat(src)
                    .as("%sTradingService 가 틱 스코프를 닫지 않는다 — 다음 틱이 옛 캔들을 본다", engine)
                    .contains("TickCandleCache.end()");
            assertThat(src)
                    .as("%sTradingService 가 캐시를 경유해 조회하지 않는다", engine)
                    .contains("TickCandleCache.get(");
        }
    }
}
