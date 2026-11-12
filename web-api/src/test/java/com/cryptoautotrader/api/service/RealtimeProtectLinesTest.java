package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 총괄 v1.157 §125 — 첫 발동 판단이 실제 판정 규칙(6자리 반올림·절대가 우선) 그대로인가. */
@DisplayName("v1.157 §125 RealtimeProtectLines — 실제 판정 규칙과 첫 발동")
class RealtimeProtectLinesTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("백분율 손절 반올림 경계 — 평균300·5%: 285.00025 미발동, 285.0001·285 발동")
    void percentRounding() {
        assertThat(RealtimeProtectLines.pnlPct(d("285.00025"), d("300"))).isEqualByComparingTo("-4.9999");
        assertThat(RealtimeProtectLines.stopHit(d("285.00025"), d("300"), null, d("5.00"))).isFalse();
        assertThat(RealtimeProtectLines.pnlPct(d("285.0001"), d("300"))).isEqualByComparingTo("-5.0000");
        assertThat(RealtimeProtectLines.stopHit(d("285.0001"), d("300"), null, d("5.00"))).isTrue();
        assertThat(RealtimeProtectLines.stopHit(d("285"), d("300"), null, d("5.00"))).isTrue();
    }

    @Test
    @DisplayName("절대 손절가가 있으면 백분율을 보지 않는다 — 절대 280: 284 미발동(백분율로는 발동), 280 발동")
    void absoluteFirst() {
        assertThat(RealtimeProtectLines.stopHit(d("284"), d("300"), d("280"), d("5.00"))).isFalse();
        assertThat(RealtimeProtectLines.stopHit(d("280"), d("300"), d("280"), d("5.00"))).isTrue();
        assertThat(RealtimeProtectLines.stopHit(d("1"), d("300"), null, null)).isFalse();
    }

    @Test
    @DisplayName("익절 — 익절가 이상이면 발동, 없으면 미발동")
    void takeProfit() {
        assertThat(RealtimeProtectLines.takeProfitHit(d("330"), d("330"))).isTrue();
        assertThat(RealtimeProtectLines.takeProfitHit(d("329.99"), d("330"))).isFalse();
        assertThat(RealtimeProtectLines.takeProfitHit(d("999"), null)).isFalse();
    }

    @Test
    @DisplayName("첫 발동 — 미발동 근접값은 첫 발동이 아니고 소비하지도 않는다, 이어진 발동 구간은 첫 발동 아님")
    void firstHitSequence() {
        RealtimeProtectLines p = new RealtimeProtectLines();
        RealtimeProtectLines.Collector c = p.collector();
        c.add(d("300"), null, d("5.00"), null);
        c.store("KRW-A");
        assertThat(p.firstHit("KRW-A", d("286"))).isFalse();
        assertThat(p.firstHit("KRW-A", d("285.00025"))).isFalse();
        assertThat(p.firstHit("KRW-A", d("284"))).isTrue();
        assertThat(p.firstHit("KRW-A", d("283"))).isFalse();
        assertThat(p.firstHit("KRW-A", d("290"))).isFalse();
        assertThat(p.firstHit("KRW-A", d("284"))).as("다시 올라갔다 내려오면 다시 첫 발동").isTrue();
    }

    @Test
    @DisplayName("여러 보호선 — 높은 선이 이미 발동 구간이어도 낮은 선의 첫 발동은 점검을 부른다")
    void multipleLines() {
        RealtimeProtectLines p = new RealtimeProtectLines();
        RealtimeProtectLines.Collector c = p.collector();
        c.add(d("300"), d("285"), d("5.00"), null);
        c.add(d("300"), d("280"), d("5.00"), null);
        c.store("KRW-B");
        assertThat(p.firstHit("KRW-B", d("286"))).isFalse();
        assertThat(p.firstHit("KRW-B", d("284"))).isTrue();
        assertThat(p.firstHit("KRW-B", d("282"))).isFalse();
        assertThat(p.firstHit("KRW-B", d("279"))).isTrue();
    }

    @Test
    @DisplayName("직전 유효 시세 없음·보호선 없음 — 첫 시세가 발동이면 첫 발동, 빈 점검 결과는 보호선을 지운다")
    void emptyAndNoPrev() {
        RealtimeProtectLines p = new RealtimeProtectLines();
        RealtimeProtectLines.Collector c = p.collector();
        c.add(d("300"), d("285"), null, null);
        c.store("KRW-C");
        assertThat(p.firstHit("KRW-C", d("280"))).isTrue();
        p.collector().store("KRW-C");
        assertThat(p.firstHit("KRW-C", d("290"))).isFalse();
        assertThat(p.firstHit("KRW-C", d("270"))).isFalse();
    }
}
