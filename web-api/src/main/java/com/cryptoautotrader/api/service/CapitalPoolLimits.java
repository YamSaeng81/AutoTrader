package com.cryptoautotrader.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * <b>사전 등록한 공유 풀 한도</b> (2026-10-12) — {@code LIVE_ENTRY_PREREG.md} §3·§5.
 *
 * <h3>🔴 계좌 잔고와 별개다</h3>
 *
 * <p>계좌 잔고는 "거래소에 실제로 있는 돈" 이고, 이 한도는 "<b>이 단계에서 쓰기로 등록한 돈</b>" 이다.
 * 계좌에 다른 자금이 있어도 등록한 단계 자본·포지션당 금액·동시 보유를 넘으면 안 된다. 그래서 두
 * 검사를 모두 하고, 더 좁은 쪽이 승인을 정한다.</p>
 *
 * <table>
 *   <tr><th></th><th>1단계</th><th>2단계</th></tr>
 *   <tr><td>총 자본 상한</td><td>100,000</td><td>500,000</td></tr>
 *   <tr><td>포지션당</td><td>20,000</td><td>50,000</td></tr>
 *   <tr><td>동시 보유</td><td>≤ 3</td><td>≤ 5</td></tr>
 * </table>
 *
 * <h3>🔴 등록되지 않으면 실자금 진입을 승인하지 않는다</h3>
 *
 * <p>기본값을 두지 않는다. 기본값이 있으면 "등록하지 않았는데 한도가 걸려 있는 줄 아는" 상태가 된다.
 * 셋 중 하나라도 없으면 {@link #isRegistered()} 가 거짓이고 예약은 거절된다.</p>
 *
 * <p>테스트가 한도를 바꿀 수 있게 세터를 둔다 — 운영은 설정으로만 정한다.</p>
 */
@Component
public class CapitalPoolLimits {

    /** 사람이 읽는 단계 이름 (예: {@code STAGE_1}). 판정에는 쓰지 않는다. */
    @Value("${trading.capital-pool.stage:}")
    private String stage;

    /** 단계 총 자본 상한 — 열린 실자금 포지션 배정액 합 + 신규 ≤ 이 값. */
    @Value("${trading.capital-pool.total-cap-krw:#{null}}")
    private BigDecimal totalCapKrw;

    /** 포지션당 상한 — 한 번의 진입 금액 ≤ 이 값. */
    @Value("${trading.capital-pool.per-position-cap-krw:#{null}}")
    private BigDecimal perPositionCapKrw;

    /** 동시 보유 상한 — 열린 실자금 포지션(체결 전 예약 포함) 수 + 1 ≤ 이 값. */
    @Value("${trading.capital-pool.max-concurrent-positions:#{null}}")
    private Integer maxConcurrentPositions;

    public boolean isRegistered() {
        return totalCapKrw != null && totalCapKrw.signum() > 0
                && perPositionCapKrw != null && perPositionCapKrw.signum() > 0
                && maxConcurrentPositions != null && maxConcurrentPositions > 0;
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public BigDecimal getTotalCapKrw() { return totalCapKrw; }
    public void setTotalCapKrw(BigDecimal v) { this.totalCapKrw = v; }

    public BigDecimal getPerPositionCapKrw() { return perPositionCapKrw; }
    public void setPerPositionCapKrw(BigDecimal v) { this.perPositionCapKrw = v; }

    public Integer getMaxConcurrentPositions() { return maxConcurrentPositions; }
    public void setMaxConcurrentPositions(Integer v) { this.maxConcurrentPositions = v; }

    @Override
    public String toString() {
        return String.format("단계=%s 총상한=%s 포지션당=%s 동시보유=%s",
                stage, totalCapKrw, perPositionCapKrw, maxConcurrentPositions);
    }
}
