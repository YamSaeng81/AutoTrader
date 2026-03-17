package com.cryptoautotrader.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DB 초기화 서비스 — 카테고리별 테이블 삭제
 * 비밀번호 인증 후 사용 가능
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DbResetService {

    private static final String RESET_PASSWORD = "!Iloveyhde1";

    private final JdbcTemplate jdbc;

    public boolean checkPassword(String password) {
        return RESET_PASSWORD.equals(password);
    }

    /** 카테고리별 레코드 수 조회 (초기화 전 미리보기) */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 백테스트
        Map<String, Long> backtest = new LinkedHashMap<>();
        backtest.put("backtest_run",     count("SELECT COUNT(*) FROM backtest_run"));
        backtest.put("backtest_metrics", count("SELECT COUNT(*) FROM backtest_metrics"));
        backtest.put("backtest_trade",   count("SELECT COUNT(*) FROM backtest_trade"));
        stats.put("backtest", backtest);

        // 모의투자
        Map<String, Long> paper = new LinkedHashMap<>();
        paper.put("virtual_balance", count("SELECT COUNT(*) FROM paper_trading.virtual_balance"));
        paper.put("position",        count("SELECT COUNT(*) FROM paper_trading.position"));
        paper.put("order",           count("SELECT COUNT(*) FROM paper_trading.\"order\""));
        paper.put("strategy_log",    count("SELECT COUNT(*) FROM paper_trading.strategy_log"));
        paper.put("trade_log",       count("SELECT COUNT(*) FROM paper_trading.trade_log"));
        stats.put("paperTrading", paper);

        // 실전매매
        Map<String, Long> live = new LinkedHashMap<>();
        live.put("live_trading_session", count("SELECT COUNT(*) FROM live_trading_session"));
        live.put("position",             count("SELECT COUNT(*) FROM position WHERE session_id IS NOT NULL"));
        live.put("order",                count("SELECT COUNT(*) FROM \"order\" WHERE session_id IS NOT NULL"));
        live.put("strategy_log",         count("SELECT COUNT(*) FROM strategy_log WHERE session_type = 'LIVE'"));
        live.put("trade_log",            count("SELECT COUNT(*) FROM trade_log WHERE order_id IN (SELECT id FROM \"order\" WHERE session_id IS NOT NULL)"));
        stats.put("liveTrading", live);

        return stats;
    }

    /** 백테스트 데이터 초기화 */
    @Transactional
    public Map<String, Integer> resetBacktest() {
        log.warn("백테스트 데이터 초기화 시작");
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("backtest_trade",   jdbc.update("DELETE FROM backtest_trade"));
        result.put("backtest_metrics", jdbc.update("DELETE FROM backtest_metrics"));
        result.put("backtest_run",     jdbc.update("DELETE FROM backtest_run"));
        log.warn("백테스트 데이터 초기화 완료: {}", result);
        return result;
    }

    /** 모의투자 데이터 초기화 */
    @Transactional
    public Map<String, Integer> resetPaperTrading() {
        log.warn("모의투자 데이터 초기화 시작");
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("trade_log",     jdbc.update("DELETE FROM paper_trading.trade_log"));
        result.put("strategy_log",  jdbc.update("DELETE FROM paper_trading.strategy_log"));
        result.put("order",         jdbc.update("DELETE FROM paper_trading.\"order\""));
        result.put("position",      jdbc.update("DELETE FROM paper_trading.position"));
        result.put("virtual_balance", jdbc.update("DELETE FROM paper_trading.virtual_balance"));
        log.warn("모의투자 데이터 초기화 완료: {}", result);
        return result;
    }

    /** 실전매매 데이터 초기화 */
    @Transactional
    public Map<String, Integer> resetLiveTrading() {
        log.warn("실전매매 데이터 초기화 시작");
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("strategy_log",
                jdbc.update("DELETE FROM strategy_log WHERE session_type = 'LIVE'"));
        result.put("trade_log",
                jdbc.update("DELETE FROM trade_log WHERE order_id IN (SELECT id FROM \"order\" WHERE session_id IS NOT NULL)"));
        result.put("order",
                jdbc.update("DELETE FROM \"order\" WHERE session_id IS NOT NULL"));
        result.put("position",
                jdbc.update("DELETE FROM position WHERE session_id IS NOT NULL"));
        result.put("live_trading_session",
                jdbc.update("DELETE FROM live_trading_session"));
        log.warn("실전매매 데이터 초기화 완료: {}", result);
        return result;
    }

    private long count(String sql) {
        try {
            Long val = jdbc.queryForObject(sql, Long.class);
            return val != null ? val : 0L;
        } catch (Exception e) {
            log.warn("통계 조회 실패: {} — {}", sql, e.getMessage());
            return -1L;
        }
    }
}
