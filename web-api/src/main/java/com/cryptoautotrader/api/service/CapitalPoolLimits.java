package com.cryptoautotrader.api.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

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
 * <h3>🔴 등록값과 다르면 실자금 진입을 승인하지 않는다 (2026-10-14)</h3>
 *
 * <p>값이 "있다" 만으로는 부족하다 — 오타·잘못 옮긴 값이 있으면 사전 등록과 다른 위험으로 돈이 나간다. 단계 이름은
 * {@link #REGISTERED} 표의 키여야 하고, 세 값이 그 단계의 등록값과 <b>정확히</b> 같아야 한다. 표에 없는 단계 이름
 * (시험 기반의 {@code TEST}·{@code RESTART_TEST} 등)은 <b>시험 런타임에서만</b> 허용한다 — 시험 런타임은 H2 드라이버나
 * 재시작 시험 진입점 클래스가 클래스패스에 있는 경우이고, 둘 다 운영 jar 에는 없다. 3단계는 금액이 아직 등록되지
 * 않았으므로 표에 없다 → 운영에서 쓰면 거절된다.</p>
 *
 * <p>테스트가 한도를 바꿀 수 있게 세터를 둔다 — 운영은 설정으로만 정한다.</p>
 */
@Component
@Slf4j
public class CapitalPoolLimits {

    /** {@code LIVE_ENTRY_PREREG.md} §3 의 사전 등록값 — 총 자본, 포지션당, 동시 보유. */
    static final Map<String, Registered> REGISTERED = Map.of(
            "STAGE_1", new Registered(new BigDecimal("100000"), new BigDecimal("20000"), 3),
            "STAGE_2", new Registered(new BigDecimal("500000"), new BigDecimal("50000"), 5));

    record Registered(BigDecimal totalCapKrw, BigDecimal perPositionCapKrw, int maxConcurrentPositions) {}

    /** 운영 jar 에 없는 클래스 — 있으면 시험 런타임이다. */
    static final String[] TEST_RUNTIME_MARKERS = {
            "org.h2.Driver",
            "com.cryptoautotrader.api.restartharness.RestartHarnessController"};

    /** 단계 이름 ({@code STAGE_1}·{@code STAGE_2}). 등록 표와 대조한다. */
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
        return registrationProblem() == null;
    }

    /** 실자금 진입을 막는 이유 — 없으면 {@code null}. */
    public String registrationProblem() {
        return registrationProblem(testRuntime());
    }

    /** 시험 런타임 여부를 밖에서 받는다 — 운영 판정을 시험 런타임 안에서 검증하기 위해서다. */
    String registrationProblem(boolean testRuntime) {
        if (totalCapKrw == null || totalCapKrw.signum() <= 0
                || perPositionCapKrw == null || perPositionCapKrw.signum() <= 0
                || maxConcurrentPositions == null || maxConcurrentPositions <= 0) {
            return "한도 설정 누락";
        }
        String key = stage == null ? "" : stage.trim();
        Registered r = REGISTERED.get(key);
        if (r == null) {
            return testRuntime ? null : "등록되지 않은 단계 '" + key + "' — 사전 등록된 단계는 " + REGISTERED.keySet();
        }
        if (totalCapKrw.compareTo(r.totalCapKrw()) != 0
                || perPositionCapKrw.compareTo(r.perPositionCapKrw()) != 0
                || maxConcurrentPositions != r.maxConcurrentPositions()) {
            return "단계 " + key + " 의 등록값(총상한=" + r.totalCapKrw() + " 포지션당=" + r.perPositionCapKrw()
                    + " 동시보유=" + r.maxConcurrentPositions() + ")과 다르다";
        }
        return null;
    }

    static boolean testRuntime() {
        for (String c : TEST_RUNTIME_MARKERS) {
            try {
                Class.forName(c, false, CapitalPoolLimits.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                // 다음 표지
            }
        }
        return false;
    }

    /** 기동 시 한 번 크게 알린다 — 거절은 예약 시점마다 하지만, 운영자는 기동 로그에서 먼저 봐야 한다. */
    @PostConstruct
    void reportAtStartup() {
        String problem = registrationProblem();
        if (problem != null) {
            log.error("🔴 공유 풀 한도가 사전 등록과 맞지 않는다 — 실자금 진입·배정을 모두 거절한다: {} ({})", problem, this);
        } else {
            log.info("공유 풀 한도 확인: {}", this);
        }
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
