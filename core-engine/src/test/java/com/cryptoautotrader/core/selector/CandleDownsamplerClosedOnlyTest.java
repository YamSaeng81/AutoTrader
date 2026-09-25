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
 * {@link CandleDownsampler#downsampleClosedOnly} — 전향 검증 팔 A 전용 경로.
 *
 * <p>🔴 <b>왜 이 테스트가 따로 필요한가</b><br>
 * {@code StrategyBehaviourFingerprintTest} 의 합성 시나리오에서는
 * {@code COMPOSITE_MTF_MOMENTUM_CLOSED} 가 {@code COMPOSITE_MTF_MOMENTUM} 과
 * <b>같은 해시</b>를 낸다 — 그 트레이스에서 Supertrend 필터가 한 번도 발화하지 않기 때문이다.
 * 즉 지문 테스트는 이 코드가 실제로 무엇을 하는지 검증하지 못한다. 그래서 여기서 직접 잰다.
 *
 * <p>확인하는 것
 * <ol>
 *   <li>말미가 <b>미완결</b>이면 상위봉 하나가 줄어든다</li>
 *   <li>말미가 <b>완결</b>이면 {@link CandleDownsampler#downsample} 과 완전히 같다</li>
 *   <li>🔴 {@code downsample} 자체의 동작은 바뀌지 않았다 (기존 전략 보호)</li>
 * </ol>
 */
class CandleDownsamplerClosedOnlyTest {

    private static final long H = 3600L;

    /**
     * 비교용 지문 — {@link Candle} 에는 {@code equals()} 가 없다(Lombok @Builder 만 붙어 있어
     * 객체 동일성 비교가 된다). 그래서 시각·시가·고가·저가·종가를 문자열로 뽑아 비교한다.
     */
    private static List<String> sig(List<Candle> cs) {
        return cs.stream().map(c -> c.getTime().getEpochSecond() + "|" + c.getOpen()
                + "|" + c.getHigh() + "|" + c.getLow() + "|" + c.getClose()).toList();
    }

    /** {@code n} 개의 H1 캔들 — 시각은 epoch 0 부터 1시간 간격. */
    private static List<Candle> h1(int n) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal p = BigDecimal.valueOf(100 + i);
            out.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * H))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE))
                    .close(p).volume(BigDecimal.TEN).build());
        }
        return out;
    }

    @Test
    @DisplayName("말미가 미완결이면 상위봉 하나가 줄어든다")
    void dropsFormingBar() {
        // 9개 = H4 로 (0-3)(4-7) 완결 2개 + (8) 형성 중 1개
        List<Candle> c = h1(9);
        List<Candle> all = CandleDownsampler.downsample(c, 4);
        List<Candle> closed = CandleDownsampler.downsampleClosedOnly(c, 4);

        assertThat(all).as("downsample 은 형성 중 봉을 남긴다").hasSize(3);
        assertThat(closed).as("downsampleClosedOnly 는 형성 중 봉을 버린다").hasSize(2);
        assertThat(sig(closed)).isEqualTo(sig(all.subList(0, 2)));
        // 마지막 완결 봉의 종가는 8번째 캔들(index 7) 의 종가
        assertThat(closed.get(1).getClose()).isEqualByComparingTo(c.get(7).getClose());
    }

    @Test
    @DisplayName("말미가 완결이면 downsample 과 완전히 같다")
    void identicalWhenLastBarComplete() {
        List<Candle> c = h1(8);          // (0-3)(4-7) — 둘 다 완결
        assertThat(sig(CandleDownsampler.downsampleClosedOnly(c, 4)))
                .isEqualTo(sig(CandleDownsampler.downsample(c, 4)));
    }

    @Test
    @DisplayName("말미 그룹에 결측이 있으면 완결로 보지 않는다")
    void missingCandleInLastGroupIsNotComplete() {
        // (0-3) 완결, 그다음 그룹은 4,5,6 만 있고 7(마지막 슬롯)이 없다
        List<Candle> c = new ArrayList<>(h1(7));
        List<Candle> all = CandleDownsampler.downsample(c, 4);
        assertThat(all).hasSize(2);
        assertThat(CandleDownsampler.downsampleClosedOnly(c, 4))
                .as("마지막 슬롯이 비면 미완결로 본다").hasSize(1);
    }

    @Test
    @DisplayName("🔴 downsample 자체는 바뀌지 않았다 — 형성 중 봉을 계속 남긴다")
    void downsampleStillKeepsFormingBar() {
        // 이 단정이 깨지면 기존 전략 전부의 거동이 바뀐 것이다.
        List<Candle> c = h1(9);
        List<Candle> all = CandleDownsampler.downsample(c, 4);
        assertThat(all).hasSize(3);
        assertThat(all.get(2).getTime()).isEqualTo(Instant.ofEpochSecond(8 * H));
        assertThat(all.get(2).getClose()).isEqualByComparingTo(c.get(8).getClose());
    }

    @Test
    @DisplayName("팔 A 프리셋이 등록돼 있고 기본 프리셋과 별개 객체다")
    void armAPresetRegistered() {
        var f = CompositePresets.factories();
        assertThat(f).containsKey("COMPOSITE_MTF_MOMENTUM_CLOSED");
        assertThat(f).containsKey("COMPOSITE_MTF_MOMENTUM");
        assertThat(f.get("COMPOSITE_MTF_MOMENTUM_CLOSED").get().getName())
                .isEqualTo("COMPOSITE_MTF_MOMENTUM_CLOSED");
        // 팔별 세션이 서로 상태를 공유하지 않으려면 매번 새 인스턴스여야 한다
        assertThat(f.get("COMPOSITE_MTF_MOMENTUM_CLOSED").get())
                .isNotSameAs(f.get("COMPOSITE_MTF_MOMENTUM_CLOSED").get());
    }
}
