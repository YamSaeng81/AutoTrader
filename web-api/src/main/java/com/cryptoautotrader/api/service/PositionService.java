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
     * 열린 포지션 목록 조회
     */
    @Transactional(readOnly = true)
    public List<PositionEntity> getOpenPositions() {
        return positionRepository.findByStatus("OPEN");
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
        positionRepository.findByCoinPairAndStatus(coinPair, "OPEN").ifPresent(pos -> {
            BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
            pos.setUnrealizedPnl(unrealized);
            positionRepository.save(pos);
            log.debug("미실현 손익 업데이트: {} unrealizedPnl={}", coinPair, unrealized);
        });
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
     * 열린 포지션의 전체 실현+미실현 손익 합산
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
