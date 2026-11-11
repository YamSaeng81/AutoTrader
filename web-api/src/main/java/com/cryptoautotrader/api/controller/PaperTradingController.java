package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.BulkDeleteRequest;
import com.cryptoautotrader.api.dto.MultiStrategyPaperRequest;
import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.dto.PerformanceSummaryResponse;
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
        // v1.150 §3-3: 목록도 세션별 청산 대기 수와 함께 한 스냅샷으로 읽는다.
        List<Map<String, Object>> sessions = paperTradingService.listSessionValuations().stream()
                .map(v -> toSessionSummaryMap(v.session(), v.pendingCloseCount()))
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
            return ApiResponse.ok(toSessionSummaryMap(session, 0));
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
                    .stream().map(s -> toSessionSummaryMap(s, 0)).toList();
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
        // v1.150 S4: 잔고와 청산 대기 수를 한 스냅샷으로 읽는다 — 대기가 있으면 기록 총자산을 현재 평가로 내보내지 않는다.
        PaperTradingService.SessionValuation v = paperTradingService.getSessionValuation(sessionId);
        return ApiResponse.ok(toBalanceMap(v.session(), v.pendingCloseCount()));
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
            PaperTradingService.SessionValuation outcome = paperTradingService.stopWithOutcome(sessionId);
            VirtualBalanceEntity session = outcome.session();
            // 청산 대기(LIVE_ALIGNED, 유효 가격 없음)가 남았으면 정산 완료가 아니다 — 수와 함께 알린다 (2026-11-11). 종전 세션은 늘 0.
            // v1.150 §3-3: 대기 수는 정지를 커밋한 같은 트랜잭션에서 센 값이다. 응답 직전에 다시 세면 그 사이 정산이 끝나 0 이 되고
            //   정산 전 엔티티의 총액을 최종 자산으로 내보낼 수 있다.
            long pendingCloses = outcome.pendingCloseCount();
            // v1.148 W4: 청산 대기가 있으면 최종 자산이 아니다 — finalAsset 을 비우고 마지막 기록 총자산을 따로 표시한다.
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("id", session.getId());
            body.put("status", session.getStatus());
            body.put("finalAsset", pendingCloses == 0 ? session.getTotalKrw() : null);
            if (pendingCloses > 0) {
                body.put("lastRecordedTotalKrw", session.getTotalKrw());
                body.put("assetStatus", "확정 아님 — 청산 대기 포지션 미정산(마지막 기록 총자산, 현재 평가 아님)");
            }
            body.put("stoppedAt", session.getStoppedAt().toString());
            body.put("pendingCloseCount", pendingCloses);
            body.put("settlement", pendingCloses == 0 ? "완료" : "청산 대기 — 첫 유효 시세에 정산");
            return ApiResponse.ok(body);
        } catch (IllegalStateException e) {
            return ApiResponse.error("INVALID_REQUEST", e.getMessage());
        }
    }

    /**
     * 실행 중인 모든 세션 일괄 정지
     * POST /api/v1/paper-trading/sessions/stop-all
     */
    @PostMapping("/sessions/stop-all")
    public ApiResponse<Map<String, Object>> stopAll() {
        List<PaperTradingService.SessionValuation> stopped = paperTradingService.stopAll();
        // v1.148 W4: 일괄 정지도 청산 대기 수를 함께 알린다(0 이면 전부 정산). v1.150: 수는 각 정지 커밋 시점의 값이다.
        long pending = stopped.stream().mapToLong(PaperTradingService.SessionValuation::pendingCloseCount).sum();
        return ApiResponse.ok(Map.of("stoppedCount", stopped.size(), "pendingCloseCount", pending,
                "settlement", pending == 0 ? "완료" : "청산 대기 " + pending + "건 — 첫 유효 시세에 정산")); 
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

    /** 청산 대기가 있을 때 기록값 표시 문구 (v1.150 §3-3). */
    static final String LAST_RECORDED_NOTE = "청산 대기 — 마지막 기록 총자산, 기록 시각 미상, 현재 평가 아님";
    static final String VALUATION_DONE = "평가 완료";
    static final String VALUATION_PENDING = "평가 불가 — 청산 대기(미정산 보유, 유효 시세 없음)";

    /**
     * 평가 상태 공통 필드 (v1.150 §3-3). 대기가 있으면 현재 총자산·수익률을 확정 수치처럼 주지 않는다 — null 로 두고 기록값은 이름과 표시를
     * 달리해 따로 준다. 현금 잔액(availableKrw)과 확정 실현손익(realizedPnl, 완료 거래만)은 확정값이므로 그대로다.
     */
    private static void putValuation(Map<String, Object> map, VirtualBalanceEntity s, long pending) {
        map.put("pendingCloseCount", pending);
        map.put("valuationStatus", pending > 0 ? VALUATION_PENDING : VALUATION_DONE);
        if (pending > 0) {
            map.put("lastRecordedTotalKrw", s.getTotalKrw());
            map.put("lastRecordedNote", LAST_RECORDED_NOTE);
            map.put("realizedPnlNote", "완료 거래만 — 청산 대기 포지션은 아직 완료 거래가 아님");
        }
    }

    private Map<String, Object> toSessionSummaryMap(VirtualBalanceEntity s, long pending) {
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
        map.put("totalAssetKrw", pending > 0 ? null : s.getTotalKrw());
        map.put("availableKrw", s.getAvailableKrw());
        map.put("initialCapital", initial);
        map.put("totalReturnPct", pending > 0 ? null : totalReturn);
        putValuation(map, s, pending);
        map.put("realizedPnl", s.getRealizedPnl() != null ? s.getRealizedPnl() : BigDecimal.ZERO);
        map.put("totalFee", s.getTotalFee() != null ? s.getTotalFee() : BigDecimal.ZERO);
        map.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        map.put("stoppedAt", s.getStoppedAt() != null ? s.getStoppedAt().toString() : null);
        // A/B arm 식별자 — 이게 없으면 어느 세션이 실험군인지 API 로 구분할 수 없다.
        // (2026-08-24 손절폭 A/B: 대조군만 골라 복제하려면 필수)
        map.put("strategyParams", s.getStrategyParams());
        return map;
    }

    private Map<String, Object> toBalanceMap(VirtualBalanceEntity b, long pending) {
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
        boolean p = pending > 0;
        map.put("id", b.getId());
        // v1.150 S4: 청산 대기가 있으면 평가에서 나온 값(총자산·포지션 평가액·순손익·수익률)은 null — 유효 시세가 없어 현재 평가가 아니다.
        map.put("totalAssetKrw", p ? null : b.getTotalKrw());
        map.put("availableKrw", b.getAvailableKrw());     // 현금 잔액 — 확정값
        map.put("positionValueKrw", p ? null : positionValueKrw);   // 오픈 포지션 현재 평가금액
        map.put("costBasisKrw", costBasisKrw);            // 오픈 포지션 매수 원가
        map.put("unrealizedPnl", p ? null : netPnl);       // 순손익 (초기자본 대비 전체 변동액)
        map.put("positionPnlPct", p ? null : positionPnlPct);        // 포지션 수익률 (매수원가 기준)
        map.put("totalReturnPct", p ? null : totalReturnPct);         // 전체 수익률 (초기자본 기준)
        putValuation(map, b, pending);
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
        if ("CLOSING".equals(p.getStatus())) return toPendingPositionMap(p);
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

    /**
     * 청산 대기(LIVE_ALIGNED, 미정산 보유) 표시 (2026-11-11, v1.148 W4). 유효 시세가 없어 평가할 수 없으므로 미실현손익을 0 으로 채우지 않고
     * null 로 두며, 표에 남은 마지막 기록값은 시각을 알 수 없다는 표시와 함께 따로 준다. 결정한 청산 사유와 판단 시각을 함께 준다.
     */
    private Map<String, Object> toPendingPositionMap(PaperPositionEntity p) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", p.getId());
        map.put("coinPair", p.getCoinPair());
        map.put("side", p.getSide());
        map.put("quantity", p.getSize());
        map.put("avgEntryPrice", p.getAvgPrice());
        map.put("entryPrice", p.getEntryPrice());
        map.put("unrealizedPnl", null);
        map.put("unrealizedPnlPct", null);
        map.put("valuationStatus", "평가 불가 — 청산 대기(유효 시세 없음)");
        map.put("lastRecordedUnrealizedPnl", p.getUnrealizedPnl());
        map.put("lastRecordedUnrealizedPnlNote", "청산 판단 이전 마지막 기록값 — 기록 시각 미상, 현재 평가 아님");
        map.put("pendingExitReason", p.getExitReason() != null ? p.getExitReason().name() : null);
        map.put("exitDecidedAt", p.getExitDecidedAt() != null ? p.getExitDecidedAt().toString() : null);
        map.put("realizedPnl", null);
        map.put("status", p.getStatus());
        map.put("openedAt", p.getOpenedAt().toString());
        map.put("closedAt", null);
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

    // ── 성과 통계 ──────────────────────────────────────────────

    /**
     * 전체 모의투자 성과 요약
     * GET /api/v1/paper-trading/performance
     */
    @GetMapping("/performance")
    public ApiResponse<PerformanceSummaryResponse> getPerformance() {
        return ApiResponse.ok(paperTradingService.getOverallPerformance());
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
