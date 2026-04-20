package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.discord.DiscordWebhookClient;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.util.TradingConstants;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 전략 성과 저하 감지 워치독.
 *
 * <p>매 6시간마다 실전(REAL) 세션의 신호 품질 데이터를 분석해
 * 7일 적중률이 30일 기준선 대비 임계값 이상 하락하면 Discord로 경보를 전송한다.
 *
 * <ul>
 *   <li>WARN  — 7d 적중률이 30d 대비 {@value WARN_DROP_PCT}%p 이상 하락</li>
 *   <li>CRIT  — 7d 적중률이 30d 대비 {@value CRIT_DROP_PCT}%p 이상 하락</li>
 *   <li>NEG_EV — 7d 평균 4h 수익률이 음수 (수수료 포함 기댓값 마이너스)</li>
 * </ul>
 *
 * <p>최소 샘플({@value MIN_SAMPLE}건) 미만이면 해당 전략×코인은 평가 생략한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDegradationWatchdog {

    private static final int    MIN_SAMPLE      = 10;   // 평가에 필요한 최소 신호 수
    private static final double WARN_DROP_PCT   = 15.0; // 경고 임계값 (%p)
    private static final double CRIT_DROP_PCT   = 25.0; // 심각 임계값 (%p)

    private static final String CHANNEL_TYPE  = "TRADING_REPORT";
    private static final String MESSAGE_TYPE  = "DEGRADATION_ALERT";

    private final StrategyLogRepository strategyLogRepo;
    private final DiscordWebhookClient  discordClient;

    // ── 스케줄 ────────────────────────────────────────────────────────────────

    /** 매 6시간 실행 (03:00 / 09:00 / 15:00 / 21:00 KST) */
    @Scheduled(cron = "0 0 0,6,12,18 * * *", zone = "Asia/Seoul")
    public void check() {
        log.info("[DegradationWatchdog] 성과 저하 검사 시작");
        try {
            List<Alert> alerts = evaluate();
            if (alerts.isEmpty()) {
                log.info("[DegradationWatchdog] 이상 없음");
                return;
            }
            sendAlerts(alerts);
        } catch (Exception e) {
            log.error("[DegradationWatchdog] 검사 중 오류", e);
        }
    }

    // ── 핵심 로직 ─────────────────────────────────────────────────────────────

    /**
     * 전략×코인별 7d/30d 적중률을 비교해 Alert 목록을 반환한다.
     */
    List<Alert> evaluate() {
        Instant now   = Instant.now();
        Instant from7d  = now.minus(7,  ChronoUnit.DAYS);
        Instant from30d = now.minus(30, ChronoUnit.DAYS);

        List<StrategyLogEntity> logs30d = strategyLogRepo.findEvaluatedSignalsBySessionType("REAL", from30d);

        // strategy:coin → 30d 로그 / 7d 로그
        Map<String, List<StrategyLogEntity>> by30d = groupByKey(logs30d);
        Map<String, List<StrategyLogEntity>> by7d  = groupByKey(
                logs30d.stream().filter(l -> l.getCreatedAt().isAfter(from7d)).toList());

        List<Alert> alerts = new ArrayList<>();

        for (Map.Entry<String, List<StrategyLogEntity>> entry : by30d.entrySet()) {
            String key = entry.getKey();
            List<StrategyLogEntity> logs30 = entry.getValue();
            List<StrategyLogEntity> logs7  = by7d.getOrDefault(key, List.of());

            if (logs30.size() < MIN_SAMPLE || logs7.size() < MIN_SAMPLE / 2) continue;

            double rate30 = calcWinRate(logs30);
            double rate7  = calcWinRate(logs7);
            double avgRet7 = calcAvgReturn(logs7);
            double drop   = rate30 - rate7;

            String[] parts = key.split(":", 2);
            String strategy = parts[0];
            String coin     = parts.length > 1 ? parts[1] : "?";

            if (drop >= CRIT_DROP_PCT) {
                alerts.add(new Alert(AlertLevel.CRIT, strategy, coin, rate30, rate7, drop, avgRet7, logs7.size()));
            } else if (drop >= WARN_DROP_PCT) {
                alerts.add(new Alert(AlertLevel.WARN, strategy, coin, rate30, rate7, drop, avgRet7, logs7.size()));
            } else if (avgRet7 < -TradingConstants.FEE_THRESHOLD.doubleValue()) {
                // 적중률 하락은 없어도 기댓값이 음수면 경고
                alerts.add(new Alert(AlertLevel.NEG_EV, strategy, coin, rate30, rate7, drop, avgRet7, logs7.size()));
            }
        }

        alerts.sort(Comparator.comparing((Alert a) -> a.level).reversed());
        return alerts;
    }

    // ── 알림 전송 ─────────────────────────────────────────────────────────────

    private void sendAlerts(List<Alert> alerts) {
        StringBuilder desc = new StringBuilder();
        for (Alert a : alerts) {
            desc.append(a.level.emoji()).append(" **").append(a.strategy)
                .append("** × ").append(a.coin).append("\n");
            desc.append(String.format(
                "30d 적중률 %.1f%% → 7d %.1f%%  (▼%.1f%%p)  |  7d 평균수익 %.3f%%  |  샘플 %d건\n\n",
                a.rate30, a.rate7, a.drop, a.avgRet7, a.sample));
        }

        int maxLevel = alerts.stream().mapToInt(a -> a.level.ordinal()).max().orElse(0);
        int color = maxLevel >= AlertLevel.CRIT.ordinal()
                ? DiscordWebhookClient.COLOR_RED
                : DiscordWebhookClient.COLOR_YELLOW;

        ObjectNode embed = discordClient.embed(
                "⚠️ 전략 성과 저하 감지 — " + alerts.size() + "건",
                desc.toString().trim(),
                color);

        boolean sent = discordClient.sendEmbed(CHANNEL_TYPE, embed, MESSAGE_TYPE);
        log.info("[DegradationWatchdog] 경보 {} 건 전송 {}",
                alerts.size(), sent ? "성공" : "실패(채널 미설정 또는 오류)");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Map<String, List<StrategyLogEntity>> groupByKey(List<StrategyLogEntity> logs) {
        return logs.stream().collect(Collectors.groupingBy(
                l -> (l.getStrategyName() != null ? l.getStrategyName() : "?")
                     + ":" + (l.getCoinPair() != null ? l.getCoinPair() : "?")));
    }

    private double calcWinRate(List<StrategyLogEntity> logs) {
        List<StrategyLogEntity> eval = logs.stream()
                .filter(l -> l.getReturn4hPct() != null).toList();
        if (eval.isEmpty()) return 0.0;
        long wins = eval.stream()
                .filter(l -> l.getReturn4hPct().compareTo(TradingConstants.FEE_THRESHOLD) > 0)
                .count();
        return wins * 100.0 / eval.size();
    }

    private double calcAvgReturn(List<StrategyLogEntity> logs) {
        List<BigDecimal> returns = logs.stream()
                .map(StrategyLogEntity::getReturn4hPct)
                .filter(Objects::nonNull)
                .toList();
        if (returns.isEmpty()) return 0.0;
        return returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    enum AlertLevel {
        NEG_EV, WARN, CRIT;

        String emoji() {
            return switch (this) {
                case CRIT   -> "🔴";
                case WARN   -> "🟡";
                case NEG_EV -> "🟠";
            };
        }
    }

    record Alert(AlertLevel level, String strategy, String coin,
                 double rate30, double rate7, double drop,
                 double avgRet7, int sample) {}
}
