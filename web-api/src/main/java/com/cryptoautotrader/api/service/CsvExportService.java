package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.*;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.*;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CsvExportService {

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestMetricsRepository backtestMetricsRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final VirtualBalanceRepository virtualBalanceRepository;
    private final PaperPositionRepository paperPositionRepository;
    private final LiveTradingSessionRepository sessionRepository;
    private final StrategyLogRepository strategyLogRepository;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);

    // ── 백테스트 이력 ─────────────────────────────────────────────────────────

    public byte[] exportBacktestHistory() {
        List<BacktestRunEntity> runs = backtestRunRepository.findAllByOrderByCreatedAtDesc();
        if (runs.isEmpty()) return bom("데이터 없음\n");

        List<Long> runIds = runs.stream().map(BacktestRunEntity::getId).toList();
        List<BacktestMetricsEntity> allMetrics = backtestMetricsRepository.findByBacktestRunIdIn(runIds);
        Map<Long, List<BacktestMetricsEntity>> metricsMap = allMetrics.stream()
                .collect(Collectors.groupingBy(BacktestMetricsEntity::getBacktestRunId));

        StringBuilder sb = new StringBuilder();
        sb.append("runId,전략,코인페어,타임프레임,시작일,종료일,초기자금,슬리피지(%),수수료(%),");
        sb.append("Walk-Forward,WF학습종료일,WF테스트종료일,세그먼트,");
        sb.append("총수익률(%),승률(%),최대낙폭(%),샤프비율,소르티노비율,칼마비율,손익비,회복계수,");
        sb.append("총거래,승리거래,패배거래,평균수익(%),평균손실(%),최대연속손실,생성일시\n");

        for (BacktestRunEntity run : runs) {
            List<BacktestMetricsEntity> metricsList = metricsMap.getOrDefault(run.getId(), List.of());
            if (metricsList.isEmpty()) {
                appendBacktestRow(sb, run, null);
            } else {
                for (BacktestMetricsEntity m : metricsList) {
                    appendBacktestRow(sb, run, m);
                }
            }
        }
        return bom(sb.toString());
    }

    private void appendBacktestRow(StringBuilder sb, BacktestRunEntity run, BacktestMetricsEntity m) {
        sb.append(run.getId()).append(',');
        sb.append(q(run.getStrategyName())).append(',');
        sb.append(q(run.getCoinPair())).append(',');
        sb.append(q(run.getTimeframe())).append(',');
        sb.append(fmt(run.getStartDate())).append(',');
        sb.append(fmt(run.getEndDate())).append(',');
        sb.append(run.getInitialCapital()).append(',');
        sb.append(run.getSlippagePct()).append(',');
        sb.append(run.getFeePct()).append(',');
        sb.append(Boolean.TRUE.equals(run.getIsWalkForward())).append(',');
        sb.append(fmt(run.getWfInSample())).append(',');
        sb.append(fmt(run.getWfOutSample())).append(',');

        if (m != null) {
            sb.append(q(m.getSegment())).append(',');
            sb.append(m.getTotalReturnPct()).append(',');
            sb.append(m.getWinRatePct()).append(',');
            sb.append(m.getMddPct()).append(',');
            sb.append(m.getSharpeRatio()).append(',');
            sb.append(m.getSortinoRatio()).append(',');
            sb.append(m.getCalmarRatio()).append(',');
            sb.append(m.getWinLossRatio()).append(',');
            sb.append(m.getRecoveryFactor()).append(',');
            sb.append(m.getTotalTrades()).append(',');
            sb.append(m.getWinningTrades()).append(',');
            sb.append(m.getLosingTrades()).append(',');
            sb.append(m.getAvgProfitPct()).append(',');
            sb.append(m.getAvgLossPct()).append(',');
            sb.append(m.getMaxConsecutiveLoss()).append(',');
        } else {
            sb.append(",,,,,,,,,,,,,,");
        }
        sb.append(fmt(run.getCreatedAt())).append('\n');
    }

    // ── 백테스트 거래 내역 ────────────────────────────────────────────────────

    public byte[] exportBacktestTrades(Long backtestRunId) {
        List<BacktestTradeEntity> trades;
        Map<Long, BacktestRunEntity> runMap = new HashMap<>();

        if (backtestRunId != null) {
            trades = backtestTradeRepository.findByBacktestRunIdOrderByExecutedAtAsc(backtestRunId);
            backtestRunRepository.findById(backtestRunId).ifPresent(r -> runMap.put(r.getId(), r));
        } else {
            trades = backtestTradeRepository.findAll(Sort.by("executedAt").ascending());
            backtestRunRepository.findAll().forEach(r -> runMap.put(r.getId(), r));
        }

        if (trades.isEmpty()) return bom("데이터 없음\n");

        StringBuilder sb = new StringBuilder();
        sb.append("tradeId,runId,전략,코인페어,타임프레임,");
        sb.append("방향,체결가,수량,수수료,슬리피지,손익,누적손익,신호이유,시장레짐,체결일시\n");

        for (BacktestTradeEntity t : trades) {
            BacktestRunEntity run = runMap.get(t.getBacktestRunId());
            sb.append(t.getId()).append(',');
            sb.append(t.getBacktestRunId()).append(',');
            sb.append(run != null ? q(run.getStrategyName()) : "").append(',');
            sb.append(run != null ? q(run.getCoinPair()) : "").append(',');
            sb.append(run != null ? q(run.getTimeframe()) : "").append(',');
            sb.append(q(t.getSide())).append(',');
            sb.append(t.getPrice()).append(',');
            sb.append(t.getQuantity()).append(',');
            sb.append(t.getFee()).append(',');
            sb.append(t.getSlippage()).append(',');
            sb.append(t.getPnl()).append(',');
            sb.append(t.getCumulativePnl()).append(',');
            sb.append(q(t.getSignalReason())).append(',');
            sb.append(q(t.getMarketRegime())).append(',');
            sb.append(fmt(t.getExecutedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    // ── Walk Forward 이력 ─────────────────────────────────────────────────────

    public byte[] exportWalkForwardHistory() {
        List<BacktestRunEntity> runs = backtestRunRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsWalkForward()))
                .toList();

        if (runs.isEmpty()) return bom("데이터 없음\n");

        // WF 결과는 BacktestRunEntity.wfResultJson(JSONB)에 저장됨.
        // 과거 호환을 위해 BacktestMetricsEntity fallback도 유지.
        List<Long> runIds = runs.stream().map(BacktestRunEntity::getId).toList();
        List<BacktestMetricsEntity> allMetrics = backtestMetricsRepository.findByBacktestRunIdIn(runIds);
        Map<Long, List<BacktestMetricsEntity>> metricsMap = allMetrics.stream()
                .collect(Collectors.groupingBy(BacktestMetricsEntity::getBacktestRunId));

        StringBuilder sb = new StringBuilder();
        sb.append("runId,전략,코인페어,타임프레임,전체시작일,전체종료일,학습기간종료일,테스트기간종료일,초기자금,");
        sb.append("세그먼트,총수익률(%),승률(%),최대낙폭(%),샤프비율,소르티노비율,칼마비율,");
        sb.append("총거래,승리거래,패배거래,평균수익(%),평균손실(%),생성일시\n");

        for (BacktestRunEntity run : runs) {
            Map<String, Object> wf = run.getWfResultJson();
            boolean wroteFromJson = false;

            if (wf != null) {
                Object windowsObj = wf.get("windows");
                if (windowsObj instanceof List<?> windows) {
                    for (Object wObj : windows) {
                        if (!(wObj instanceof Map<?, ?> w)) continue;
                        Object idx = w.get("windowIndex");
                        Map<String, Object> in  = asMap(w.get("inSample"));
                        Map<String, Object> out = asMap(w.get("outSample"));
                        String trainEnd = str(in != null ? in.get("end") : null);
                        String testEnd  = str(out != null ? out.get("end") : null);
                        if (in != null) {
                            appendWfJsonRow(sb, run, "W" + idx + "_IN", trainEnd, "", in);
                            wroteFromJson = true;
                        }
                        if (out != null) {
                            appendWfJsonRow(sb, run, "W" + idx + "_OUT", trainEnd, testEnd, out);
                            wroteFromJson = true;
                        }
                    }
                }
                Map<String, Object> agg = asMap(wf.get("aggregatedOutSample"));
                if (agg != null) {
                    appendWfJsonRow(sb, run, "AGG_OUT",
                            fmt(run.getWfInSample()), fmt(run.getWfOutSample()), agg);
                    wroteFromJson = true;
                }
            }

            if (wroteFromJson) continue;

            // Fallback: metrics 테이블 기반 (레거시 데이터)
            List<BacktestMetricsEntity> metricsList = metricsMap.getOrDefault(run.getId(), List.of());
            if (metricsList.isEmpty()) {
                appendWfRow(sb, run, null);
            } else {
                for (BacktestMetricsEntity m : metricsList) {
                    appendWfRow(sb, run, m);
                }
            }
        }
        return bom(sb.toString());
    }

    private void appendWfJsonRow(StringBuilder sb, BacktestRunEntity run,
                                 String segment, String trainEnd, String testEnd,
                                 Map<String, Object> metrics) {
        sb.append(run.getId()).append(',');
        sb.append(q(run.getStrategyName())).append(',');
        sb.append(q(run.getCoinPair())).append(',');
        sb.append(q(run.getTimeframe())).append(',');
        sb.append(fmt(run.getStartDate())).append(',');
        sb.append(fmt(run.getEndDate())).append(',');
        sb.append(q(normalizeDt(trainEnd))).append(',');
        sb.append(q(normalizeDt(testEnd))).append(',');
        sb.append(run.getInitialCapital()).append(',');
        sb.append(q(segment)).append(',');
        sb.append(num(metrics.get("totalReturn"))).append(',');
        sb.append(num(metrics.get("winRate"))).append(',');
        sb.append(num(metrics.get("maxDrawdown"))).append(',');
        sb.append(num(metrics.get("sharpeRatio"))).append(',');
        sb.append(num(metrics.get("sortinoRatio"))).append(',');
        sb.append(num(metrics.get("calmarRatio"))).append(',');
        sb.append(num(metrics.get("totalTrades"))).append(',');
        // 승리/패배/평균수익/평균손실은 JSON에 없음 → blank
        sb.append(",,,,");
        sb.append(fmt(run.getCreatedAt())).append('\n');
    }

    private void appendWfRow(StringBuilder sb, BacktestRunEntity run, BacktestMetricsEntity m) {
        sb.append(run.getId()).append(',');
        sb.append(q(run.getStrategyName())).append(',');
        sb.append(q(run.getCoinPair())).append(',');
        sb.append(q(run.getTimeframe())).append(',');
        sb.append(fmt(run.getStartDate())).append(',');
        sb.append(fmt(run.getEndDate())).append(',');
        sb.append(fmt(run.getWfInSample())).append(',');
        sb.append(fmt(run.getWfOutSample())).append(',');
        sb.append(run.getInitialCapital()).append(',');

        if (m != null) {
            sb.append(q(m.getSegment())).append(',');
            sb.append(m.getTotalReturnPct()).append(',');
            sb.append(m.getWinRatePct()).append(',');
            sb.append(m.getMddPct()).append(',');
            sb.append(m.getSharpeRatio()).append(',');
            sb.append(m.getSortinoRatio()).append(',');
            sb.append(m.getCalmarRatio()).append(',');
            sb.append(m.getTotalTrades()).append(',');
            sb.append(m.getWinningTrades()).append(',');
            sb.append(m.getLosingTrades()).append(',');
            sb.append(m.getAvgProfitPct()).append(',');
            sb.append(m.getAvgLossPct()).append(',');
        } else {
            sb.append(",,,,,,,,,,,,");
        }
        sb.append(fmt(run.getCreatedAt())).append('\n');
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String num(Object o) {
        return o == null ? "" : o.toString();
    }

    /** "2026-04-24T00:00:00Z" 형태의 ISO 문자열을 "yyyy-MM-dd HH:mm:ss" KST로 변환 (실패 시 원문 반환). */
    private String normalizeDt(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return DT_FMT.format(Instant.parse(s));
        } catch (Exception e) {
            return s;
        }
    }

    // ── 실전매매 이력 ─────────────────────────────────────────────────────────

    public byte[] exportLiveTradingSessions() {
        List<LiveTradingSessionEntity> sessions = sessionRepository.findAllByOrderByCreatedAtDesc();

        StringBuilder sb = new StringBuilder();
        sb.append("세션ID,전략,코인페어,타임프레임,초기자금,현재자산,가용KRW,수익률(%),");
        sb.append("투자비율,손절(%),상태,CB발동사유,시작일시,종료일시,생성일시\n");

        for (LiveTradingSessionEntity s : sessions) {
            BigDecimal returnPct = calcReturnPct(s.getInitialCapital(), s.getTotalAssetKrw());
            sb.append(s.getId()).append(',');
            sb.append(q(s.getStrategyType())).append(',');
            sb.append(q(s.getCoinPair())).append(',');
            sb.append(q(s.getTimeframe())).append(',');
            sb.append(s.getInitialCapital()).append(',');
            sb.append(s.getTotalAssetKrw()).append(',');
            sb.append(s.getAvailableKrw()).append(',');
            sb.append(returnPct).append(',');
            sb.append(s.getInvestRatio()).append(',');
            sb.append(s.getStopLossPct()).append(',');
            sb.append(q(s.getStatus())).append(',');
            sb.append(q(s.getCircuitBreakerReason())).append(',');
            sb.append(fmt(s.getStartedAt())).append(',');
            sb.append(fmt(s.getStoppedAt())).append(',');
            sb.append(fmt(s.getCreatedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    public byte[] exportLiveTradingPositions() {
        List<PositionEntity> positions = positionRepository.findAll(
                Sort.by(Sort.Direction.DESC, "openedAt"));

        Map<Long, LiveTradingSessionEntity> sessionMap = new HashMap<>();
        sessionRepository.findAll().forEach(s -> sessionMap.put(s.getId(), s));

        StringBuilder sb = new StringBuilder();
        sb.append("포지션ID,세션ID,전략,코인페어,방향,진입가,평균가,수량,투자KRW,");
        sb.append("실현손익,포지션수수료,수익률(%),상태,시장레짐,손절가,익절가,진입일시,청산일시\n");

        for (PositionEntity p : positions) {
            LiveTradingSessionEntity s = p.getSessionId() != null ? sessionMap.get(p.getSessionId()) : null;
            BigDecimal returnPct = calcPositionReturnPct(p.getInvestedKrw(), p.getRealizedPnl());
            sb.append(p.getId()).append(',');
            sb.append(p.getSessionId()).append(',');
            sb.append(s != null ? q(s.getStrategyType()) : "").append(',');
            sb.append(q(p.getCoinPair())).append(',');
            sb.append(q(p.getSide())).append(',');
            sb.append(p.getEntryPrice()).append(',');
            sb.append(p.getAvgPrice()).append(',');
            sb.append(p.getSize()).append(',');
            sb.append(p.getInvestedKrw()).append(',');
            sb.append(p.getRealizedPnl()).append(',');
            sb.append(p.getPositionFee()).append(',');
            sb.append(returnPct).append(',');
            sb.append(q(p.getStatus())).append(',');
            sb.append(q(p.getMarketRegime())).append(',');
            sb.append(p.getStopLossPrice()).append(',');
            sb.append(p.getTakeProfitPrice()).append(',');
            sb.append(fmt(p.getOpenedAt())).append(',');
            sb.append(fmt(p.getClosedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    // ── 모의투자 이력 ─────────────────────────────────────────────────────────

    public byte[] exportPaperTradingSessions() {
        List<VirtualBalanceEntity> sessions = virtualBalanceRepository.findAllByOrderByIdDesc();

        StringBuilder sb = new StringBuilder();
        sb.append("세션ID,전략,코인페어,타임프레임,초기자금,총자산KRW,가용KRW,");
        sb.append("실현손익,총수수료,수익률(%),상태,시작일시,종료일시\n");

        for (VirtualBalanceEntity s : sessions) {
            BigDecimal returnPct = calcReturnPct(s.getInitialCapital(), s.getTotalKrw());
            sb.append(s.getId()).append(',');
            sb.append(q(s.getStrategyName())).append(',');
            sb.append(q(s.getCoinPair())).append(',');
            sb.append(q(s.getTimeframe())).append(',');
            sb.append(s.getInitialCapital()).append(',');
            sb.append(s.getTotalKrw()).append(',');
            sb.append(s.getAvailableKrw()).append(',');
            sb.append(s.getRealizedPnl()).append(',');
            sb.append(s.getTotalFee()).append(',');
            sb.append(returnPct).append(',');
            sb.append(q(s.getStatus())).append(',');
            sb.append(fmt(s.getStartedAt())).append(',');
            sb.append(fmt(s.getStoppedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    public byte[] exportPaperTradingPositions() {
        List<PaperPositionEntity> positions = paperPositionRepository.findAll(
                Sort.by(Sort.Direction.DESC, "openedAt"));

        StringBuilder sb = new StringBuilder();
        sb.append("포지션ID,세션ID,코인페어,방향,진입가,평균가,수량,");
        sb.append("실현손익,포지션수수료,수익률(%),상태,손절가,익절가,진입일시,청산일시\n");

        for (PaperPositionEntity p : positions) {
            BigDecimal returnPct = calcPositionReturnPctFromEntry(p.getEntryPrice(), p.getAvgPrice(), p.getRealizedPnl(), p.getSize());
            sb.append(p.getId()).append(',');
            sb.append(p.getSessionId()).append(',');
            sb.append(q(p.getCoinPair())).append(',');
            sb.append(q(p.getSide())).append(',');
            sb.append(p.getEntryPrice()).append(',');
            sb.append(p.getAvgPrice()).append(',');
            sb.append(p.getSize()).append(',');
            sb.append(p.getRealizedPnl()).append(',');
            sb.append(p.getPositionFee()).append(',');
            sb.append(returnPct).append(',');
            sb.append(q(p.getStatus())).append(',');
            sb.append(p.getStopLossPrice()).append(',');
            sb.append(p.getTakeProfitPrice()).append(',');
            sb.append(fmt(p.getOpenedAt())).append(',');
            sb.append(fmt(p.getClosedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    // ── 신호 품질 분석 ────────────────────────────────────────────────────────

    public byte[] exportSignalQuality(int days, String sessionType) {
        Instant from = Instant.now().minusSeconds((long) days * 86400);
        boolean hasType = sessionType != null && !sessionType.isBlank() && !"ALL".equalsIgnoreCase(sessionType);

        List<StrategyLogEntity> logs;
        if (hasType) {
            logs = strategyLogRepository.findByPeriodAndSessionType(
                    sessionType.toUpperCase(), from, Instant.now());
        } else {
            logs = strategyLogRepository.findByPeriod(from, Instant.now());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ID,전략,코인페어,신호,신호이유,시장레짐,세션타입,세션ID,");
        sb.append("신호가격,실행여부,차단사유,4시간후가격,4시간수익률(%),");
        sb.append("24시간후가격,24시간수익률(%),신뢰도점수,발생일시\n");

        for (StrategyLogEntity l : logs) {
            sb.append(l.getId()).append(',');
            sb.append(q(l.getStrategyName())).append(',');
            sb.append(q(l.getCoinPair())).append(',');
            sb.append(q(l.getSignal())).append(',');
            sb.append(q(l.getReason())).append(',');
            sb.append(q(l.getMarketRegime())).append(',');
            sb.append(q(l.getSessionType())).append(',');
            sb.append(l.getSessionId()).append(',');
            sb.append(l.getSignalPrice()).append(',');
            sb.append(l.isWasExecuted()).append(',');
            sb.append(q(l.getBlockedReason())).append(',');
            sb.append(l.getPriceAfter4h()).append(',');
            sb.append(l.getReturn4hPct()).append(',');
            sb.append(l.getPriceAfter24h()).append(',');
            sb.append(l.getReturn24hPct()).append(',');
            sb.append(l.getConfidenceScore()).append(',');
            sb.append(fmt(l.getCreatedAt())).append('\n');
        }
        return bom(sb.toString());
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private String fmt(Instant instant) {
        if (instant == null) return "";
        return DT_FMT.format(instant);
    }

    /** CSV 필드 이스케이프: 쉼표/줄바꿈/따옴표 포함 시 따옴표로 감싸고 내부 따옴표는 "" 처리 */
    private String q(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private BigDecimal calcReturnPct(BigDecimal initial, BigDecimal current) {
        if (initial == null || initial.compareTo(BigDecimal.ZERO) == 0 || current == null) return BigDecimal.ZERO;
        return current.subtract(initial)
                .divide(initial, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calcPositionReturnPct(BigDecimal invested, BigDecimal realizedPnl) {
        if (invested == null || invested.compareTo(BigDecimal.ZERO) == 0 || realizedPnl == null) return BigDecimal.ZERO;
        return realizedPnl.divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calcPositionReturnPctFromEntry(BigDecimal entryPrice, BigDecimal avgPrice, BigDecimal realizedPnl, BigDecimal size) {
        if (realizedPnl == null || entryPrice == null || size == null
                || entryPrice.compareTo(BigDecimal.ZERO) == 0 || size.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal invested = entryPrice.multiply(size);
        return calcPositionReturnPct(invested, realizedPnl);
    }

    /** UTF-8 BOM 추가 (Excel 한글 깨짐 방지) */
    private byte[] bom(String csv) {
        byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] content = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }
}
