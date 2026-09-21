package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CandleDownsampler} 시각 경계 집계 — Wave 3-L 회귀.
 *
 * <p>고치기 전에는 리스트 0번 인덱스부터 {@code factor} 개씩 잘랐다. 호출자가 넘기는 것은
 * 매 캔들 1칸씩 미끄러지는 고정 길이 창이므로, <b>상위봉 구성이 조회 시점에 따라 달라졌다.</b>
 * 아래 첫 테스트가 그 성질을 직접 잰다.
 */
@DisplayName("CandleDownsampler — 시각 경계 집계")
class CandleDownsamplerBoundaryTest {

    private static final long H1 = 3600L;
    /** 2026-01-01 00:00 UTC — H4 경계에 정확히 걸린다. */
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();

    /** H1 캔들 n개. close 는 i 로 두어 어느 봉이 어디서 끝났는지 식별할 수 있게 한다. */
    private static List<Candle> h1(long startEpoch, int n) {
        List<Candle> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal p = BigDecimal.valueOf(100 + i);
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(startEpoch + i * H1))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(BigDecimal.TEN)
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("창 시작 위치가 달라도 같은 구간은 같은 HTF 봉을 낸다")
    void slidingWindow_producesIdenticalBars() {
        List<Candle> full = h1(BASE, 64);

        // 같은 원본에서 시작점만 1칸씩 다르게 자른 네 개의 창 — 실제 호출자가 하는 일이다.
        List<List<Candle>> bars = new ArrayList<>();
        for (int offset = 0; offset < 4; offset++) {
            bars.add(CandleDownsampler.downsample(full.subList(offset, full.size()), 4));
        }

        // 네 결과의 **공통 꼬리**는 완전히 같아야 한다. 창을 언제 잡았느냐가
        // 상위봉 구성에 스며들면 안 된다.
        int common = bars.stream().mapToInt(List::size).min().orElseThrow();
        for (int k = 1; k < bars.size(); k++) {
            List<Candle> a = tail(bars.get(0), common);
            List<Candle> b = tail(bars.get(k), common);
            for (int j = 0; j < common; j++) {
                assertThat(b.get(j).getTime())
                        .as("offset=%d, 봉 %d 의 시각", k, j).isEqualTo(a.get(j).getTime());
                assertThat(b.get(j).getOpen())
                        .as("offset=%d, 봉 %d 의 open", k, j).isEqualByComparingTo(a.get(j).getOpen());
                assertThat(b.get(j).getHigh())
                        .as("offset=%d, 봉 %d 의 high", k, j).isEqualByComparingTo(a.get(j).getHigh());
                assertThat(b.get(j).getLow())
                        .as("offset=%d, 봉 %d 의 low", k, j).isEqualByComparingTo(a.get(j).getLow());
                assertThat(b.get(j).getClose())
                        .as("offset=%d, 봉 %d 의 close", k, j).isEqualByComparingTo(a.get(j).getClose());
            }
        }
    }

    private static List<Candle> tail(List<Candle> l, int n) {
        return l.subList(l.size() - n, l.size());
    }

    @Test
    @DisplayName("HTF 봉의 시각은 경계로 정렬된다 — 그룹 첫 캔들 시각이 아니다")
    void barTime_isBucketBoundary() {
        // 01:00 부터 시작 — 첫 H4 경계(00:00)의 한가운데다.
        List<Candle> htf = CandleDownsampler.downsample(h1(BASE + H1, 16), 4);

        assertThat(htf).isNotEmpty();
        for (Candle c : htf) {
            assertThat(c.getTime().getEpochSecond() % (4 * H1))
                    .as("H4 봉 시각 %s 가 4시간 경계에 정렬돼야 한다", c.getTime())
                    .isZero();
        }
    }

