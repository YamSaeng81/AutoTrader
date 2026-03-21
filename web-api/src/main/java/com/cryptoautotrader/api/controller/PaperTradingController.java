package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.BulkDeleteRequest;
import com.cryptoautotrader.api.dto.MultiStrategyPaperRequest;
import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.service.PaperTradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/paper-trading")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradingService paperTradingService;
    private final PaperPositionRepository positionRepo;

    /**
     * 세션 목록 조회
     * GET /api/v1/paper-trading/sessions
     */
    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        List<Map<String, Object>> sessions = paperTradingService.listSessions().stream()
                .map(this::toSessionSummaryMap)
                .toList();
        return ApiResponse.ok(sessions);
    }

    /**
     * 새 모의투자 세션 시작 (단일 전략)
     * POST /api/v1/paper-trading/sessions
     */
    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> start(@Valid @RequestBody PaperTradingStartRequest request) {
        try {
            VirtualBalanceEntity session = paperTradingService.start(request);
            return ApiResponse.ok(toSessionSummaryMap(session));
        } catch (IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /**
     * 동일 조건으로 여러 전략 한 번에 모의투자 등록
     * POST /api/v1/paper-trading/sessions/multi
     * Body: { "strategyTypes": ["RSI","EMA_CROSS","BOLLINGER"],
     *         "coinPair": "KRW-BTC", "timeframe": "M5", "initialCapital": 10000000 }
     * 응답: 생성된 세션 목록
     */
    @PostMapping("/sessions/multi")
    public ApiResponse<List<Map<String, Object>>> startMulti(
            @Valid @RequestBody MultiStrategyPaperRequest request) {
        try {
            List<Map<String, Object>> sessions = paperTradingService.startMulti(request)
                    .stream().map(this::toSessionSummaryMap).toList();
            return ApiResponse.ok(sessions);
        } catch (IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /**
     * 세션 잔고 조회
     * GET /api/v1/paper-trading/sessions/{sessionId}
     */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSessionBalance(@PathVariable Long sessionId) {
        VirtualBalanceEntity session = paperTradingService.getSessionBalance(sessionId);
        return ApiResponse.ok(toBalanceMap(session));
    }

    /**
     * 세션 포지션 조회
     * GET /api/v1/paper-trading/sessions/{sessionId}/positions
     */
    @GetMapping("/sessions/{sessionId}/positions")
    public ApiResponse<List<Map<String, Object>>> getPositions(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "OPEN") String status) {
        List<PaperPositionEntity> positions = "ALL".equals(status)
                ? paperTradingService.getAllPositions(sessionId)
                : paperTradingService.getOpenPositions(sessionId);
        return ApiResponse.ok(positions.stream().map(this::toPositionMap).toList());
    }

    /**
     * 세션 주문 내역 조회
     * GET /api/v1/paper-trading/sessions/{sessionId}/orders?page=0&size=20
     */
    @GetMapping("/sessions/{sessionId}/orders")
    public ApiResponse<Map<String, Object>> getOrders(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaperOrderEntity> orders = paperTradingService.getOrders(sessionId, PageRequest.of(page, size));
        return ApiResponse.ok(Map.of(
                "content", orders.getContent().stream().map(this::toOrderMap).toList(),
                "totalElements", orders.getTotalElements(),
                "totalPages", orders.getTotalPages(),
                "number", orders.getNumber()
        ));
    }

    /**
     * 세션 중단
     * POST /api/v1/paper-trading/sessions/{sessionId}/stop
     */
    @PostMapping("/sessions/{sessionId}/stop")
    public ApiResponse<Map<String, Object>> stop(@PathVariable Long sessionId) {
        try {
            VirtualBalanceEntity session = paperTradingService.stop(sessionId);
            return ApiResponse.ok(Map.of(
                    "id", session.getId(),
                    "status", session.getStatus(),
                    "finalAsset", session.getTotalKrw(),
                    "stoppedAt", session.getStoppedAt().toString()
            ));
        } catch (IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /**
     * 세션 가격 차트 데이터 (캔들 + 매수매도 시점)
     * GET /api/v1/paper-trading/sessions/{sessionId}/chart
     */
    @GetMapping("/sessions/{sessionId}/chart")
    public ApiResponse<Map<String, Object>> getChart(@PathVariable Long sessionId) {
        List<MarketDataCacheEntity> candles = paperTradingService.getChartCandles(sessionId);
        List<Map<String, Object>> allOrders = paperTradingService.getAllOrders(sessionId).stream()
                .map(this::toOrderMap)
                .toList();

        List<Map<String, Object>> candleData = candles.stream().map(c -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("time", c.getTime().toEpochMilli());
            m.put("open", c.getOpen());
            m.put("high", c.getHigh());
            m.put("low", c.getLow());
            m.put("close", c.getClose());
            m.put("volume", c.getVolume());
            return m;
        }).toList();

        return ApiResponse.ok(Map.of("candles", candleData, "orders", allOrders));
    }

    // ── 응답 변환 ──────────────────────────────────────────────

    private Map<String, Object> toSessionSummaryMap(VirtualBalanceEntity s) {
        BigDecimal initial = s.getInitialCapital() != null ? s.getInitialCapital() : s.getTotalKrw();
        BigDecimal totalReturn = initial.compareTo(BigDecimal.ZERO) > 0
                ? s.getTotalKrw().subtract(initial)
                        .divide(initial, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", s.getId());
        map.put("strategyName", s.getStrategyName() != null ? s.getStrategyName() : "");
        map.put("coinPair", s.getCoinPair() != null ? s.getCoinPair() : "");
        map.put("timeframe", s.getTimeframe() != null ? s.getTimeframe() : "");
        map.put("status", s.getStatus());
        map.put("totalAssetKrw", s.getTotalKrw());
        map.put("availableKrw", s.getAvailableKrw());
        map.put("initialCapital", initial);
        map.put("totalReturnPct", totalReturn);
        map.put("realizedPnl", s.getRealizedPnl() != null ? s.getRealizedPnl() : BigDecimal.ZERO);
        map.put("totalFee", s.getTotalFee() != null ? s.getTotalFee() : BigDecimal.ZERO);
        map.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        map.put("stoppedAt", s.getStoppedAt() != null ? s.getStoppedAt().toString() : null);
        return map;
    }

    private Map<String, Object> toBalanceMap(VirtualBalanceEntity b) {
        BigDecimal initial = b.getInitialCapital() != null ? b.getInitialCapital() : b.getTotalKrw();
        // 포지션 시가 = totalKrw - availableKrw (오픈 포지션의 현재 평가금액)
        BigDecimal positionValueKrw = b.getTotalKrw().subtract(b.getAvailableKrw()).max(BigDecimal.ZERO);
        // 순손익 = totalKrw - initialCapital (수익/손실 합계, 수수료 반영)
        // 이전 코드 버그: unrealizedPnl = totalKrw - availableKrw → 포지션 시가(8,800)를 손익으로 표시했음
        // 올바른 계산: 10,000 투자 → 8,000 매수 → 현재가 8,800 → unrealizedPnl = 10,800 - 10,000 = +800
        BigDecimal netPnl = b.getTotalKrw().subtract(initial);
        BigDecimal totalReturnPct = initial.compareTo(BigDecimal.ZERO) > 0
                ? netPnl.divide(initial, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        // 투자금 대비 포지션 손익률: positionValue 기준 (매수 시 투자원금으로 나눔)
        BigDecimal costBasisKrw = initial.subtract(b.getAvailableKrw()).max(BigDecimal.ZERO);
        BigDecimal positionPnlPct = costBasisKrw.compareTo(BigDecimal.ZERO) > 0
                ? positionValueKrw.subtract(costBasisKrw)
                        .divide(costBasisKrw, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", b.getId());
        map.put("totalAssetKrw", b.getTotalKrw());
        map.put("availableKrw", b.getAvailableKrw());
        map.put("positionValueKrw", positionValueKrw);   // 오픈 포지션 현재 평가금액
        map.put("costBasisKrw", costBasisKrw);            // 오픈 포지션 매수 원가
        map.put("unrealizedPnl", netPnl);                 // 순손익 (초기자본 대비 전체 변동액)
        map.put("positionPnlPct", positionPnlPct);        // 포지션 수익률 (매수원가 기준)
        map.put("totalReturnPct", totalReturnPct);         // 전체 수익률 (초기자본 기준)
        map.put("realizedPnl", b.getRealizedPnl() != null ? b.getRealizedPnl() : BigDecimal.ZERO);
        map.put("totalFee", b.getTotalFee() != null ? b.getTotalFee() : BigDecimal.ZERO);
        map.put("initialCapital", initial);
        map.put("status", b.getStatus());
        map.put("strategyName", b.getStrategyName() != null ? b.getStrategyName() : "");
        map.put("coinPair", b.getCoinPair() != null ? b.getCoinPair() : "");
        map.put("timeframe", b.getTimeframe() != null ? b.getTimeframe() : "");
        map.put("startedAt", b.getStartedAt() != null ? b.getStartedAt().toString() : null);
        map.put("stoppedAt", b.getStoppedAt() != null ? b.getStoppedAt().toString() : null);
        return map;
    }

    private Map<String, Object> toPositionMap(PaperPositionEntity p) {
        BigDecimal unrealizedPnlPct = p.getAvgPrice().compareTo(BigDecimal.ZERO) > 0
                ? p.getUnrealizedPnl()
                        .divide(p.getAvgPrice().multiply(p.getSize()), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", p.getId());
        map.put("coinPair", p.getCoinPair());
        map.put("side", p.getSide());
        map.put("quantity", p.getSize());
        map.put("avgEntryPrice", p.getAvgPrice());
        map.put("entryPrice", p.getEntryPrice());
        map.put("unrealizedPnl", p.getUnrealizedPnl());
        map.put("unrealizedPnlPct", unrealizedPnlPct);
        map.put("realizedPnl", p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO);
        map.put("status", p.getStatus());
        map.put("openedAt", p.getOpenedAt().toString());
        map.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : null);
        return map;
    }

    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");

    private Map<String, Object> toOrderMap(PaperOrderEntity o) {
        BigDecimal price = o.getPrice() != null ? o.getPrice() : BigDecimal.ZERO;
        BigDecimal qty = o.getQuantity() != null ? o.getQuantity() : BigDecimal.ZERO;
        BigDecimal fee = price.multiply(qty).multiply(FEE_RATE).setScale(0, RoundingMode.HALF_UP);

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", o.getId());
        map.put("coinPair", o.getCoinPair());
        map.put("side", o.getSide());
        map.put("price", price);
        map.put("quantity", qty);
        map.put("fee", fee);
        map.put("state", o.getState());
        map.put("signalReason", o.getSignalReason() != null ? o.getSignalReason() : "");
        map.put("createdAt", o.getCreatedAt().toString());
        map.put("filledAt", o.getFilledAt() != null ? o.getFilledAt().toString() : null);

        // SELL 주문: 연결된 포지션에서 매수단가·실현손익 추가
        if ("SELL".equals(o.getSide()) && o.getPositionId() != null) {
            positionRepo.findById(o.getPositionId()).ifPresent(pos -> {
                BigDecimal buyPrice = pos.getAvgPrice();
                BigDecimal realizedPnl = pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO;
                BigDecimal costBasis = buyPrice.multiply(qty);
                BigDecimal pnlPct = costBasis.compareTo(BigDecimal.ZERO) > 0
                        ? realizedPnl.divide(costBasis, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;
                map.put("buyPrice", buyPrice);
                map.put("realizedPnl", realizedPnl);
                map.put("realizedPnlPct", pnlPct);
            });
        }
        return map;
    }

    // ── 이력 삭제 ──────────────────────────────────────────────

    /**
     * 모의투자 세션 이력 단건 삭제
     * DELETE /api/v1/paper-trading/history/{id}
     * - RUNNING 상태: 400 Bad Request
     * - 존재하지 않는 ID: 404 Not Found
     * - 성공: 204 No Content
     */
    @DeleteMapping("/history/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable Long id) {
        try {
            paperTradingService.deleteSession(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 모의투자 세션 이력 다건 삭제
     * DELETE /api/v1/paper-trading/history/bulk
     * Body: { "ids": [1, 2, 3] }
     * - RUNNING 세션은 건너뜀
     * - 성공: 204 No Content
     */
    @DeleteMapping("/history/bulk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bulkDeleteSessions(@RequestBody BulkDeleteRequest request) {
        paperTradingService.bulkDeleteSessions(request.getIds());
    }
}
