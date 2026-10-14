package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.util.TimeframeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>신규 진입의 코인 우선순위</b> — 같은 평가 회차의 후보를 사전 등록 순서로 승인한다 (2026-10-14, 선행 조건 3번 3-e,
 * {@code LIVE_ENTRY_PREREG.md} §5).
 *
 * <p>종전에는 각 엔진이 평가하는 즉시 매수했고 공유 풀 슬롯은 <b>계좌 잠금을 먼저 잡은 쪽</b>이 가져갔다 — 스레드·스케줄
 * 실행 속도가 우선순위였다. 사전 등록은 "한 틱에 신호가 한도를 넘으면 사전 고정된 코인 순서로 채운다" 이다.</p>
 *
 * <h3>세 가지 정의</h3>
 * <ol>
 *   <li><b>평가 회차</b> = (타임프레임, 신호를 낸 <b>마감 캔들</b>의 시작 시각). 실자금 LIVE·DYNAMIC 세션이 같은 마감 캔들로
 *       낸 신호는 엔진·스케줄 주기와 무관하게 같은 회차다.</li>
 *   <li><b>후보 수집 완료</b> = 그 캔들 마감 + 마감 유예({@link #MAX_WINDOW}, 타임프레임이 짧으면 그 절반)의 <b>고정 시각</b>.
 *       그 전에 접수된 후보만 이 회차 후보다. 마감 뒤 도착(느린 평가·늦은 캔들)은 이 회차에 넣지 않고, 평가가 실패한 세션은
 *       후보가 없다. 🔴 먼저 도착한 후보부터 승인하지 않는다 — 마감 전에는 아무것도 승인하지 않는다.</li>
 *   <li><b>승인</b> = 마감 뒤 한 번, 확정된 후보를 {@link #REGISTERED_ORDER} 순서로(목록 밖 코인은 뒤에 코인 이름순, 같은 코인은
 *       LIVE → DYNAMIC, 세션 id 순) 하나씩 처리한다. 각 승인은 엔진의 매수 경로 — 즉 기존 원자적 예약 경계(계좌 잠금 안의
 *       한도 확인·예약·의도 저장)를 그대로 지난다. 슬롯·풀·손실 한도로 거절된 후보만 빠지고 다음 후보로 간다.</li>
 * </ol>
 *
 * <p>적용 범위: 등록 단계({@code STAGE_1}·{@code STAGE_2})의 실자금 진입. 페이퍼(독립 자본)는 공유 풀이 아니므로 해당 없다.
 * 시험 단계는 즉시 진입(종전 동작)이며 시험은 {@link #setForcedForTest} 로 켠다.</p>
 *
 * <p><b>같은 코인 동순위</b> (2026-10-15): 같은 회차에 같은 코인을 여러 세션이 내면 LIVE → DYNAMIC, 그 안에서 세션 id
 * 오름차순({@link #ORDER}). 둘 다 세션 생성 때 정해지는 값이라 성과·접수 순서와 무관하다.</p>
 *
 * <p>🔴 <b>진입 지연은 실행 규칙 변경이다</b> — 진입이 최대 마감 유예만큼 늦어진다({@code LIVE_ENTRY_PREREG.md} §5 기록). 그래서
 * 각 엔진의 승인 메서드는 그 사이에 바뀔 수 있는 관문(정지·보유·서킷 브레이커·위험 관문·동일 코인 보유 상한 등)을 승인 시점에
 * 다시 본다.</p>
 *
 * <p>🔴 한계: 후보는 메모리에만 있다 — 마감 전에 프로세스가 죽으면 그 회차 후보는 사라진다(돈은 움직이지 않았다). 재기동한
 * 엔진이 같은 마감 캔들을 다시 평가하면 다시 접수된다.</p>
 */
@Slf4j
@Component
public class EntryPriorityRounds {

    /** {@code LIVE_ENTRY_PREREG.md} §5 — 전향 검증 문서의 22코인 나열 순서 그대로. */
    public static final List<String> REGISTERED_ORDER = List.of(
            "KRW-IOTA", "KRW-WAVES", "KRW-CRO", "KRW-ONG", "KRW-SC", "KRW-POLYX", "KRW-NEAR", "KRW-WAXP",
            "KRW-BCH", "KRW-CVC", "KRW-POWR", "KRW-T", "KRW-ANKR", "KRW-DKA", "KRW-GLM", "KRW-HIVE",
            "KRW-PUNDIX", "KRW-ELF", "KRW-BLAST", "KRW-JUP", "KRW-G", "KRW-INJ");

    static final Duration MAX_WINDOW = Duration.ofMinutes(5);
    private static final Duration CLOSED_RETENTION = Duration.ofDays(2);

    /** 평가 회차 — (타임프레임, 마감 캔들 시작 시각). */
    public record RoundKey(String timeframe, Instant candleOpenTime) {}

    /**
     * 후보 하나.
     *
     * @param approve 승인 차례에 부른다 — 엔진의 매수 경로를 실행하고, 진입했으면 {@code null}, 아니면 사유를 돌려준다.
     *                예외는 그 후보의 실패로만 기록한다.
     */
    public record Candidate(String kind, Long sessionId, String coinPair, RoundKey round,
                            Supplier<String> approve) {}

    /** 승인 결과 — {@code reason} 이 {@code null} 이면 진입. */
    public record Outcome(Candidate candidate, int rank, String reason) {}

    private final CapitalPoolLimits limits;
    private final Map<RoundKey, LinkedHashMap<String, Candidate>> open = new HashMap<>();
    private final Map<RoundKey, Instant> closed = new HashMap<>();
    private volatile boolean forcedForTest;

    public EntryPriorityRounds(CapitalPoolLimits limits) {
        this.limits = limits;
    }

    /** 등록 단계면 켜진다. 시험 단계는 {@link #setForcedForTest} 로만. */
    public boolean isActive() {
        if (forcedForTest) return true;
        String s = limits.getStage();
        return s != null && CapitalPoolLimits.REGISTERED.containsKey(s.trim());
    }

    /** 시험용. */
    public void setForcedForTest(boolean v) {
        this.forcedForTest = v;
    }

    /** 이 회차의 후보 수집 마감 시각. */
    public static Instant deadline(RoundKey round) {
        Duration tf = Duration.ofMinutes(TimeframeUtils.toMinutes(round.timeframe()));
        Duration window = tf.dividedBy(2).compareTo(MAX_WINDOW) < 0 ? tf.dividedBy(2) : MAX_WINDOW;
        return round.candleOpenTime().plus(tf).plus(window);
    }

    /** 등록 순서상 순위 — 목록 밖 코인은 목록 뒤. */
    static int registeredRank(String coinPair) {
        int i = REGISTERED_ORDER.indexOf(coinPair);
        return i >= 0 ? i : REGISTERED_ORDER.size();
    }

    static final Comparator<Candidate> ORDER = Comparator
            .comparingInt((Candidate c) -> registeredRank(c.coinPair()))
            .thenComparing(Candidate::coinPair)
            .thenComparing(c -> "LIVE".equals(c.kind()) ? 0 : 1)
            .thenComparing(Candidate::sessionId);

    /**
     * 후보를 접수한다. 승인하지 않는다.
     *
     * @return 접수했으면 {@code null}, 아니면 접수하지 않은 사유(마감 뒤 도착·같은 세션의 중복)
     */
    public String submit(Candidate c, Instant now) {
        synchronized (this) {
            if (closed.containsKey(c.round()) || now.isAfter(deadline(c.round()))) {
                log.info("[진입 우선순위] 회차 마감 뒤 도착 — 이번 회차 후보 아님: {} {}#{} {}", c.round(), c.kind(), c.sessionId(), c.coinPair());
                return "회차 마감 뒤 도착(" + c.round().timeframe() + " " + c.round().candleOpenTime() + ") — 이번 회차 후보 아님";
            }
            LinkedHashMap<String, Candidate> m = open.computeIfAbsent(c.round(), k -> new LinkedHashMap<>());
            String key = c.kind() + "#" + c.sessionId();
            if (m.containsKey(key)) {
                return "같은 회차에 이 세션의 후보가 이미 있다(" + m.get(key).coinPair() + ")";
            }
            m.put(key, c);
            log.info("[진입 우선순위] 후보 접수 (마감 {}): {} {}#{} {} (회차 후보 {}건)",
                    deadline(c.round()), c.round(), c.kind(), c.sessionId(), c.coinPair(), m.size());
            return null;
        }
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void processDueRounds() {
        processDue(Instant.now());
    }

    /**
     * 마감이 지난 회차를 승인한다. 한 회차는 한 번만 처리된다(꺼낸 쪽만 승인한다).
     */
    public List<Outcome> processDue(Instant now) {
        List<Map.Entry<RoundKey, List<Candidate>>> due = new ArrayList<>();
        synchronized (this) {
            for (var it = open.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!now.isBefore(deadline(e.getKey()))) {
                    due.add(Map.entry(e.getKey(), new ArrayList<>(e.getValue().values())));
                    closed.put(e.getKey(), now);
                    it.remove();
                }
            }
            closed.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(CLOSED_RETENTION)));
        }
        List<Outcome> out = new ArrayList<>();
        for (var e : due) {
            List<Candidate> ordered = new ArrayList<>(e.getValue());
            ordered.sort(ORDER);
            log.info("[진입 우선순위] 회차 확정 {} — 후보 {}건, 승인 순서 {}", e.getKey(), ordered.size(),
                    ordered.stream().map(c -> c.coinPair() + "(" + c.kind() + "#" + c.sessionId() + ")").toList());
            int rank = 0;
            for (Candidate c : ordered) {
                rank++;
                String reason;
                try {
                    reason = c.approve().get();
                } catch (RuntimeException ex) {
                    reason = "승인 중 오류: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                    log.error("[진입 우선순위] 승인 중 오류 — 다음 후보로 간다: {} {}#{} {}", e.getKey(), c.kind(), c.sessionId(), c.coinPair(), ex);
                }
                log.info("[진입 우선순위] {}위 {} {}#{} → {}", rank, c.coinPair(), c.kind(), c.sessionId(),
                        reason == null ? "진입" : "진입 안 함: " + reason);
                out.add(new Outcome(c, rank, reason));
            }
        }
        return out;
    }

    /** 시험용 — 열린 회차 수. */
    synchronized int openRounds() {
        return open.size();
    }
}
