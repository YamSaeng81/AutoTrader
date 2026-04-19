package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.*;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.service.*;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 예외 처리는 GlobalExceptionHandler 에서 일괄 처리:
//   SessionNotFoundException  → 404
//   SessionStateException     → 409
//   MethodArgumentNotValidException → 400

/**
 * 실전 매매 API 컨트롤러 -- 다중 세션 지원
 * DESIGN.md 섹션 4.4 API 명세 구현
 */
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
@Slf4j
public class TradingController {

    private final LiveTradingService liveTradingService;
    private final PositionService positionService;
    private final OrderExecutionEngine orderExecutionEngine;
    private final RiskManagementService riskManagementService;
    private final ExchangeHealthMonitor exchangeHealthMonitor;
    private final TelegramNotificationService telegramNotificationService;
    private final PositionRepository positionRepository;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    // -- 세션 관리 ------------------------------------------------

    /** 새 매매 세션 생성 */
    @PostMapping("/sessions")
    public ApiResponse<LiveTradingSessionEntity> createSession(
            @Valid @RequestBody LiveTradingStartRequest request) {
        return ApiResponse.ok(liveTradingService.createSession(request));
    }

    /** 다중 전략 일괄 세션 생성 (동일 코인/타임프레임/투자금, 전략별 독립 세션) */
    @PostMapping("/sessions/multi")
    public ApiResponse<List<LiveTradingSessionEntity>> createMultipleSessions(
            @Valid @RequestBody MultiStrategyLiveTradingRequest request) {
        return ApiResponse.ok(liveTradingService.createMultipleSessions(request));
    }

    /** 전체 세션 목록 */
    @GetMapping("/sessions")
    public ApiResponse<List<LiveTradingSessionEntity>> listSessions() {
        return ApiResponse.ok(liveTradingService.listSessions());
    }

    /** 세션 상세 조회 */
    @GetMapping("/sessions/{id}")
    public ApiResponse<LiveTradingSessionEntity> getSession(@PathVariable Long id) {
        return ApiResponse.ok(liveTradingService.getSession(id));
    }

    /** 세션 시작 */
    @PostMapping("/sessions/{id}/start")
    public ApiResponse<LiveTradingSessionEntity> startSession(@PathVariable Long id) {
        return ApiResponse.ok(liveTradingService.startSession(id));
    }

    /** 세션 정지 */
    @PostMapping("/sessions/{id}/stop")
    public ApiResponse<LiveTradingSessionEntity> stopSession(@PathVariable Long id) {
        return ApiResponse.ok(liveTradingService.stopSession(id));
    }

    /** 세션 비상 정지 */
    @PostMapping("/sessions/{id}/emergency-stop")
    public ApiResponse<LiveTradingSessionEntity> emergencyStopSession(@PathVariable Long id) {
        return ApiResponse.ok(liveTradingService.emergencyStopSession(id));
    }

