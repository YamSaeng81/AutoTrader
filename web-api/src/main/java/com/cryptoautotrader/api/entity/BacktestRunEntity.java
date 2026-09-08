package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "backtest_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "coin_pair", nullable = false, length = 20)
    private String coinPair;

    @Column(name = "timeframe", nullable = false, length = 10)
    private String timeframe;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "end_date", nullable = false)
    private Instant endDate;

    @Column(name = "initial_capital", nullable = false)
    private BigDecimal initialCapital;

    @Column(name = "slippage_pct")
    private BigDecimal slippagePct;

    @Column(name = "fee_pct")
    private BigDecimal feePct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fill_simulation_json", columnDefinition = "jsonb")
    private Map<String, Object> fillSimulationJson;

    @Column(name = "is_walk_forward")
    private Boolean isWalkForward;

    /**
     * 이 실행이 어떤 청산 규칙으로 돌았는지 — {@code ExitRuleFormula.EXIT_RULES_VERSION} (V78, 2026-09-08).
     *
     * <p><b>NULL = 09-08 이전</b>(백테스트 SL 5% 고정 · TP 10% · time stop 없음 · 손실 구간 SL 조임).
     * {@code WalkForwardValidationGate} 는 현재 버전 미만을 <b>근거로 인정하지 않는다</b> —
     * 게이트가 조합별 최신 실행만 보기 때문에, 이 표시가 없으면 재실행되지 않은 조합이
     * 수정 전 판정으로 실자본을 계속 승인한다.</p>
     */
    @Column(name = "exit_rules_version")
    private Integer exitRulesVersion;

    @Column(name = "wf_in_sample")
    private Instant wfInSample;

    @Column(name = "wf_out_sample")
    private Instant wfOutSample;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wf_result_json", columnDefinition = "jsonb")
    private Map<String, Object> wfResultJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (initialCapital == null) initialCapital = new BigDecimal("10000000");
    }
}
