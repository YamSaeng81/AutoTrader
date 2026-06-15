package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.service.CsvExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/export/csv")
@RequiredArgsConstructor
public class CsvExportController {

    private final CsvExportService csvExportService;

    private static final MediaType CSV_TYPE = MediaType.parseMediaType("text/csv; charset=UTF-8");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 백테스트 이력 (전체 실행 × 지표) */
    @GetMapping("/backtest")
    public ResponseEntity<byte[]> backtestHistory() {
        byte[] csv = csvExportService.exportBacktestHistory();
        return csvResponse(csv, "backtest_history_" + today() + ".csv");
    }

    /** 백테스트 거래 내역 (runId 지정 시 해당 run, 미지정 시 전체) */
    @GetMapping("/backtest/trades")
    public ResponseEntity<byte[]> backtestTrades(
            @RequestParam(required = false) Long runId) {
        byte[] csv = csvExportService.exportBacktestTrades(runId);
        String filename = runId != null
                ? "backtest_trades_run" + runId + "_" + today() + ".csv"
                : "backtest_trades_all_" + today() + ".csv";
        return csvResponse(csv, filename);
    }

    /** Walk Forward 이력 */
    @GetMapping("/walk-forward")
    public ResponseEntity<byte[]> walkForwardHistory() {
        byte[] csv = csvExportService.exportWalkForwardHistory();
        return csvResponse(csv, "walk_forward_history_" + today() + ".csv");
    }

    /** 실전매매 세션 이력 — sessionIds 지정 시 해당 세션만(운영 여부 무관), 미지정 시 전체 */
    @GetMapping("/live-trading/sessions")
    public ResponseEntity<byte[]> liveTradingSessions(
            @RequestParam(required = false) java.util.List<Long> sessionIds) {
        byte[] csv = csvExportService.exportLiveTradingSessions(sessionIds);
        return csvResponse(csv, "live_trading_sessions_" + today() + ".csv");
    }

    /** 실전매매 포지션 이력 — sessionIds 지정 시 해당 세션의 포지션만(운영 여부 무관), 미지정 시 전체 */
    @GetMapping("/live-trading/positions")
    public ResponseEntity<byte[]> liveTradingPositions(
            @RequestParam(required = false) java.util.List<Long> sessionIds) {
        byte[] csv = csvExportService.exportLiveTradingPositions(sessionIds);
        return csvResponse(csv, "live_trading_positions_" + today() + ".csv");
    }

    /** 실전매매(Upbit) 주문 로그 — sessionIds(다중)/dateFrom/dateTo 필터 (upbit-logs 화면과 동일) */
    @GetMapping("/live-trading/orders")
    public ResponseEntity<byte[]> liveTradingOrders(
            @RequestParam(required = false) java.util.List<Long> sessionIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        java.time.Instant from = dateFrom != null ? dateFrom.atStartOfDay(kst).toInstant() : null;
        java.time.Instant to   = dateTo   != null ? dateTo.plusDays(1).atStartOfDay(kst).toInstant() : null;
        byte[] csv = csvExportService.exportLiveTradingOrders(sessionIds, from, to);
        return csvResponse(csv, "live_trading_orders_" + today() + ".csv");
    }

    /** 모의투자 세션 이력 */
    @GetMapping("/paper-trading/sessions")
    public ResponseEntity<byte[]> paperTradingSessions() {
        byte[] csv = csvExportService.exportPaperTradingSessions();
        return csvResponse(csv, "paper_trading_sessions_" + today() + ".csv");
    }

    /** 모의투자 포지션 이력 */
    @GetMapping("/paper-trading/positions")
    public ResponseEntity<byte[]> paperTradingPositions() {
        byte[] csv = csvExportService.exportPaperTradingPositions();
        return csvResponse(csv, "paper_trading_positions_" + today() + ".csv");
    }

    /** 전략 로그 — sessionType(ALL|LIVE|PAPER)·sessionId 필터 (/logs 화면과 동일) */
    @GetMapping("/strategy-logs")
    public ResponseEntity<byte[]> strategyLogs(
            @RequestParam(required = false) String sessionType,
            @RequestParam(required = false) Long sessionId) {
        byte[] csv = csvExportService.exportStrategyLogs(sessionType, sessionId);
        return csvResponse(csv, "strategy_logs_" + today() + ".csv");
    }

    /** 신호 품질 분석 (days=최근N일, sessionType=ALL|LIVE|PAPER) */
    @GetMapping("/signal-quality")
    public ResponseEntity<byte[]> signalQuality(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "ALL") String sessionType) {
        byte[] csv = csvExportService.exportSignalQuality(days, sessionType);
        return csvResponse(csv, "signal_quality_" + days + "d_" + today() + ".csv");
    }

    private ResponseEntity<byte[]> csvResponse(byte[] csv, String filename) {
        return ResponseEntity.ok()
                .contentType(CSV_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodeFilename(filename))
                .body(csv);
    }

    private String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    private String encodeFilename(String filename) {
        try {
            return java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return filename;
        }
    }
}
