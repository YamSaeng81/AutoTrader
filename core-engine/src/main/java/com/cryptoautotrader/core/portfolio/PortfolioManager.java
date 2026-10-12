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
     * 마지막으로 거래소 잔고를 반영한 시각 (2026-10-12). {@code null} 이면 한 번도 반영하지 않았다.
     *
     * <p>🔴 {@code totalCapital} 의 초기값은 0 이고 동기화가 실패하면 이전 값이 남는다. 값만 보고는
     * "계좌에 정말 그만큼 있다" 와 "아직 모른다" 를 구분할 수 없으므로, 그 값을 실자금 승인에 쓰는
     * 쪽이 신선도를 확인할 수 있게 시각을 함께 둔다.</p>
     */
    private volatile Instant lastSyncedAt;

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

    /** 마지막 거래소 잔고 반영 시각 — {@code null} 이면 미반영. */
    public Instant getLastSyncedAt() {
        return lastSyncedAt;
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
        if (newCapital == null || newCapital.compareTo(BigDecimal.ZERO) < 0) return;
        this.totalCapital = newCapital;
        this.lastSyncedAt = Instant.now();
        // allocatedCapital이 새 totalCapital을 초과하면 조정
        if (allocatedCapital.compareTo(newCapital) > 0) {
            allocatedCapital = newCapital;
        }
    }
}
