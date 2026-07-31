package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 포지션 관리 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionService {

    private final PositionRepository positionRepository;
    private final OrderExecutionEngine orderExecutionEngine;

    /**
     * 열린 포지션 목록 조회 (세션 종류 무관 — 전역).
     *
     * <p>실전매매/동적 세션 화면처럼 한쪽 종류만 보여야 하는 곳에서는
     * {@link #getOpenPositions(String)} 을 사용할 것.</p>
     */
    @Transactional(readOnly = true)
    public List<PositionEntity> getOpenPositions() {
        return positionRepository.findByStatus("OPEN");
    }

    /**
     * 세션 종류(LIVE/DYNAMIC)별 열린 포지션 목록 조회.
     *
     * <p>position 테이블은 실전매매와 동적 멀티코인이 공용으로 쓰고 {@code session_kind} 로만
     * 구분되므로, 필터 없이 조회하면 실전매매 화면에 동적 세션 포지션이 섞여 보인다.</p>
     */
    @Transactional(readOnly = true)
    public List<PositionEntity> getOpenPositions(String sessionKind) {
        return positionRepository.findBySessionKindAndStatus(sessionKind, "OPEN");
    }

    /**
     * 포지션 상세 조회
     */
    @Transactional(readOnly = true)
    public PositionEntity getPosition(Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("포지션을 찾을 수 없습니다: id=" + id));
    }

    /**
     * 전체 포지션 페이징 조회
     */
    @Transactional(readOnly = true)
    public Page<PositionEntity> getAllPositions(Pageable pageable) {
        return positionRepository.findAllByOrderByOpenedAtDesc(pageable);
    }

    /**
     * 미실현 손익 업데이트
     * 현재가 기준으로 열린 포지션의 unrealized_pnl을 재계산한다.
     */
    @Transactional
    public void updateUnrealizedPnl(String coinPair, BigDecimal currentPrice) {
        // 여러 세션이 같은 코인을 동시에 보유할 수 있으므로 전건을 각자 갱신한다.
        // (단건 조회로 두면 다른 세션 포지션 하나만 갱신되거나 NonUniqueResult로 터진다)
        for (PositionEntity pos : positionRepository.findAllByCoinPairAndStatus(coinPair, "OPEN")) {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepository.save(pos);
            log.debug("미실현 손익 업데이트: {} posId={} unrealizedPnl={}", coinPair, pos.getId(), unrealized);
        }
    }

    /**
     * 포지션 청산 — 시장가 매도 주문을 생성하여 포지션을 닫는다.
     */
    @Transactional
    public void closePosition(Long positionId, BigDecimal price) {
        PositionEntity pos = getPosition(positionId);

        if (!"OPEN".equals(pos.getStatus())) {
            throw new IllegalStateException("이미 종료된 포지션입니다: id=" + positionId);
        }

        OrderRequest sellOrder = new OrderRequest();
        sellOrder.setCoinPair(pos.getCoinPair());
        sellOrder.setSide("SELL");
        sellOrder.setOrderType("MARKET");
        sellOrder.setQuantity(pos.getSize());
        sellOrder.setReason("포지션 청산 요청 (positionId=" + positionId + ")");

        orderExecutionEngine.submitOrder(sellOrder);
        log.info("포지션 청산 주문 제출: posId={}, {} 수량={}", positionId, pos.getCoinPair(), pos.getSize());
    }

    /**
     * 세션 종류(LIVE/DYNAMIC)별 실현+미실현 손익 합산.
     *
     * <p>{@link #getTotalPnl()} 은 두 종류를 합산하므로 실전매매 요약에 동적 세션 손익이
     * 섞여 들어간다. 종류별 화면은 반드시 이 메서드를 쓸 것.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalPnl(String sessionKind) {
        BigDecimal sum = positionRepository.sumTotalPnlBySessionKind(sessionKind);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * 열린 포지션의 전체 실현+미실현 손익 합산 (세션 종류 무관 — 전역)
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalPnl() {
        List<PositionEntity> allPositions = positionRepository.findAll();
        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalUnrealized = BigDecimal.ZERO;

        for (PositionEntity pos : allPositions) {
            if (pos.getRealizedPnl() != null) {
                totalRealized = totalRealized.add(pos.getRealizedPnl());
            }
            if ("OPEN".equals(pos.getStatus()) && pos.getUnrealizedPnl() != null) {
                totalUnrealized = totalUnrealized.add(pos.getUnrealizedPnl());
            }
        }

        return totalRealized.add(totalUnrealized);
    }
}
