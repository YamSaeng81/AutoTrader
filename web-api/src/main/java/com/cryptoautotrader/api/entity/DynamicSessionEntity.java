package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 동적 멀티코인 세션 엔티티.
 *
 * <p>종목 고정 없이 거래량 상위 코인을 실시간 필터링해 매매하는 세션.
 *
 * <h3>상태 머신</h3>
 * <pre>
 * status=RUNNING + scanState=SCANNING
 *   → BUY 신호 발생 → 매수 실행
 *   → scanState=POSITION_MONITORING, currentCoinPair=매수코인
 *
 * status=RUNNING + scanState=POSITION_MONITORING
 *   → SELL 신호 / SL / TP → 매도 실행
 *   → scanState=SCANNING, currentCoinPair=null
 * </pre>
 */
@Entity
@Table(name = "dynamic_session")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicSessionEntity {

    /**
     * {@code maxHoldHours} 기본값 — 세션 생성 시 요청에 값이 없으면 이 값이 적용된다.
     *
     * <p><b>2026-08-18: 0(비활성) → 24 로 복원</b>. 07-31에 임시로 꺼둔 사유였던
     * 매도 후처리 롤백 P0(주문은 체결됐는데 포지션이 OPEN으로 남아 매 틱마다 매도를
     * 재시도하는 루프)는 08-03에 해소됐고, 이후 {@code reattachRolledBackPosition()}
     * ·CLOSING 타임아웃 8분 분리(D-5)·부분체결 SELL 승격(D-3)까지 보강됐다.
     * 운영 DB {@code daily_health_snapshot} 기준 {@code ghost_position_count}가
     * 08-07~08-18 11일 연속 0 이라 재발 징후도 없다.</p>
     *
     * <p>끄고 있는 동안 정확히 V62가 예고한 고착이 재발했다 — LIVE 198 / DYNAMIC 48 /
     * DYN_PAPER 49 가 같은 KRW-XRP 를 <b>259시간(10.8일)</b> 물고 있었고, SL(−5%)·TP(+10%)
     * 어느 쪽도 닿지 않아 세션당 자본의 80%가 잠겼다(2026-08-18 운영 DB 분석).</p>
     *
     * <p>24시간인 이유: 동적 세션은 워치리스트를 순회하며 기회를 찾는 구조라, 하루가 지나도
     * 방향이 나오지 않은 포지션은 자본 회전을 막는 기회비용이 손실보다 크다. 08-07~08-18
     * 실측 청산 8건의 보유시간 중앙값은 16시간으로, 정상 매매는 24시간에 걸리지 않는다.</p>
     */
    public static final int DEFAULT_MAX_HOLD_HOURS = 24;

    /** {@link #tradingMode} 기본값 — 미지정 시 실거래. */
    public static final String DEFAULT_TRADING_MODE = "REAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(name = "initial_capital", nullable = false, precision = 20, scale = 2)
    private BigDecimal initialCapital;

    /**
     * REAL(실거래) | PAPER(모의) — (V67, 2026-08-06).
     *
     * <p>PAPER는 전략 평가·진입 게이트 5종·SL/TP·time stop·워치리스트 스캔을 REAL과 100% 공유하되,
     * 체결만 실거래소 대신 슬리피지·수수료 시뮬레이션으로 처리한다({@link #isPaper()} 참조).
     * {@code position}/{@code "order"}의 {@code session_kind}가 REAL="DYNAMIC", PAPER="DYN_PAPER"로
     * 분리되어, 실거래 reconcile 스케줄러 4종이 PAPER 데이터를 절대 건드리지 않는다.</p>
     */
    @Builder.Default
    @Column(name = "trading_mode", nullable = false, length = 10)
    private String tradingMode = DEFAULT_TRADING_MODE;

    @Column(name = "available_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal availableKrw;

    @Column(name = "total_asset_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal totalAssetKrw;

    @Column(name = "invest_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal investRatio;

    @Column(name = "stop_loss_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal stopLossPct;

    /** CREATED / RUNNING / STOPPED / EMERGENCY_STOPPED */
    @Column(nullable = false, length = 20)
    private String status;

    /** SCANNING / POSITION_MONITORING */
    @Column(name = "scan_state", nullable = false, length = 25)
    private String scanState;

    /** POSITION_MONITORING 상태일 때 보유 종목 */
    @Column(name = "current_coin_pair", length = 20)
    private String currentCoinPair;

    /** 현재 보유 포지션 ID */
    @Column(name = "current_position_id")
    private Long currentPositionId;

    /** 거래량 상위 후보 추출 수 (기본 30) */
    @Column(name = "max_candidate_size", nullable = false)
    private Integer maxCandidateSize;

    /** 필터 통과 후 최종 감시 종목 수 (기본 10) */
    @Column(name = "target_watch_size", nullable = false)
    private Integer targetWatchSize;

    /** ATR(14)/현재가 최소 비율 % (기본 0.5) */
    @Column(name = "min_atr_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal minAtrPct;

    /** 호가 스프레드 최대 비율 % (기본 0.1) */
    @Column(name = "max_spread_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal maxSpreadPct;

    /** 워치리스트 재필터링 주기 (분, 기본 60) */
    @Column(name = "watchlist_refresh_min", nullable = false)
    private Integer watchlistRefreshMin;

    /**
     * 최대 보유시간 (시) — 초과 시 손익과 무관하게 시장가 청산(time stop). 0 이하면 비활성.
     *
     * <p>SL/TP 는 가격 기반이라 저변동 종목(스테이블코인 등)에서는 영원히 도달하지 않는다.
     * 2026-07-31 세션 38 KRW-RLUSD 가 42시간 고착돼 자본이 잠긴 사례에서 도입.</p>
     *
     * <p>기본값은 {@link #DEFAULT_MAX_HOLD_HOURS} 참조 — <b>현재 0(비활성)</b>.</p>
     */
    @Builder.Default
    @Column(name = "max_hold_hours", nullable = false)
    private Integer maxHoldHours = DEFAULT_MAX_HOLD_HOURS;

    /** 캐시된 워치리스트 JSON (예: ["KRW-BTC","KRW-ETH",...]) */
    @Column(name = "watchlist_json", columnDefinition = "TEXT")
    private String watchlistJson;

    @Column(name = "watchlist_refreshed_at")
    private Instant watchlistRefreshedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "mdd_peak_capital", precision = 20, scale = 2)
    private BigDecimal mddPeakCapital;

    /** 서킷 브레이커 발동 시각 (MDD 초과 / 연속 손실 한도 초과) */
    @Column(name = "circuit_breaker_triggered_at")
    private Instant circuitBreakerTriggeredAt;

    /** 서킷 브레이커 발동 사유 */
    @Column(name = "circuit_breaker_reason", length = 500)
    private String circuitBreakerReason;

    /**
     * 서킷 브레이커 누적 발동 횟수 (V69) — kill criteria {@code CB_REPEAT} 판정용.
     * 사유는 {@link LiveTradingSessionEntity#getCircuitBreakerTripCount()} 주석 참조.
     */
    @Column(name = "circuit_breaker_trip_count", nullable = false)
    private Integer circuitBreakerTripCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (status == null)    status    = "CREATED";
        if (scanState == null) scanState = "SCANNING";
        if (tradingMode == null) tradingMode = DEFAULT_TRADING_MODE;
        if (circuitBreakerTripCount == null) circuitBreakerTripCount = 0;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    // ── getters / setters ──────────────────────────────────────────

    public Long getId() { return id; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String v) { this.strategyType = v; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String v) { this.timeframe = v; }

    public BigDecimal getInitialCapital() { return initialCapital; }
    public void setInitialCapital(BigDecimal v) { this.initialCapital = v; }

    public String getTradingMode() { return tradingMode; }
    public void setTradingMode(String v) { this.tradingMode = v; }

    /** true면 모의(PAPER) 세션 — 체결이 실거래소를 거치지 않고 시뮬레이션된다. */
    public boolean isPaper() { return "PAPER".equals(tradingMode); }

    public BigDecimal getAvailableKrw() { return availableKrw; }
    public void setAvailableKrw(BigDecimal v) { this.availableKrw = v; }

    public BigDecimal getTotalAssetKrw() { return totalAssetKrw; }
    public void setTotalAssetKrw(BigDecimal v) { this.totalAssetKrw = v; }

    public BigDecimal getInvestRatio() { return investRatio; }
    public void setInvestRatio(BigDecimal v) { this.investRatio = v; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal v) { this.stopLossPct = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getScanState() { return scanState; }
    public void setScanState(String v) { this.scanState = v; }

    public String getCurrentCoinPair() { return currentCoinPair; }
    public void setCurrentCoinPair(String v) { this.currentCoinPair = v; }

    public Long getCurrentPositionId() { return currentPositionId; }
    public void setCurrentPositionId(Long v) { this.currentPositionId = v; }

    public Integer getMaxCandidateSize() { return maxCandidateSize; }
    public void setMaxCandidateSize(Integer v) { this.maxCandidateSize = v; }

    public Integer getTargetWatchSize() { return targetWatchSize; }
    public void setTargetWatchSize(Integer v) { this.targetWatchSize = v; }

    public BigDecimal getMinAtrPct() { return minAtrPct; }
    public void setMinAtrPct(BigDecimal v) { this.minAtrPct = v; }

    public BigDecimal getMaxSpreadPct() { return maxSpreadPct; }
    public void setMaxSpreadPct(BigDecimal v) { this.maxSpreadPct = v; }

    public Integer getWatchlistRefreshMin() { return watchlistRefreshMin; }
    public void setWatchlistRefreshMin(Integer v) { this.watchlistRefreshMin = v; }

    public Integer getMaxHoldHours() { return maxHoldHours; }
    public void setMaxHoldHours(Integer v) { this.maxHoldHours = v; }

    public String getWatchlistJson() { return watchlistJson; }
    public void setWatchlistJson(String v) { this.watchlistJson = v; }

    public Instant getWatchlistRefreshedAt() { return watchlistRefreshedAt; }
    public void setWatchlistRefreshedAt(Instant v) { this.watchlistRefreshedAt = v; }

    public Long getVersion() { return version; }
    public void setVersion(Long v) { this.version = v; }

    public BigDecimal getMddPeakCapital() { return mddPeakCapital; }
    public void setMddPeakCapital(BigDecimal v) { this.mddPeakCapital = v; }

    public Instant getCircuitBreakerTriggeredAt() { return circuitBreakerTriggeredAt; }
    public void setCircuitBreakerTriggeredAt(Instant v) { this.circuitBreakerTriggeredAt = v; }

    public String getCircuitBreakerReason() { return circuitBreakerReason; }
    public void setCircuitBreakerReason(String v) { this.circuitBreakerReason = v; }

    /** V69 이전에 만들어진 행은 null 일 수 있으므로 0 으로 읽는다. */
    public int getCircuitBreakerTripCount() {
        return circuitBreakerTripCount == null ? 0 : circuitBreakerTripCount;
    }
    public void setCircuitBreakerTripCount(Integer v) { this.circuitBreakerTripCount = v; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }

    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant v) { this.stoppedAt = v; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
