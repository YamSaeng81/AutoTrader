package com.cryptoautotrader.core.portfolio;

import com.cryptoautotrader.core.model.CoinPair;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 포트폴리오 매니저: 전체 자산 관리, 전략 간 충돌 방지
 * - 동일 코인에 대한 복수 전략의 반대 포지션 방지
 * - 총 투자 한도 관리
 */
public class PortfolioManager {

    @Getter
    private BigDecimal totalCapital;
    private BigDecimal allocatedCapital = BigDecimal.ZERO;

    /**
     * 거래소 잔고 스냅숏 — <b>잔고와 기준 시각의 짝</b> (2026-10-12). {@code null} 이면 한 번도 반영하지 않았다.
     *
     * <p>🔴 {@code totalCapital} 의 초기값은 0 이고 동기화가 실패하면 이전 값이 남는다. 값만 보고는
     * "계좌에 정말 그만큼 있다" 와 "아직 모른다" 를 구분할 수 없으므로 시각을 함께 둔다.</p>
     *
     * <p>🔴 <b>하나의 불변 객체로 둔다.</b> 잔고와 시각을 따로 읽으면 교체 순간에 다른 스냅숏의 짝이
     * 섞일 수 있다 — 옛 잔고(체결 전)에 새 시각을 붙이면 그 사이 체결이 "스냅숏 이전" 으로 분류돼
     * 이미 쓴 돈을 다시 승인한다.</p>
     */
    public record CapitalSnapshot(BigDecimal totalCapital, Instant asOf) { }

    private volatile CapitalSnapshot snapshot;

    // 코인별 현재 포지션 방향 (전략 간 충돌 방지)
    private final Map<String, PositionDirection> coinDirections = new ConcurrentHashMap<>();
    // 전략별 할당 자본
    private final Map<String, BigDecimal> strategyAllocations = new ConcurrentHashMap<>();

    public enum PositionDirection {
        LONG, SHORT, FLAT
    }

    public PortfolioManager(BigDecimal totalCapital) {
        this.totalCapital = totalCapital;
    }

    /**
     * 전략에 자본 할당 가능 여부 확인
     * synchronized: allocatedCapital 가시성 보장 + TOCTOU 방지
     */
    public synchronized boolean canAllocate(String strategyId, BigDecimal amount) {
        BigDecimal available = totalCapital.subtract(allocatedCapital);
        return amount.compareTo(available) <= 0;
    }

    /**
     * 전략에 자본 할당
     */
    public synchronized void allocate(String strategyId, BigDecimal amount) {
        if (!canAllocate(strategyId, amount)) {
            throw new IllegalStateException("할당 가능 자본 부족: 요청=" + amount + ", 가용=" + totalCapital.subtract(allocatedCapital));
        }
        allocatedCapital = allocatedCapital.add(amount);
        strategyAllocations.merge(strategyId, amount, BigDecimal::add);
    }

    /**
     * 전략 자본 반환
     */
    public synchronized void release(String strategyId, BigDecimal amount) {
        allocatedCapital = allocatedCapital.subtract(amount).max(BigDecimal.ZERO);
        strategyAllocations.computeIfPresent(strategyId, (k, v) -> {
            BigDecimal remaining = v.subtract(amount);
            return remaining.compareTo(BigDecimal.ZERO) <= 0 ? null : remaining;
        });
    }

    /**
     * 코인에 대한 방향 충돌 확인
     * 한 코인에 대해 LONG과 SHORT이 동시에 존재하면 안 됨
     */
    public boolean hasConflict(CoinPair coinPair, PositionDirection direction) {
        PositionDirection current = coinDirections.get(coinPair.value());
        return current != null && current != PositionDirection.FLAT && current != direction;
    }

    public void setDirection(CoinPair coinPair, PositionDirection direction) {
        if (direction == PositionDirection.FLAT) {
            coinDirections.remove(coinPair.value());
        } else {
            coinDirections.put(coinPair.value(), direction);
        }
    }

    /** 마지막 거래소 잔고 스냅숏의 기준 시각 — {@code null} 이면 미반영. */
    public Instant getLastSyncedAt() {
        CapitalSnapshot s = snapshot;
        return s != null ? s.asOf() : null;
    }

    /** 잔고와 기준 시각을 <b>한 번에</b> 읽는다 — 실자금 승인은 이것만 쓴다. */
    public CapitalSnapshot getCapitalSnapshot() {
        return snapshot;
    }

    public synchronized BigDecimal getAvailableCapital() {
        return totalCapital.subtract(allocatedCapital);
    }

    public BigDecimal getAllocationRatio() {
        if (totalCapital.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return allocatedCapital.divide(totalCapital, 4, RoundingMode.HALF_UP);
    }

    /**
     * 거래소 잔고 동기화 — 외부 스케줄러가 주기적으로 호출한다.
     * totalCapital을 실시간 거래소 KRW 잔고(가용 + 주문 잠금)로 갱신한다.
     * allocatedCapital이 새 totalCapital을 초과하면 안전하게 새 totalCapital로 조정한다.
     */
    public synchronized void syncTotalCapital(BigDecimal newCapital) {
        syncTotalCapital(newCapital, Instant.now());
    }

    /**
     * 기준 시각을 지정해 반영한다 (2026-10-12).
     *
     * <p>🔴 {@code asOf} 는 <b>조회를 시작한 시각</b>이어야 한다 — 거래소가 잔고를 계산한 시점은
     * 그 뒤 어딘가이고 우리는 알 수 없다. 시작 시각을 쓰면 조회 중에 일어난 체결은 "스냅숏 이후" 로
     * 분류돼 한 번 더 빠진다(과소 승인). 응답 시각을 쓰면 반대로 빠지지 않아 과대 승인이 된다.</p>
     *
     * <p>🔴 <b>더 오래된 스냅숏으로 바꾸지 않는다.</b> 두 조회가 겹치면 먼저 시작한 쪽의 응답이 늦게
     * 도착할 수 있다.</p>
     *
     * @return 반영했으면 {@code true}
     */
    public synchronized boolean syncTotalCapital(BigDecimal newCapital, Instant asOf) {
        if (newCapital == null || newCapital.compareTo(BigDecimal.ZERO) < 0 || asOf == null) return false;
        // 🔴 DB 시각은 마이크로초 정밀도다(PostgreSQL·H2). 메모리 시각(나노초)을 그대로 두면 같은 마이크로초
        //    안에서 기준 시각 **뒤에** 종결된 체결이 저장 후 기준 시각보다 앞으로 보여 약정에서 빠진다(전체
        //    실행에서 실측: 저장 .733551 < 기준 .7335514). 기준을 마이크로초로 **내림**하고 비교를 포함(≥)으로
        //    하면, 기준 뒤의 어떤 사건도 저장 시각이 기준 이상이 된다 — 오차는 과소 승인 쪽에만 남는다.
        asOf = asOf.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        CapitalSnapshot current = snapshot;
        if (current != null && current.asOf().isAfter(asOf)) {
            return false;
        }
        this.totalCapital = newCapital;
        this.snapshot = new CapitalSnapshot(newCapital, asOf);
        // allocatedCapital이 새 totalCapital을 초과하면 조정
        if (allocatedCapital.compareTo(newCapital) > 0) {
            allocatedCapital = newCapital;
        }
        return true;
    }
}