    @Test
    @DisplayName("선두의 잘린 그룹은 버린다 — 창 시작 위치가 결과에 스미지 않게")
    void leadingPartialGroup_dropped() {
        // 02:00 시작 → 00:00 버킷은 2칸만 있다(잘린 봉). 그 봉의 open 은 실제 H4 open 이 아니다.
        List<Candle> htf = CandleDownsampler.downsample(h1(BASE + 2 * H1, 16), 4);

        assertThat(htf.get(0).getTime())
                .as("첫 봉은 04:00 이어야 한다 — 00:00 의 잘린 봉은 버려진다")
                .isEqualTo(Instant.ofEpochSecond(BASE + 4 * H1));
    }

    @Test
    @DisplayName("말미의 형성 중인 그룹은 남긴다 — 잘린 봉이 아니라 진행 중인 봉이다")
    void trailingPartialGroup_kept() {
        // 00:00 부터 18개 → 마지막 버킷(16:00)에 2개만 있다.
        List<Candle> htf = CandleDownsampler.downsample(h1(BASE, 18), 4);

        assertThat(htf).hasSize(5);
        assertThat(htf.get(4).getTime()).isEqualTo(Instant.ofEpochSecond(BASE + 16 * H1));
        assertThat(htf.get(4).getVolume())
                .as("형성 중인 봉은 지금까지 들어온 2개만 합산한다")
                .isEqualByComparingTo(BigDecimal.valueOf(20));
    }

    @Test
    @DisplayName("결측이 맨 앞과 버킷 머리에 있어도 경계가 깨지지 않는다")
    void missingCandles_intervalAndBoundaryHold() {
        // 00:00~15:00 H1 16개에서 **01:00**(첫 간격을 2시간으로 벌린다)과
        // **08:00**(08:00 버킷의 머리 캔들)을 뺀다.
        //
        // · 간격을 "첫 차이"로 잡으면 2시간 → 버킷이 8시간이 되어 봉이 2개로 뭉친다.
        // · 봉 시각을 "그룹 첫 캔들"로 잡으면 08:00 버킷의 시각이 09:00 으로 밀린다.
        // 두 오류 모두 아래 단언에 걸린다.
        List<Candle> src = new ArrayList<>(h1(BASE, 16));
        src.removeIf(c -> {
            long h = (c.getTime().getEpochSecond() - BASE) / H1;
            return h == 1 || h == 8;
        });

        List<Candle> htf = CandleDownsampler.downsample(src, 4);

        assertThat(htf.stream().map(Candle::getTime).toList())
                .as("결측과 무관하게 H4 봉은 00/04/08/12 에 정렬돼야 한다")
                .containsExactly(
                        Instant.ofEpochSecond(BASE),
                        Instant.ofEpochSecond(BASE + 4 * H1),
                        Instant.ofEpochSecond(BASE + 8 * H1),
                        Instant.ofEpochSecond(BASE + 12 * H1));

        assertThat(htf.get(2).getOpen())
                .as("08:00 이 결측이면 09:00 의 open 이 그 봉의 open 이 된다 — 봉 자체는 08:00 이다")
                .isEqualByComparingTo(BigDecimal.valueOf(109));
    }

    @Test
    @DisplayName("OHLCV 집계 — open 은 첫 캔들, close 는 마지막, high/low 는 극값, volume 은 합")
    void ohlcvAggregation() {
        List<Candle> htf = CandleDownsampler.downsample(h1(BASE, 4), 4);

        assertThat(htf).hasSize(1);
        Candle c = htf.get(0);
        assertThat(c.getOpen()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(c.getClose()).isEqualByComparingTo(BigDecimal.valueOf(103));
        assertThat(c.getHigh()).isEqualByComparingTo(BigDecimal.valueOf(104));  // 103+1
        assertThat(c.getLow()).isEqualByComparingTo(BigDecimal.valueOf(99));    // 100-1
        assertThat(c.getVolume()).isEqualByComparingTo(BigDecimal.valueOf(40));
    }
}
