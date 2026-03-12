package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.*;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.util.List;

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
    private final OrderRepository orderRepository;
    private final TelegramNotificationService telegramNotificationService;

    // -- 세션 관리 ------------------------------------------------

    /** 새 매매 세션 생성 */
    @PostMapping("/sessions")
    public ApiResponse<LiveTradingSessionEntity> createSession(
            @Valid @RequestBody LiveTradingStartRequest request) {
        try {
            return ApiResponse.ok(liveTradingService.createSession(request));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 전체 세션 목록 */
    @GetMapping("/sessions")
    public ApiResponse<List<LiveTradingSessionEntity>> listSessions() {
        return ApiResponse.ok(liveTradingService.listSessions());
    }

    /** 세션 상세 조회 */
    @GetMapping("/sessions/{id}")
    public ApiResponse<LiveTradingSessionEntity> getSession(@PathVariable Long id) {
        try {
            return ApiResponse.ok(liveTradingService.getSession(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 세션 시작 */
    @PostMapping("/sessions/{id}/start")
    public ApiResponse<LiveTradingSessionEntity> startSession(@PathVariable Long id) {
        try {
            return ApiResponse.ok(liveTradingService.startSession(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 세션 정지 */
    @PostMapping("/sessions/{id}/stop")
    public ApiResponse<LiveTradingSessionEntity> stopSession(@PathVariable Long id) {
        try {
            return ApiResponse.ok(liveTradingService.stopSession(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 세션 비상 정지 */
    @PostMapping("/sessions/{id}/emergency-stop")
    public ApiResponse<LiveTradingSessionEntity> emergencyStopSession(@PathVariable Long id) {
        try {
            return ApiResponse.ok(liveTradingService.emergencyStopSession(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 세션 삭제 (STOPPED 상태만) */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable Long id) {
        try {
            liveTradingService.deleteSession(id);
            return ApiResponse.ok(null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 세션의 포지션 목록 */
    @GetMapping("/sessions/{id}/positions")
    public ApiResponse<List<PositionEntity>> getSessionPositions(@PathVariable Long id) {
        try {
            return ApiResponse.ok(liveTradingService.getSessionPositions(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 세션의 주문 내역 */
    @GetMapping("/sessions/{id}/orders")
    public ApiResponse<Page<OrderEntity>> getSessionOrders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.ok(liveTradingService.getSessionOrders(id, PageRequest.of(page, size)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // -- 전체 매매 상태 -------------------------------------------

    /** 전체 비상 정지 (모든 세션) */
    @PostMapping("/emergency-stop")
    public ApiResponse<TradingStatusResponse> emergencyStopAll() {
        liveTradingService.emergencyStopAll();
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
        try {
            return ApiResponse.ok(positionService.getPosition(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // -- 주문 관리 (전체) ------------------------------------------

    /** 주문 내역 (페이징) */
    @GetMapping("/orders")
    public ApiResponse<Page<OrderEntity>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
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
        try {
            OrderEntity cancelled = orderExecutionEngine.cancelOrder(id);
            return ApiResponse.ok(cancelled);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
