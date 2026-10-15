package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 포지션별 한도의 <b>감시와 집행</b> (2026-10-15, 선행 조건 1번 1-g). 판정은 {@link PositionLimitGuard} 가 한다.
 *
 * <p>10초마다: ① 열린 실자금 포지션을 판정한다(고점 갱신·도달 기록) ② 아직 처리하지 않은 도달 기록을 처리한다.</p>
 *
 * <h3>처리 — 기존 폐기 기준과 같은 스위치</h3>
 * <ul>
 *   <li>신규 진입 차단은 <b>스위치와 무관하게 항상</b> 걸린다 — 도달 기록이 생긴 순간부터 공유 예약 지점이 거절한다.</li>
 *   <li>{@code kill-criteria.auto-stop=true} — 그 세션을 엔진의 일반 정지로 멈춘다(미체결 매수 취소 → 보유 청산 매도 → STOPPED).
 *       정지 뒤 늦은 체결은 기존 정리 대기(V87)가 맡는다.</li>
 *   <li>{@code false}(기본) — 경보 한 번. 정지·정리는 운영자가 한다(§6.5 절차의 세션 단위 적용).</li>
 * </ul>
 * <p>처리 결과는 도달 기록에 남기고(정지 성공·실패·경보·세션이 이미 멈춤), 한 번 남긴 기록은 다시 처리하지 않는다. 처리 전에 프로세스가
 * 죽으면 재시작 뒤 다음 주기에 처리된다. 🔴 정지 성공은 <b>정리 완료가 아니다</b> — 청산 매도의 체결·정리 대기는 별도로 추적된다.</p>
 */
@Slf4j
@Component
public class PositionLimitMonitor {

    public static final String ACTION_AUTO_STOPPED = "AUTO_STOPPED";
    public static final String ACTION_AUTO_STOP_FAILED = "AUTO_STOP_FAILED";
    public static final String ACTION_ALERTED = "ALERTED";
    public static final String ACTION_NOT_RUNNING = "NOT_RUNNING";

    private final PositionLimitGuard guard;
    private final LiveTradingService liveTradingService;
    private final DynamicTradingService dynamicTradingService;
    private final LiveTradingSessionRepository liveSessionRepository;
    private final DynamicSessionRepository dynamicSessionRepository;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    @Value("${kill-criteria.auto-stop:false}")
    private boolean autoStop;

    public PositionLimitMonitor(PositionLimitGuard guard, LiveTradingService liveTradingService,
                                DynamicTradingService dynamicTradingService,
                                LiveTradingSessionRepository liveSessionRepository,
                                DynamicSessionRepository dynamicSessionRepository) {
        this.guard = guard;
        this.liveTradingService = liveTradingService;
        this.dynamicTradingService = dynamicTradingService;
        this.liveSessionRepository = liveSessionRepository;
        this.dynamicSessionRepository = dynamicSessionRepository;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 25_000)
    public void run() {
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            log.error("[포지션 한도] 감시 실패 — 다음 주기에 다시 본다", e);
        }
    }

    /** 시험에서도 쓰는 본체. 판정 결과를 돌려준다. */
    public List<PositionLimitGuard.Evaluation> runOnce(Instant now) {
        List<PositionLimitGuard.Evaluation> evaluations = guard.evaluateAll(now);
        Set<String> handledSessions = new HashSet<>();
        for (PositionLimitGuard.PendingBreach b : guard.pendingBreaches()) {
            handle(b, now, handledSessions);
        }
        return evaluations;
    }

    private void handle(PositionLimitGuard.PendingBreach b, Instant now, Set<String> handledSessions) {
        String key = b.sessionKind() + "#" + b.sessionId();
        String status = sessionStatus(b.sessionKind(), b.sessionId());
        String action;
        String note;
        if (!autoStop) {
            action = ACTION_ALERTED;
            note = "자동 정지 OFF — 신규 진입은 차단됨, 정지·정리는 운영자 (세션 상태 " + status + ")";
        } else if (!"RUNNING".equals(status)) {
            action = ACTION_NOT_RUNNING;
            note = "세션 상태 " + status + " — 자동 정지 대상이 아니다(일반 정지는 RUNNING 만 받는다). 남은 보유는 운영자가 확인";
        } else if (handledSessions.contains(key)) {
            action = ACTION_AUTO_STOPPED;
            note = "같은 주기에 같은 세션을 이미 정지했다";
        } else {
            try {
                if ("LIVE".equals(b.sessionKind())) {
                    liveTradingService.stopSession(b.sessionId());
                } else {
                    dynamicTradingService.stopSession(b.sessionId());
                }
                action = ACTION_AUTO_STOPPED;
                note = "일반 정지 호출 성공(세션 STOPPED) — 청산 체결·정리 대기는 별도 추적";
            } catch (Exception e) {
                action = ACTION_AUTO_STOP_FAILED;
                note = "정지 호출 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " — 신규 진입은 계속 차단, 운영자 확인";
                log.error("[포지션 한도] 자동 정지 실패 {}: {}", key, e.toString());
            }
        }
        handledSessions.add(key);
        guard.recordStopAction(b.positionId(), action, note, now);
        log.error("🔴 [포지션 한도] {} {} 포지션 #{} → {}: {} | {}", b.code(), key, b.positionId(), action, note, b.detail());
        if (telegram != null) {
            try {
                telegram.notifyRiskLimitBreached("포지션별 한도 " + b.code() + " — " + key + " 포지션 #" + b.positionId()
                        + " → " + action + ": " + note);
            } catch (Exception e) {
                log.warn("포지션 한도 경보 전송 실패: {}", e.getMessage());
            }
        }
    }

    private String sessionStatus(String kind, Long sessionId) {
        if ("LIVE".equals(kind)) {
            return liveSessionRepository.findById(sessionId).map(s -> s.getStatus()).orElse("없음");
        }
        return dynamicSessionRepository.findById(sessionId).map(s -> s.getStatus()).orElse("없음");
    }

    /** 시험용. */
    void setAutoStop(boolean v) {
        this.autoStop = v;
    }
}
