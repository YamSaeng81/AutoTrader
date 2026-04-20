package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.discord.DiscordWebhookClient;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모의매매 → 실전매매 자동 승격 파이프라인.
 *
 * <p>매일 08:00 KST(UTC 23:00) 에 PAPER 세션을 점검하여 아래 조건을 모두 충족하면
 * Discord로 승격 추천 알림을 전송한다.
 *
 * <ul>
 *   <li>운영 기간 ≥ {@value MIN_DAYS}일</li>
 *   <li>평가 완료 BUY/SELL 신호 ≥ {@value MIN_SIGNAL_COUNT}건 (통계 신뢰도)</li>
 *   <li>4h 방향 적중률 ≥ {@value MIN_ACCURACY_PCT}%</li>
 *   <li>기대값(EV) ≥ {@value MIN_EV_PCT}%</li>
 *   <li>현재 MDD(피크 대비 자산 낙폭) ≤ {@value MAX_MDD_PCT}%</li>
 * </ul>
 *
 * <p>한 번 추천된 세션은 재시작 전까지 중복 알림을 보내지 않는다 (in-memory set).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperSessionPromotionService {

    private static final int    MIN_DAYS         = 30;   // 최소 운영 기간 (일)
    private static final int    MIN_SIGNAL_COUNT = 20;   // 통계 신뢰도용 최소 신호 수
    private static final double MIN_ACCURACY_PCT = 60.0; // 최소 4h 적중률 (%)
    private static final double MIN_EV_PCT       = 0.1;  // 최소 기대값 (%)
    private static final double MAX_MDD_PCT      = 10.0; // 최대 허용 MDD (%)

    private static final String CHANNEL_TYPE = "TRADING_REPORT";
    private static final String MESSAGE_TYPE = "PAPER_PROMOTION";

    private static final DateTimeFormatter KST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final LiveTradingSessionRepository sessionRepo;
    private final StrategyLogRepository        strategyLogRepo;
    private final DiscordWebhookClient         discordClient;

    /** 이번 프로세스 기동 후 이미 알림을 보낸 세션 ID (중복 방지) */
    private final Set<Long> notifiedSessions = ConcurrentHashMap.newKeySet();

    // ── 스케줄 ────────────────────────────────────────────────────────────────

    /** 매일 08:00 KST (UTC 23:00) */
    @Scheduled(cron = "0 0 23 * * *")
    public void checkPromotionCandidates() {
        log.info("[PaperPromotion] 모의 세션 승격 검사 시작");
        try {
            List<LiveTradingSessionEntity> paperSessions =
                    sessionRepo.findBySessionTypeAndStatus("PAPER", "RUNNING");
            log.info("[PaperPromotion] PAPER RUNNING 세션 {}개 점검", paperSessions.size());

            int promoted = 0;
            for (LiveTradingSessionEntity session : paperSessions) {
                if (notifiedSessions.contains(session.getId())) continue;
                PromotionResult result = evaluate(session);
                if (result.eligible()) {
                    sendPromotionAlert(session, result);
                    notifiedSessions.add(session.getId());
                    promoted++;
                }
            }
            log.info("[PaperPromotion] 승격 추천 {}건 전송", promoted);
        } catch (Exception e) {
            log.error("[PaperPromotion] 검사 중 오류", e);
        }
    }

    // ── 평가 로직 ─────────────────────────────────────────────────────────────

    PromotionResult evaluate(LiveTradingSessionEntity session) {
        Instant now = Instant.now();

        // 1. 운영 기간
        long operatingDays = session.getStartedAt() != null
                ? ChronoUnit.DAYS.between(session.getStartedAt(), now) : 0;
        if (operatingDays < MIN_DAYS) {
            return PromotionResult.notEligible("운영 기간 부족 (" + operatingDays + "d < " + MIN_DAYS + "d)");
        }

        // 2. 신호 품질 — 평가 완료 BUY/SELL 신호 조회
        List<StrategyLogEntity> signals = strategyLogRepo.findEvaluatedSignalsBySessionId(session.getId());
        if (signals.size() < MIN_SIGNAL_COUNT) {
            return PromotionResult.notEligible("신호 샘플 부족 (" + signals.size() + "건 < " + MIN_SIGNAL_COUNT + "건)");
        }

        // 3. 4h 적중률
        double accuracy4h = calcAccuracy(signals);
        if (accuracy4h < MIN_ACCURACY_PCT) {
            return PromotionResult.notEligible(String.format("4h 적중률 부족 (%.1f%% < %.0f%%)", accuracy4h, MIN_ACCURACY_PCT));
        }

        // 4. 기대값(EV)
        double ev = calcEV(signals);
        if (ev < MIN_EV_PCT / 100.0) {
            return PromotionResult.notEligible(String.format("EV 부족 (%.3f%% < %.1f%%)", ev * 100, MIN_EV_PCT));
        }

        // 5. MDD
        double mddPct = calcMdd(session);
        if (mddPct > MAX_MDD_PCT) {
            return PromotionResult.notEligible(String.format("MDD 초과 (%.1f%% > %.0f%%)", mddPct, MAX_MDD_PCT));
        }

        // 현재 수익률
        double returnPct = calcReturn(session);

        return PromotionResult.eligible(operatingDays, signals.size(), accuracy4h, ev * 100, mddPct, returnPct);
    }

    // ── 알림 전송 ─────────────────────────────────────────────────────────────

    private void sendPromotionAlert(LiveTradingSessionEntity s, PromotionResult r) {
        String desc = String.format(
                "**%s** × %s [%s]\n" +
                "운영 기간: **%d일** | 신호: %d건\n" +
                "4h 적중률: **%.1f%%** | EV: **+%.2f%%**\n" +
                "수익률: **%+.2f%%** | MDD: %.1f%%\n" +
                "세션 ID: %d | 시작: %s",
                s.getStrategyType(), s.getCoinPair(), s.getTimeframe(),
                r.operatingDays, r.signalCount,
                r.accuracy4h, r.evPct,
                r.returnPct, r.mddPct,
                s.getId(),
                s.getStartedAt() != null ? KST_FMT.format(s.getStartedAt()) : "-");

        ObjectNode embed = discordClient.embed(
                "🚀 모의매매 실전 승격 추천",
                desc,
                DiscordWebhookClient.COLOR_GREEN);

        boolean sent = discordClient.sendEmbed(CHANNEL_TYPE, embed, MESSAGE_TYPE);
        log.info("[PaperPromotion] 세션 {} ({} × {}) 승격 추천 알림 {}",
                s.getId(), s.getStrategyType(), s.getCoinPair(),
                sent ? "전송 성공" : "전송 실패(채널 미설정 또는 오류)");
    }

    // ── 계산 헬퍼 ─────────────────────────────────────────────────────────────

    private static final BigDecimal FEE_THRESHOLD = TradingConstants.FEE_THRESHOLD;

    private double calcAccuracy(List<StrategyLogEntity> signals) {
        List<StrategyLogEntity> evaluated = signals.stream()
                .filter(l -> l.getReturn4hPct() != null).toList();
        if (evaluated.isEmpty()) return 0.0;
        long wins = evaluated.stream()
                .filter(l -> l.getReturn4hPct().compareTo(FEE_THRESHOLD) > 0).count();
        return wins * 100.0 / evaluated.size();
    }

    /** EV(기대값) = win_rate × avg_win + loss_rate × avg_loss (소수 단위 반환) */
    private double calcEV(List<StrategyLogEntity> signals) {
        List<StrategyLogEntity> evaluated = signals.stream()
                .filter(l -> l.getReturn4hPct() != null).toList();
        if (evaluated.isEmpty()) return 0.0;

        List<BigDecimal> wins   = evaluated.stream()
                .filter(l -> l.getReturn4hPct().compareTo(FEE_THRESHOLD) > 0)
                .map(StrategyLogEntity::getReturn4hPct).toList();
        List<BigDecimal> losses = evaluated.stream()
                .filter(l -> l.getReturn4hPct().compareTo(FEE_THRESHOLD) <= 0)
                .map(StrategyLogEntity::getReturn4hPct).toList();

        double n        = evaluated.size();
        double winRate  = wins.size()   / n;
        double lossRate = losses.size() / n;
        double avgWin   = wins.isEmpty()   ? 0.0
                : wins.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(wins.size()), 6, RoundingMode.HALF_UP).doubleValue();
        double avgLoss  = losses.isEmpty() ? 0.0
                : losses.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(losses.size()), 6, RoundingMode.HALF_UP).doubleValue();

        return winRate * avgWin + lossRate * avgLoss; // avgLoss는 음수
    }

    /** 현재 MDD = (mddPeakCapital − totalAssetKrw) / mddPeakCapital × 100 */
    private double calcMdd(LiveTradingSessionEntity s) {
        if (s.getMddPeakCapital() == null || s.getMddPeakCapital().compareTo(BigDecimal.ZERO) <= 0) {
            // mddPeakCapital 미설정 시 initialCapital로 대체
            if (s.getInitialCapital() == null || s.getInitialCapital().compareTo(BigDecimal.ZERO) <= 0) return 0.0;
            double ret = (s.getTotalAssetKrw().doubleValue() - s.getInitialCapital().doubleValue())
                    / s.getInitialCapital().doubleValue() * 100;
            return ret < 0 ? -ret : 0.0;
        }
        double peak = s.getMddPeakCapital().doubleValue();
        double curr = s.getTotalAssetKrw().doubleValue();
        return Math.max(0, (peak - curr) / peak * 100);
    }

    /** 세션 수익률 (%) = (totalAssetKrw − initialCapital) / initialCapital × 100 */
    private double calcReturn(LiveTradingSessionEntity s) {
        if (s.getInitialCapital() == null || s.getInitialCapital().compareTo(BigDecimal.ZERO) <= 0) return 0.0;
        return (s.getTotalAssetKrw().doubleValue() - s.getInitialCapital().doubleValue())
                / s.getInitialCapital().doubleValue() * 100;
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    record PromotionResult(
            boolean eligible,
            String reason,          // 부적격 시 사유
            long operatingDays,
            int signalCount,
            double accuracy4h,
            double evPct,
            double mddPct,
            double returnPct) {

        static PromotionResult notEligible(String reason) {
            return new PromotionResult(false, reason, 0, 0, 0, 0, 0, 0);
        }

        static PromotionResult eligible(long days, int count,
                                         double accuracy, double evPct,
                                         double mdd, double ret) {
            return new PromotionResult(true, null, days, count, accuracy, evPct, mdd, ret);
        }
    }
}