    /** 세션 삭제 (STOPPED 상태만) */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable Long id) {
        liveTradingService.deleteSession(id);
        return ApiResponse.ok(null);
    }

    /** 세션의 포지션 목록 */
    @GetMapping("/sessions/{id}/positions")
    public ApiResponse<List<PositionEntity>> getSessionPositions(@PathVariable Long id) {
        return ApiResponse.ok(liveTradingService.getSessionPositions(id));
    }

    /** 세션 가격 차트 데이터 (캔들 + 매수매도 시점) */
    @GetMapping("/sessions/{id}/chart")
    public ApiResponse<Map<String, Object>> getSessionChart(@PathVariable Long id) {
        List<MarketDataCacheEntity> candles = liveTradingService.getChartCandles(id);
        BigDecimal feeRate = fetchFeeRate(liveTradingService.getSession(id).getCoinPair());
        List<Map<String, Object>> allOrders = liveTradingService.getAllSessionOrders(id).stream()
                .map(o -> toOrderMap(o, feeRate))
                .toList();

        List<Map<String, Object>> candleData = candles.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
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

    /** 세션의 주문 내역 */
    @GetMapping("/sessions/{id}/orders")
    public ApiResponse<Page<OrderEntity>> getSessionOrders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(liveTradingService.getSessionOrders(id, PageRequest.of(page, size)));
    }

    // -- 전체 매매 상태 -------------------------------------------

    /** 전체 비상 정지 (모든 세션) */
    @PostMapping("/emergency-stop")
    public ApiResponse<TradingStatusResponse> emergencyStopAll() {
        liveTradingService.emergencyStopAll(false);
        return ApiResponse.ok(liveTradingService.getGlobalStatus());
    }

    /** §10 비상 청산 dry-run — 실제 주문 없이 청산 시나리오만 로그 기록 */
    @PostMapping("/emergency-stop/dry-run")
    public ApiResponse<TradingStatusResponse> emergencyStopDryRun() {
        liveTradingService.emergencyStopAll(true);
        return ApiResponse.ok(liveTradingService.getGlobalStatus());
    }

    /** 전체 매매 상태 요약 */
    @GetMapping("/status")
    public ApiResponse<TradingStatusResponse> getStatus() {
        return ApiResponse.ok(liveTradingService.getGlobalStatus());
    }

    // -- 포지션 관리 (전체) ----------------------------------------

    /** 현재 열린 포지션 목록 */
    @GetMapping("/positions")
    public ApiResponse<List<PositionEntity>> getPositions() {
        return ApiResponse.ok(positionService.getOpenPositions());
    }

    /** 포지션 상세 조회 */
    @GetMapping("/positions/{id}")
    public ApiResponse<PositionEntity> getPosition(@PathVariable Long id) {
        return ApiResponse.ok(positionService.getPosition(id));
    }

    // -- 주문 관리 (전체) ------------------------------------------

    /** 주문 내역 (페이징) — 세션/날짜 필터 선택 적용 */
    @GetMapping("/orders")
    public ApiResponse<Page<OrderEntity>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant from = dateFrom != null ? dateFrom.atStartOfDay(kst).toInstant() : null;
        Instant to   = dateTo  != null ? dateTo.plusDays(1).atStartOfDay(kst).toInstant() : null;
        return ApiResponse.ok(orderExecutionEngine.getOrders(PageRequest.of(page, size), sessionId, from, to));
    }

    /** 주문 상세 조회 */
    @GetMapping("/orders/{id}")
    public ApiResponse<OrderEntity> getOrder(@PathVariable Long id) {
        return orderExecutionEngine.getOrder(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "주문을 찾을 수 없습니다: id=" + id));
    }

    /** 주문 취소 */
    @DeleteMapping("/orders/{id}")
    public ApiResponse<OrderEntity> cancelOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderExecutionEngine.cancelOrder(id));
    }

    // -- 리스크 설정 -----------------------------------------------

    /** 리스크 설정 조회 */
    @GetMapping("/risk/config")
    public ApiResponse<RiskConfigEntity> getRiskConfig() {
        return ApiResponse.ok(riskManagementService.getRiskConfig());
    }

    /** 리스크 설정 수정 */
    @PutMapping("/risk/config")
    public ApiResponse<RiskConfigEntity> updateRiskConfig(@Valid @RequestBody RiskConfigEntity config) {
        return ApiResponse.ok(riskManagementService.updateRiskConfig(config));
    }

    // -- 거래소 상태 -----------------------------------------------

    /** 거래소 연결 상태 조회 */
    @GetMapping("/health/exchange")
    public ApiResponse<ExchangeHealthResponse> getExchangeHealth() {
        return ApiResponse.ok(exchangeHealthMonitor.getHealthStatus());
    }

    // -- 내부 변환 ─────────────────────────────────────────────

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0005");

    /** Upbit getOrderChance() API로 실시간 수수료율 조회. 실패 시 기본값 0.0005 사용. */
    private BigDecimal fetchFeeRate(String coinPair) {
        if (upbitOrderClient == null) return DEFAULT_FEE_RATE;
        try {
            Map<String, Object> chance = upbitOrderClient.getOrderChance(coinPair);
            Object askFee = chance.get("ask_fee");
            if (askFee != null) return new BigDecimal(askFee.toString());
        } catch (Exception e) {
            // API 호출 실패 시 기본값 사용
        }
        return DEFAULT_FEE_RATE;
    }

    private Map<String, Object> toOrderMap(OrderEntity o, BigDecimal feeRate) {
        BigDecimal price = o.getPrice() != null ? o.getPrice() : BigDecimal.ZERO;
        BigDecimal qty = o.getQuantity() != null ? o.getQuantity() : BigDecimal.ZERO;
        BigDecimal fee = price.multiply(qty).multiply(feeRate).setScale(0, RoundingMode.HALF_UP);

        Map<String, Object> map = new HashMap<>();
        map.put("id", o.getId());
        map.put("coinPair", o.getCoinPair());
        map.put("side", o.getSide());
        map.put("price", price);
        map.put("quantity", qty);
        map.put("fee", fee);
        map.put("state", o.getState());
        map.put("signalReason", o.getSignalReason() != null ? o.getSignalReason() : "");
        map.put("createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : null);
        map.put("filledAt", o.getFilledAt() != null ? o.getFilledAt().toString() : null);

        // SELL 주문: 연결된 포지션에서 매수단가·실현손익 추가
        if ("SELL".equals(o.getSide()) && o.getPositionId() != null) {
            positionRepository.findById(o.getPositionId()).ifPresent(pos -> {
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

    // -- 성과 통계 ------------------------------------------------

    /** 전체 실전매매 성과 요약 */
    @GetMapping("/performance")
    public ApiResponse<PerformanceSummaryResponse> getPerformance() {
        return ApiResponse.ok(liveTradingService.getPerformanceSummary());
    }

    // -- 텔레그램 알림 ---------------------------------------------

    /** 텔레그램 테스트 메시지 전송 */
    @PostMapping("/telegram/test")
    public ApiResponse<String> sendTelegramTest() {
        boolean success = telegramNotificationService.sendTestMessage();
        if (success) {
            return ApiResponse.ok("텔레그램 테스트 메시지 전송 성공");
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "텔레그램 메시지 전송 실패 — 봇 토큰 및 채팅 ID를 확인하세요.");
        }
    }
}
