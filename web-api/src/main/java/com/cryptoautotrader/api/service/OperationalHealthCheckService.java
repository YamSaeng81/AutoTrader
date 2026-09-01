package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.discord.DiscordWebhookClient;
import com.cryptoautotrader.api.entity.DailyHealthSnapshotEntity;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DailyHealthSnapshotRepository;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 운영 건전성 자동 점검 — 그동안 인시던트마다 psycopg2로 손수 돌리던 4대 SQL 점검을 매일
 * 자동 실행하고 이력을 남긴다({@link DailyHealthSnapshotEntity}). 이상 발견 시 Discord로
 * 즉시 알림한다.
 *
 * <ul>
 *   <li><b>세션 잔고 정합성</b> — 포지션·활성주문이 없는 세션은 {@code available == total}이어야
 *       한다. 2026-08-03 P0(세션 39·40·44 각 8,000원 증발)가 이 불변식 위반이었다.</li>
 *   <li><b>주문 시퀀스 갭</b> — {@code order_id_seq.last_value}와 {@code MAX(id)}의 차이.
 *       양수면 INSERT 후 롤백으로 행이 사라진 것(2026-07-29/07-31 P0).</li>
 *   <li><b>유령 포지션</b> — 매도 FILLED인데 포지션이 아직 OPEN. DYNAMIC은
 *       {@link DynamicTradingService#reconcileDynamicGhostPositions()}가 30초마다 자동 정산하므로
 *       평소엔 0건이어야 정상 — 여기서 잡히면 그 안전망 자체가 고장난 신호다. LIVE는 대응하는
 *       자동 정산이 아예 없어 이 점검이 유일한 방어선이다.</li>
 *   <li><b>무출구 고착 포지션</b> — time stop(max_hold_hours)이 꺼진 채 24시간 이상 보유 중인
 *       포지션. LIVE 세션 194 BTC 136시간 고착이 이 패턴이었다.</li>
 * </ul>
 *
 * <p><b>의도적으로 감지·알림만 한다(자동 정산 없음)</b> — 유령 포지션 자동 정산은 DYNAMIC에
 * 이미 있고, 그 로직을 LIVE에 새로 복제하는 것은 실거래 자금에 직접 손대는 범위 확장이라
 * 별도 검토가 필요하다. 이 서비스의 역할은 "무엇이 잘못됐는지 사람이 매번 SQL을 켜지 않아도
 * 알 수 있게" 하는 것으로 한정한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationalHealthCheckService {

    private static final String CHANNEL_TYPE = "TRADING_REPORT";
    private static final String MESSAGE_TYPE = "HEALTH_CHECK";

    /** 매수/매도 진행 중일 수 있는 구간 — 이 시간 이내 갱신된 세션은 잔고 정합성 검사에서 제외 */
    private static final long BALANCE_CHECK_GRACE_MIN = 5;

    /** 매도 체결 후 정상 후처리가 아직 안 끝났을 수 있는 구간 — 유령 포지션 판정 유예 */
    private static final long GHOST_CHECK_GRACE_MIN = 5;

    /** time stop이 꺼진 채 이만큼 보유 중이면 "무출구 고착"으로 본다 */
    private static final long STUCK_POSITION_THRESHOLD_HOURS = 24;

    private static final List<String> ACTIVE_ORDER_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    private final LiveTradingSessionRepository liveSessionRepo;
    private final DynamicSessionRepository dynamicSessionRepo;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final DailyHealthSnapshotRepository snapshotRepository;
    private final DiscordWebhookClient discordClient;

    // ── 스케줄 ────────────────────────────────────────────────────────────────

    /** 매일 08:30 KST 실행 */
    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Seoul")
    public void runDailyCheck() {
        log.info("[HealthCheck] 일일 운영 건전성 점검 시작");
        try {
            Result result = check();
            persist(result);
            if (result.hasAnomaly()) {
                sendAlert(result);
            } else {
                log.info("[HealthCheck] 이상 없음 (세션 {}, 유령 포지션 검사 완료, 고착 포지션 검사 완료)",
                        result.balanceMismatches.size());
            }
        } catch (Exception e) {
            log.error("[HealthCheck] 점검 중 오류", e);
        }
    }

    // ── 핵심 로직 ─────────────────────────────────────────────────────────────

    Result check() {
        List<BalanceMismatch> mismatches = checkBalanceConsistency();
        SequenceGapResult sequenceGap = checkOrderSequenceGap();
        List<GhostPosition> ghosts = checkGhostPositions();
        List<StuckPosition> stuck = checkStuckPositions();
        return new Result(mismatches, sequenceGap, ghosts, stuck);
    }

    // ① 세션 잔고 정합성 ---------------------------------------------------

    List<BalanceMismatch> checkBalanceConsistency() {
        List<BalanceMismatch> results = new ArrayList<>();
        for (LiveTradingSessionEntity s : liveSessionRepo.findByStatus("RUNNING")) {
            evalBalance(results, "LIVE", s.getId(), s.getAvailableKrw(), s.getTotalAssetKrw(), s.getUpdatedAt());
        }
        for (DynamicSessionEntity s : dynamicSessionRepo.findByStatus("RUNNING")) {
            evalBalance(results, "DYNAMIC", s.getId(), s.getAvailableKrw(), s.getTotalAssetKrw(), s.getUpdatedAt());
        }
        return results;
    }

    private void evalBalance(List<BalanceMismatch> out, String kind, Long sessionId,
                              BigDecimal available, BigDecimal total, Instant updatedAt) {
        if (available == null || total == null || available.compareTo(total) == 0) return;

        if (updatedAt == null
                || Duration.between(updatedAt, Instant.now()).toMinutes() < BALANCE_CHECK_GRACE_MIN) {
            return; // 매수/매도 진행 중일 수 있는 구간 — 건드리지 않는다
        }

        boolean hasOpenPosition = !positionRepository
                .findBySessionKindAndSessionId(kind, sessionId).stream()
                .allMatch(p -> "CLOSED".equals(p.getStatus()));
        if (hasOpenPosition) return; // 보유 중 차이는 미실현손익 — 정상

        boolean hasActiveOrder = orderRepository
                .findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sessionId, PageRequest.of(0, 20))
                .stream().anyMatch(o -> ACTIVE_ORDER_STATES.contains(o.getState()));
        if (hasActiveOrder) return; // 체결 대기 중 — 아직 결론 낼 수 없다

        out.add(new BalanceMismatch(kind, sessionId, available, total));
    }

    // ② 주문 시퀀스 갭 -------------------------------------------------------

    SequenceGapResult checkOrderSequenceGap() {
        try {
            Long gap = orderRepository.findOrderSequenceGap();
            return new SequenceGapResult(true, gap == null ? 0 : Math.max(gap, 0));
        } catch (Exception e) {
            // Postgres named sequence 전용 — H2 테스트 등 시퀀스가 없는 환경에서는 확인 불가로 처리
            log.debug("[HealthCheck] 주문 시퀀스 갭 조회 불가(Postgres 전용 쿼리) — 스킵", e);
            return new SequenceGapResult(false, 0);
        }
    }

    // ③ 유령 포지션 (감지만 — 자동 정산은 DYNAMIC reconcile이 담당) -----------

    List<GhostPosition> checkGhostPositions() {
        List<GhostPosition> results = new ArrayList<>();
        // 2026-09-01: DYN_PAPER 추가. 페이퍼 포지션은 session_kind='DYN_PAPER' 로 저장되어
        // 유령 포지션 점검에서 통째로 빠져 있었다 — 페이퍼가 실전 예측용이려면 감시도 같아야 한다.
        for (String kind : List.of("LIVE", "DYNAMIC", "DYN_PAPER")) {
            for (PositionEntity pos : positionRepository.findBySessionKindAndStatus(kind, "OPEN")) {
                if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal realized = pos.getRealizedPnl();
                if (realized != null && realized.compareTo(BigDecimal.ZERO) != 0) continue; // 이미 정산됨(부분체결)

                OrderEntity filledSell = orderRepository
                        .findByPositionIdOrderByCreatedAtDesc(pos.getId()).stream()
                        .filter(o -> "SELL".equalsIgnoreCase(o.getSide()) && "FILLED".equals(o.getState()))
                        .findFirst().orElse(null);
                if (filledSell == null) continue;

                if (filledSell.getFilledQuantity() == null
                        || filledSell.getFilledQuantity().compareTo(pos.getSize()) != 0) continue; // 부분 체결

                if (filledSell.getFilledAt() == null
                        || Duration.between(filledSell.getFilledAt(), Instant.now()).toMinutes()
                           < GHOST_CHECK_GRACE_MIN) continue; // 정상 후처리 진행 중일 수 있는 구간

                results.add(new GhostPosition(kind, pos.getId(), pos.getSessionId(), pos.getCoinPair()));
            }
        }
        return results;
    }

    // ④ 무출구 고착 (time stop 비활성 + 장기 보유) -----------------------------

    List<StuckPosition> checkStuckPositions() {
        List<StuckPosition> results = new ArrayList<>();
        Instant threshold = Instant.now().minus(STUCK_POSITION_THRESHOLD_HOURS, ChronoUnit.HOURS);

        Map<Long, Integer> liveMaxHold = new LinkedHashMap<>();
        for (LiveTradingSessionEntity s : liveSessionRepo.findByStatus("RUNNING")) {
            liveMaxHold.put(s.getId(), Objects.requireNonNullElse(s.getMaxHoldHours(), 0));
        }
        Map<Long, Integer> dynMaxHold = new LinkedHashMap<>();
        for (DynamicSessionEntity s : dynamicSessionRepo.findByStatus("RUNNING")) {
            dynMaxHold.put(s.getId(), Objects.requireNonNullElse(s.getMaxHoldHours(), 0));
        }

        for (PositionEntity pos : positionRepository.findBySessionKindAndStatus("LIVE", "OPEN")) {
            checkStuck(results, "LIVE", pos, liveMaxHold, threshold);
        }
        // dynMaxHold 는 dynamic_session 테이블 기준이라 REAL/PAPER 가 같은 맵을 쓴다
        // (둘 다 같은 테이블의 행이고 id 가 유일하다). 포지션 쪽만 session_kind 로 갈린다.
        for (String kind : List.of("DYNAMIC", "DYN_PAPER")) {
            for (PositionEntity pos : positionRepository.findBySessionKindAndStatus(kind, "OPEN")) {
                checkStuck(results, kind, pos, dynMaxHold, threshold);
            }
        }
        return results;
    }

    private void checkStuck(List<StuckPosition> out, String kind, PositionEntity pos,
                             Map<Long, Integer> maxHoldBySession, Instant threshold) {
        if (pos.getOpenedAt() == null || pos.getOpenedAt().isAfter(threshold)) return;

        Integer maxHold = maxHoldBySession.get(pos.getSessionId());
        if (maxHold != null && maxHold > 0) return; // time stop이 켜져 있으면 곧 스스로 청산됨

        long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
        out.add(new StuckPosition(kind, pos.getId(), pos.getSessionId(), pos.getCoinPair(), heldHours));
    }

    // ── 저장 ──────────────────────────────────────────────────────────────────

    private void persist(Result r) {
        DailyHealthSnapshotEntity entity = DailyHealthSnapshotEntity.builder()
                .balanceMismatchCount(r.balanceMismatches.size())
                .balanceMismatchDetail(r.balanceMismatches.stream().map(BalanceMismatch::toMap).toList())
                .orderSequenceGap((int) r.sequenceGap.gap)
                .sequenceGapChecked(r.sequenceGap.checked)
                .ghostPositionCount(r.ghosts.size())
                .ghostPositionDetail(r.ghosts.stream().map(GhostPosition::toMap).toList())
                .stuckPositionCount(r.stuck.size())
                .stuckPositionDetail(r.stuck.stream().map(StuckPosition::toMap).toList())
                .build();
        snapshotRepository.save(entity);
    }

    // ── 알림 ──────────────────────────────────────────────────────────────────

    private void sendAlert(Result r) {
        StringBuilder desc = new StringBuilder();

        if (!r.balanceMismatches.isEmpty()) {
            desc.append("💰 **잔고 정합성 이상** ").append(r.balanceMismatches.size()).append("건\n");
            for (BalanceMismatch m : r.balanceMismatches) {
                desc.append(String.format("  · [%s#%d] available=%s total=%s (차이 %s)\n",
                        m.kind, m.sessionId, m.available, m.total, m.total.subtract(m.available)));
            }
            desc.append("\n");
        }

        if (r.sequenceGap.checked && r.sequenceGap.gap > 0) {
            desc.append("🔢 **주문 시퀀스 갭** ").append(r.sequenceGap.gap)
                    .append("건 — INSERT 후 롤백으로 소멸된 주문 의심\n\n");
        }

        if (!r.ghosts.isEmpty()) {
            desc.append("👻 **유령 포지션** ").append(r.ghosts.size()).append("건 (매도 FILLED인데 OPEN)\n");
            for (GhostPosition g : r.ghosts) {
                desc.append(String.format("  · [%s#%d] posId=%d %s\n", g.kind, g.sessionId, g.positionId, g.coinPair));
            }
            desc.append("\n");
        }

        if (!r.stuck.isEmpty()) {
            desc.append("⏳ **무출구 고착 포지션** ").append(r.stuck.size()).append("건 (time stop 비활성 + 24h+ 보유)\n");
            for (StuckPosition s : r.stuck) {
                desc.append(String.format("  · [%s#%d] posId=%d %s — %d시간\n",
                        s.kind, s.sessionId, s.positionId, s.coinPair, s.heldHours));
            }
        }

        boolean hasCritical = !r.balanceMismatches.isEmpty() || !r.ghosts.isEmpty()
                || (r.sequenceGap.checked && r.sequenceGap.gap > 0);
        int color = hasCritical ? DiscordWebhookClient.COLOR_RED : DiscordWebhookClient.COLOR_YELLOW;

        ObjectNode embed = discordClient.embed(
                "🩺 운영 건전성 점검 — 이상 " + r.totalAnomalyCount() + "건",
                desc.toString().trim(),
                color);

        boolean sent = discordClient.sendEmbed(CHANNEL_TYPE, embed, MESSAGE_TYPE);
        log.warn("[HealthCheck] 이상 {}건 감지, 알림 전송 {}", r.totalAnomalyCount(), sent ? "성공" : "실패(채널 미설정 또는 오류)");
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    record Result(List<BalanceMismatch> balanceMismatches, SequenceGapResult sequenceGap,
                  List<GhostPosition> ghosts, List<StuckPosition> stuck) {
        boolean hasAnomaly() {
            return !balanceMismatches.isEmpty()
                    || (sequenceGap.checked && sequenceGap.gap > 0)
                    || !ghosts.isEmpty()
                    || !stuck.isEmpty();
        }

        int totalAnomalyCount() {
            return balanceMismatches.size()
                    + (sequenceGap.checked && sequenceGap.gap > 0 ? 1 : 0)
                    + ghosts.size()
                    + stuck.size();
        }
    }

    record SequenceGapResult(boolean checked, long gap) {}

    record BalanceMismatch(String kind, Long sessionId, BigDecimal available, BigDecimal total) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionKind", kind);
            m.put("sessionId", sessionId);
            m.put("availableKrw", available);
            m.put("totalAssetKrw", total);
            return m;
        }
    }

    record GhostPosition(String kind, Long positionId, Long sessionId, String coinPair) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionKind", kind);
            m.put("positionId", positionId);
            m.put("sessionId", sessionId);
            m.put("coinPair", coinPair);
            return m;
        }
    }

    record StuckPosition(String kind, Long positionId, Long sessionId, String coinPair, long heldHours) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionKind", kind);
            m.put("positionId", positionId);
            m.put("sessionId", sessionId);
            m.put("coinPair", coinPair);
            m.put("heldHours", heldHours);
            return m;
        }
    }
}
