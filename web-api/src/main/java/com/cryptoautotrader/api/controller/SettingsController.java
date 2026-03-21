package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.entity.TelegramNotificationLogEntity;
import com.cryptoautotrader.api.log.InMemoryLogBuffer;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.service.AccountService;
import com.cryptoautotrader.api.service.DbResetService;
import com.cryptoautotrader.api.service.TelegramNotificationService;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final DateTimeFormatter KST_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    @Autowired
    private TelegramNotificationService telegramService;

    @Autowired(required = false)
    private UpbitOrderClient upbitOrderClient;

    @Autowired
    private UpbitRestClient upbitRestClient;

    @Autowired
    private AccountService accountService;

    @Autowired
    private MarketDataCacheRepository marketDataCacheRepository;

    @Autowired
    private DbResetService dbResetService;

    /** 텔레그램 전송 이력 조회 (최신순, 페이지네이션) */
    @GetMapping("/telegram/logs")
    public ApiResponse<Map<String, Object>> getTelegramLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<TelegramNotificationLogEntity> result = telegramService.getLogs(page, size);

        List<Map<String, Object>> items = result.getContent().stream()
                .map(e -> Map.<String, Object>of(
                        "id",           e.getId(),
                        "type",         e.getType(),
                        "sessionLabel", e.getSessionLabel() != null ? e.getSessionLabel() : "",
                        "messageText",  e.getMessageText(),
                        "success",      e.isSuccess(),
                        "sentAt",       KST_FMT.format(e.getSentAt())
                ))
                .toList();

        return ApiResponse.ok(Map.of(
                "items",      items,
                "totalCount", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "page",       page,
                "size",       size
        ));
    }

    /** 텔레그램 테스트 메시지 전송 */
    @PostMapping("/telegram/test")
    public ApiResponse<Map<String, Object>> sendTestMessage() {
        boolean ok = telegramService.sendTestMessage();
        return ApiResponse.ok(Map.of("success", ok));
    }

    /**
     * Upbit 연동 상태 종합 점검
     * - API 키 설정 여부 (UpbitOrderClient Bean 존재 여부)
     * - 잔고 조회 성공 여부
     * - market_data_cache 캔들 현황 (실전매매 캔들 싱크 확인용)
     */
    @GetMapping("/upbit/status")
    public ApiResponse<Map<String, Object>> getUpbitStatus() {
        Map<String, Object> result = new HashMap<>();

        // 1. API Key 설정 여부
        boolean apiKeyConfigured = upbitOrderClient != null;
        result.put("apiKeyConfigured", apiKeyConfigured);

        // 2. 잔고 조회 테스트 (AccountService 재사용)
        if (apiKeyConfigured) {
            try {
                Map<String, Object> accountSummary = accountService.getAccountSummary();
                boolean accountOk = accountSummary.containsKey("totalAssetKrw");
                result.put("accountQueryOk", accountOk);
                if (accountOk) {
                    result.put("totalAssetKrw", accountSummary.get("totalAssetKrw"));
                } else if (accountSummary.containsKey("error")) {
                    result.put("accountError", accountSummary.get("error"));
                }
            } catch (Exception e) {
                result.put("accountQueryOk", false);
                result.put("accountError", e.getMessage());
            }
        } else {
            result.put("accountQueryOk", false);
            result.put("accountError", "UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 미설정");
        }

        // 3. market_data_cache 캔들 현황 (코인+타임프레임별 건수)
        try {
            List<Object[]> summary = marketDataCacheRepository.findDataSummary();
            List<Map<String, Object>> candleSummary = new ArrayList<>();
            for (Object[] row : summary) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("coinPair",  row[0]);
                entry.put("timeframe", row[1]);
                entry.put("from",      row[2] != null ? row[2].toString() : null);
                entry.put("to",        row[3] != null ? row[3].toString() : null);
                entry.put("count",     row[4]);
                candleSummary.add(entry);
            }
            result.put("candleSummary", candleSummary);
            result.put("candleQueryOk", true);
        } catch (Exception e) {
            result.put("candleQueryOk", false);
            result.put("candleError", e.getMessage());
        }

        return ApiResponse.ok(result);
    }

    // ── Upbit API 테스트 ──────────────────────────────────────

    /**
     * 주문 가능 정보 (GET /v1/orders/chance)
     * 수수료율, 최소 주문 금액, 마켓별 잔고 확인
     */
    @GetMapping("/upbit/order-chance")
    public ApiResponse<Map<String, Object>> getOrderChance(
            @RequestParam(defaultValue = "KRW-ETH") String market) {
        if (upbitOrderClient == null) {
            return ApiResponse.ok(Map.of("error", "API 키 미설정"));
        }
        try {
            Map<String, Object> chance = upbitOrderClient.getOrderChance(market);
            return ApiResponse.ok(chance);
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 주문 생성 테스트 (POST /v1/orders/test) — 실거래 없음
     * body: { market, side, amount }
     */
    @PostMapping("/upbit/test-order")
    public ApiResponse<Map<String, Object>> testOrder(@RequestBody Map<String, Object> body) {
        if (upbitOrderClient == null) {
            return ApiResponse.ok(Map.of("success", false, "error", "API 키 미설정"));
        }
        try {
            String market = (String) body.getOrDefault("market", "KRW-ETH");
            String side   = (String) body.getOrDefault("side", "bid");
            // 시장가 매수: price 타입 (KRW 총액)
            java.math.BigDecimal amount = new java.math.BigDecimal(
                    body.getOrDefault("amount", "5000").toString());

            com.cryptoautotrader.exchange.upbit.dto.OrderResponse result =
                    upbitOrderClient.createTestOrder(market, side, null, amount, "price");

            Map<String, Object> res = new java.util.HashMap<>();
            res.put("success", true);
            res.put("uuid", result.getUuid());
            res.put("market", result.getMarket());
            res.put("side", result.getSide());
            res.put("ordType", result.getOrdType());
            res.put("price", result.getPrice());
            res.put("state", result.getState());
            res.put("createdAt", result.getCreatedAt() != null ? result.getCreatedAt().toString() : null);
            return ApiResponse.ok(res);
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Upbit 최근 주문 이력 직접 조회 (거래소 기준, 우리 DB 아님)
     * state: done | cancel | wait
     */
    @GetMapping("/upbit/exchange-orders")
    public ApiResponse<Map<String, Object>> getExchangeOrders(
            @RequestParam(defaultValue = "KRW-ETH") String market,
            @RequestParam(defaultValue = "done") String state,
            @RequestParam(defaultValue = "10") int limit) {
        if (upbitOrderClient == null) {
            return ApiResponse.ok(Map.of("error", "API 키 미설정", "orders", java.util.List.of()));
        }
        try {
            var orders = upbitOrderClient.getRecentOrders(market, state, Math.min(limit, 50));
            return ApiResponse.ok(Map.of("orders", orders, "count", orders.size()));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("error", e.getMessage(), "orders", java.util.List.of()));
        }
    }

    /**
     * 현재가(ticker) 조회 — 공개 API (인증 불필요)
     * markets: 쉼표 구분 마켓 코드 (예: KRW-BTC,KRW-ETH)
     */
    @GetMapping("/upbit/ticker")
    public ApiResponse<List<Map<String, Object>>> getTicker(
            @RequestParam(defaultValue = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE") String markets) {
        try {
            List<Map<String, Object>> tickers = upbitRestClient.getTicker(markets);
            return ApiResponse.ok(tickers);
        } catch (Exception e) {
            return ApiResponse.error("UPBIT_ERROR", e.getMessage());
        }
    }

    // ── DB 초기화 ──────────────────────────────────────────────

    // ── 서버 로그 ─────────────────────────────────────────────

    /**
     * 인메모리 서버 로그 조회
     * level: ALL | ERROR | WARN | INFO | DEBUG (쉼표로 다중 지정 가능, 예: ERROR,WARN)
     * keyword: 로거명 또는 메시지 포함 문자열 (빈 문자열 = 전체)
     * lines: 반환할 최대 라인 수 (기본 200)
     */
    @GetMapping("/server-logs")
    public ApiResponse<Map<String, Object>> getServerLogs(
            @RequestParam(defaultValue = "ALL")  String level,
            @RequestParam(defaultValue = "")     String keyword,
            @RequestParam(defaultValue = "200")  int lines) {

        List<String> levels = java.util.Arrays.asList(level.split(","));
        boolean allLevels = levels.contains("ALL");

        List<InMemoryLogBuffer.LogEntry> all = InMemoryLogBuffer.getAll();

        List<InMemoryLogBuffer.LogEntry> filtered = all.stream()
                .filter(e -> allLevels || levels.contains(e.level()))
                .filter(e -> keyword.isBlank()
                        || e.message().contains(keyword)
                        || e.logger().contains(keyword))
                .toList();

        int from = Math.max(0, filtered.size() - Math.min(lines, 2000));
        List<InMemoryLogBuffer.LogEntry> result = filtered.subList(from, filtered.size());

        return ApiResponse.ok(Map.of(
                "entries",  result,
                "total",    all.size(),
                "filtered", filtered.size(),
                "returned", result.size()
        ));
    }

    /** DB 초기화 전 레코드 수 미리보기 */
    @GetMapping("/db/stats")
    public ApiResponse<Map<String, Object>> getDbStats() {
        return ApiResponse.ok(dbResetService.getStats());
    }

    /**
     * DB 초기화 — 비밀번호 확인 후 카테고리별 삭제
     * target: BACKTEST | PAPER_TRADING | LIVE_TRADING
     */
    @PostMapping("/db/reset")
    public ApiResponse<Map<String, Object>> resetDb(
            @RequestBody Map<String, String> body) {

        String password = body.get("password");
        String target   = body.get("target");

        if (!dbResetService.checkPassword(password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다.");
        }

        if (target == null || target.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target이 필요합니다.");
        }

        Map<String, Integer> deleted = switch (target) {
            case "BACKTEST"      -> dbResetService.resetBacktest();
            case "PAPER_TRADING" -> dbResetService.resetPaperTrading();
            case "LIVE_TRADING"  -> dbResetService.resetLiveTrading();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 target: " + target);
        };

        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
        return ApiResponse.ok(Map.of("target", target, "deleted", deleted, "total", total));
    }
}
