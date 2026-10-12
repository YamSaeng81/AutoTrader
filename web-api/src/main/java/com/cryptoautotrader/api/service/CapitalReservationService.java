package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 실자금 배정·예약의 <b>직렬화 지점</b> (2026-10-12, V85 → 한도 분리).
 *
 * <h3>두 한도는 별개다</h3>
 *
 * <table>
 *   <tr><th></th><th>무엇을 묻는가</th><th>근거</th></tr>
 *   <tr><td><b>등록 풀 한도</b> ({@link CapitalPoolLimits})</td>
 *       <td>이 단계에서 쓰기로 <b>등록한</b> 자본·포지션당 금액·동시 보유를 넘는가</td>
 *       <td>사전 등록 문서</td></tr>
 *   <tr><td><b>계좌 여력</b></td>
 *       <td>거래소에 <b>실제로 있는</b> 돈 중 아직 어느 주문에도 묶이지 않은 몫을 넘는가</td>
 *       <td>거래소 잔고 스냅숏</td></tr>
 * </table>
 *
 * <p>🔴 계좌에 다른 자금이 있어도 등록 한도를 넘으면 안 되고, 등록 한도 안이어도 계좌에 없는 돈을
 * 승인하면 안 된다. 둘 다 통과해야 승인한다. 🔴 거래소의 잔고 부족 거절은 어느 쪽의 안전장치도
 * 아니다 — 계좌에 여유가 있으면 등록 한도를 넘은 주문도 접수된다.</p>
 *
 * <h3>계좌 여력의 정의 — 이중 차감과 과대 승인을 피하려면 이것을 명시해야 한다</h3>
 *
 * <pre>
 * 스냅숏      = 총잔고 = 가용(balance) + 주문 잠금(locked)        ← 주문 가능 잔고가 아니다
 *               기준 시각 = 조회를 **시작한** 시각                  ← 응답 시각이 아니다
 * 묶인 돈     = 열린 매수 주문의 약정액 합                       ← 스냅숏 안에 있다 (가용 또는 잠금)
 *             + 스냅숏 이후 로컬에서 종결된 매수의 약정액 합      ← 스냅숏이 체결 전 값이므로 아직 안에 있다
 * 여력        = 스냅숏 − 묶인 돈
 * </pre>
 *
 * <ul>
 *   <li>🔴 <b>총잔고를 쓰는 이유</b>: 주문 가능 잔고(balance)를 쓰면 접수된 미체결 주문의 돈이 이미
 *       잠금으로 빠져 있으므로, 거기서 열린 주문을 또 빼면 <b>이중 차감</b>이다. 총잔고는 열린 주문의
 *       돈을 단계와 무관하게 정확히 한 번 담고 있어 한 번 빼면 맞는다.</li>
 *   <li>🔴 <b>스냅숏은 기존 예약이 차감되지 않은 값이다</b> — 거래소는 우리 예약을 모른다. 그래서
 *       예약을 여기서 뺀다. 세션 가용 KRW 는 예약이 이미 차감된 값이므로 그것과 섞지 않는다.</li>
 *   <li>🔴 <b>남는 오차는 한쪽 방향이다</b>: 거래소에서 체결됐는데 로컬에 아직 반영되지 않은 주문은
 *       스냅숏에서 빠졌는데 묶인 돈에도 들어간다 → 과소 승인. 과대 승인을 만드는 경우(스냅숏 이후
 *       로컬 종결)는 별도 항으로 막았다.</li>
 *   <li>🔴 <b>스냅숏이 없거나 낡았으면 승인하지 않는다.</b> 초기값 0, 동기화 실패 시 이전 값 유지라
 *       값만으로는 "계좌에 그만큼 있다" 와 "모른다" 가 구분되지 않는다.</li>
 * </ul>
 *
 * <h3>경계</h3>
 *
 * <p>🔴 이 서비스는 <b>호출자의 트랜잭션 안에서</b> 돈다. 잠금의 수명이 그 트랜잭션이어야 한다 —
 * 자기 트랜잭션을 열면 잠금이 먼저 풀려 한도 확인과 의도 저장 사이가 다시 벌어진다.</p>
 *
 * <p>🟢 <b>LIVE·실자금 DYNAMIC 의 매수와 세션 생성·재시작이 모두 이 잠금을 지난다</b> (2026-10-12).</p>
 */
@Slf4j
@Service
public class CapitalReservationService {

    /** LIVE 세션 중 배정을 점유하는 상태. */
    static final List<String> LIVE_ALLOCATING_STATUSES = List.of("RUNNING", "PAUSED", "CREATED");

    /** 동적 세션 중 배정을 점유하는 상태 (실자금만 센다). */
    static final List<String> DYNAMIC_ALLOCATING_STATUSES = List.of("RUNNING", "PAUSED", "CREATED");

    /**
     * 계좌 스냅숏의 최대 허용 나이. 주기 동기화가 5분이므로 세 주기를 넘으면 동기화가 멈춘 것으로 본다.
     */
    static final Duration MAX_SNAPSHOT_AGE = Duration.ofMinutes(15);

    /** 존재하지 않는 id — 제외할 대상이 없을 때 쓴다. */
    private static final long NO_ID = -1L;

    /** 거절 사유 코드. */
    public static final String POOL_NOT_REGISTERED = "POOL_NOT_REGISTERED";
    public static final String PER_POSITION_CAP = "PER_POSITION_CAP";
    public static final String SLOT_LIMIT = "SLOT_LIMIT";
    public static final String POOL_CAP = "POOL_CAP";
    public static final String ACCOUNT_SNAPSHOT_MISSING = "ACCOUNT_SNAPSHOT_MISSING";
    public static final String ACCOUNT_SNAPSHOT_STALE = "ACCOUNT_SNAPSHOT_STALE";
    public static final String ACCOUNT_HEADROOM = "ACCOUNT_HEADROOM";
    public static final String SESSION_FUNDS = "SESSION_FUNDS";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /**
     * 판정 결과.
     *
     * @param code 승인이면 {@code null}, 거절이면 사유 코드
     */
    public record Decision(boolean approved, String code, String message) {
        static Decision approve(String message) {
            return new Decision(true, null, message);
        }

        static Decision reject(String code, String message) {
            return new Decision(false, code, message);
        }
    }

    private final LiveTradingSessionRepository sessionRepository;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final PortfolioManager portfolioManager;
    private final CapitalPoolLimits limits;

    @Autowired(required = false)
    private DynamicSessionRepository dynamicSessionRepository;

    @PersistenceContext
    private EntityManager em;

    public CapitalReservationService(LiveTradingSessionRepository sessionRepository,
                                      OrderRepository orderRepository,
                                      PositionRepository positionRepository,
                                      PortfolioManager portfolioManager,
                                      CapitalPoolLimits limits) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.portfolioManager = portfolioManager;
        this.limits = limits;
    }

    /**
     * LIVE 세션 매수 — 한도를 확인하고 자금을 예약한다. <b>호출자의 트랜잭션 안에서</b> 불러야 한다.
     *
     * <p>순서: 잠금 → 등록 한도(포지션당 → 슬롯 → 총 자본) → 계좌 여력 → 세션 자금 → 차감.
     * 거절이면 <b>아무것도 바꾸지 않는다.</b></p>
     *
     * @param selfPositionId 같은 트랜잭션에서 방금 만든 이 매수의 슬롯 — 슬롯·노출 합에서 뺀다
     */
    public Decision reserveForLiveBuy(Long sessionId, BigDecimal amount, Long selfPositionId) {
        return reserveForBuy("LIVE", sessionId, amount, selfPositionId);
    }

    /**
     * 실자금 매수 — LIVE·DYNAMIC 공통 (2026-10-12). <b>호출자의 트랜잭션 안에서</b> 불러야 한다.
     *
     * <p>🔴 <b>두 종류가 같은 잠금·같은 판정</b>을 지난다. 종류마다 다른 경로를 두면 서로의 예약을
     * 보지 못해 공유 풀이 다시 갈라진다. 다른 것은 차감할 세션 테이블뿐이다.</p>
     *
     * <p>🔴 <b>자본 모델이 공유 풀인 세션만</b> 여기로 온다. 현재 페이퍼 세션(검증 66세션 포함)은
     * 독립 자본으로 사전 등록됐으므로 대상이 아니다 — 공유 풀 페이퍼를 만들 때는 같은 판정을 쓰되
     * 계좌 스냅숏의 공급원만 모의 계좌로 바꾼다(지금은 미구현이라 거절한다).</p>
     */
    public Decision reserveForBuy(String kind, Long sessionId, BigDecimal amount, Long selfPositionId) {
        requireTransaction("자금 예약 (" + kind + ", sessionId=" + sessionId + ")");
        if (amount == null || amount.signum() <= 0 || sessionId == null) {
            return Decision.reject(INVALID_REQUEST, "금액·세션이 없다");
        }
        if (!"LIVE".equals(kind) && !"DYNAMIC".equals(kind)) {
            throw new IllegalArgumentException("실자금 예약은 LIVE·DYNAMIC 만 받는다: " + kind);
        }

        // ① 공유 잠금 — 이 트랜잭션이 끝날 때까지 다른 예약·배정은 여기서 기다린다.
        lockAccount();

        long exclude = selfPositionId != null ? selfPositionId : NO_ID;

        // ② 등록 풀 한도 — 계좌에 얼마가 있든 넘으면 안 된다.
        Decision pool = checkRegisteredPoolForBuy(amount, exclude);
        if (!pool.approved()) {
            return logged(sessionId, pool);
        }

        // ③ 계좌 여력 — 등록 한도 안이어도 계좌에 없는 돈은 승인하지 않는다.
        Decision account = checkAccountHeadroom(amount, BigDecimal.ZERO);
        if (!account.approved()) {
            return logged(sessionId, account);
        }

        // ④ 세션 자금 — 세션 가용 KRW 는 **예약이 이미 차감된 값**이다. 여기서 차감한다.
        BigDecimal before;
        BigDecimal after;
        if ("LIVE".equals(kind)) {
            LiveTradingSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return logged(sessionId, Decision.reject(INVALID_REQUEST, "세션을 찾을 수 없다"));
            }
            before = nz(session.getAvailableKrw());
            if (before.compareTo(amount) < 0) {
                return logged(sessionId, sessionFunds(before, amount));
            }
            after = before.subtract(amount);
            session.setAvailableKrw(after);
            sessionRepository.save(session);
        } else {
            DynamicSessionEntity session = dynamicSessionRepository == null ? null
                    : dynamicSessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return logged(sessionId, Decision.reject(INVALID_REQUEST, "동적 세션을 찾을 수 없다"));
            }
            if (session.isPaper()) {
                // 🔴 독립 자본 페이퍼는 이 경로의 대상이 아니다. 잘못 들어오면 실계좌 스냅숏으로 모의 매수를
                //    판정하게 되므로 조용히 통과시키지 않는다.
                throw new IllegalArgumentException("독립 자본 페이퍼 세션은 공유 풀 예약 대상이 아니다: " + sessionId);
            }
            // 🔴 **최신 행을 잠근 채 다시 읽는다.** 틱 트랜잭션이 들고 있는 엔티티는 낡았을 수 있다 —
            //    워치리스트 기록(`persistWatchlist`)·정산이 같은 행을 별도 트랜잭션으로 갱신해 @Version 이
            //    올라간다. 낡은 엔티티로 쓰면 낙관적 잠금 충돌로 틱 전체가 실패한다(측정으로 확인).
            //    먼저 flush 해 이 트랜잭션의 미반영 변경을 잃지 않게 한다(생산 틱은 세션 행을 메모리에서
            //    바꾸지 않으므로 보통 아무것도 하지 않는다).
            em.flush();
            em.refresh(session, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            before = nz(session.getAvailableKrw());
            if (before.compareTo(amount) < 0) {
                return logged(sessionId, sessionFunds(before, amount));
            }
            after = before.subtract(amount);
            // 🔴 같은 트랜잭션의 **관리 엔티티**를 바꾼다. 별도 트랜잭션(REQUIRES_NEW)으로 쓰면 이
            //    트랜잭션이 커밋 전 롤백될 때 차감만 살아남는다 — 종전 구조가 그것을 보상으로 메웠다.
            session.setAvailableKrw(after);
            dynamicSessionRepository.save(session);
        }
        em.flush();   // 🔴 잠금 안에서 반영을 확정한다 — 다음 예약이 읽을 합계에 들어가야 한다.

        String msg = String.format("예약(%s): 세션 %s → %s, %s | %s", kind, before.toPlainString(),
                after.toPlainString(), pool.message(), account.message());
        log.info("자금 예약 (sessionId={}): {}", sessionId, msg);
        return Decision.approve(msg);
    }

    /**
     * 예약을 되돌린다 — <b>같은 트랜잭션 안에서</b> 예약 직후 의도가 거절됐을 때만 쓴다 (2026-10-12).
     *
     * <p>LIVE 는 예약 트랜잭션 전체를 롤백하므로 이것이 없어도 되지만, DYNAMIC 은 틱 트랜잭션 안에서
     * 예약하므로 롤백하면 그 틱의 신호 기록까지 사라진다. 그래서 차감만 정확히 되돌린다.</p>
     */
    public void releaseWithinTransaction(String kind, Long sessionId, BigDecimal amount) {
        requireTransaction("예약 해제 (" + kind + ", sessionId=" + sessionId + ")");
        if ("LIVE".equals(kind)) {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.setAvailableKrw(nz(s.getAvailableKrw()).add(amount));
                sessionRepository.save(s);
            });
        } else if ("DYNAMIC".equals(kind) && dynamicSessionRepository != null) {
            dynamicSessionRepository.findById(sessionId).ifPresent(s -> {
                s.setAvailableKrw(nz(s.getAvailableKrw()).add(amount));
                dynamicSessionRepository.save(s);
            });
        }
        em.flush();
    }

    private static Decision sessionFunds(BigDecimal before, BigDecimal amount) {
        return Decision.reject(SESSION_FUNDS, String.format(
                "세션 가용 KRW(%s) < 매수금(%s)", before.toPlainString(), amount.toPlainString()));
    }

    /**
     * 세션 생성·재시작 — 배정이 등록 풀과 계좌 안에 들어가는가. <b>호출자의 트랜잭션 안에서</b> 불러야 한다.
     *
     * <p>🔴 매수 예약과 <b>같은 잠금</b>을 쥔다. 다른 잠금을 쓰면 배정과 매수가 서로를 보지 못한다.</p>
     *
     * <ul>
     *   <li><b>등록 풀</b>: 활성 실자금 세션(LIVE + 실자금 DYNAMIC)의 {@code initialCapital} 합 + 신규
     *       ≤ 총 자본 상한. 배정 단위의 사전 점검이고, 노출의 최종 강제는 매수 시점에 한다.</li>
     *   <li><b>계좌</b>: 활성 실자금 세션의 가용 KRW 합 + 신규 세션이 들고 올 KRW ≤ 계좌 여력.
     *       🔴 가용 KRW 는 예약이 차감된 값이므로, 예약분은 계좌 여력 쪽(묶인 돈)에서 한 번만 센다.</li>
     * </ul>
     *
     * @param kind          {@code LIVE} 또는 {@code DYNAMIC}
     * @param sessionId     재시작이면 그 세션(합에서 뺀다), 생성이면 {@code null}
     * @param allocation    등록 풀에 더할 배정액 ({@code initialCapital})
     * @param krwToHold     계좌 쪽에 더할, 이 세션이 들고 있을 KRW ({@code availableKrw})
     */
    public Decision checkSessionAllocation(String kind, Long sessionId,
                                           BigDecimal allocation, BigDecimal krwToHold) {
        requireTransaction("세션 배정 (" + kind + ", sessionId=" + sessionId + ")");
        if (allocation == null || allocation.signum() <= 0) {
            return Decision.reject(INVALID_REQUEST, "배정액이 없다");
        }
        lockAccount();

        long excludeLive = "LIVE".equals(kind) && sessionId != null ? sessionId : NO_ID;
        long excludeDynamic = "DYNAMIC".equals(kind) && sessionId != null ? sessionId : NO_ID;

        if (!limits.isRegistered()) {
            return logged(sessionId, Decision.reject(POOL_NOT_REGISTERED,
                    "등록된 공유 풀 한도가 없다 — 실자금 세션을 배정하지 않는다 (" + limits + ")"));
        }
        BigDecimal allocated = nz(sessionRepository.sumInitialCapitalExcluding(
                LIVE_ALLOCATING_STATUSES, excludeLive))
                .add(dynamicRealInitialCapital(excludeDynamic));
        if (allocated.add(allocation).compareTo(limits.getTotalCapKrw()) > 0) {
            return logged(sessionId, Decision.reject(POOL_CAP, String.format(
                    "배정 합 %s + 신규 %s > 등록 총 자본 상한 %s", allocated.toPlainString(),
                    allocation.toPlainString(), limits.getTotalCapKrw().toPlainString())));
        }

        BigDecimal held = nz(sessionRepository.sumAvailableKrwExcluding(LIVE_ALLOCATING_STATUSES, excludeLive))
                .add(dynamicRealAvailableKrw(excludeDynamic));
        Decision account = checkAccountHeadroom(krwToHold != null ? krwToHold : BigDecimal.ZERO, held);
        if (!account.approved()) {
            return logged(sessionId, account);
        }
        return Decision.approve(String.format("배정 %s + 신규 %s ≤ 상한 %s | %s",
                allocated.toPlainString(), allocation.toPlainString(),
                limits.getTotalCapKrw().toPlainString(), account.message()));
    }

    // ── 판정 ────────────────────────────────────────────────────────

    private Decision checkRegisteredPoolForBuy(BigDecimal amount, long excludePositionId) {
        if (!limits.isRegistered()) {
            // 🔴 기본값이 없으므로 등록하지 않으면 여기서 멈춘다 — "한도가 걸려 있는 줄 아는" 상태를 막는다.
            return Decision.reject(POOL_NOT_REGISTERED,
                    "등록된 공유 풀 한도가 없다 — 실자금 진입을 승인하지 않는다 (" + limits + ")");
        }
        if (amount.compareTo(limits.getPerPositionCapKrw()) > 0) {
            return Decision.reject(PER_POSITION_CAP, String.format("매수금 %s > 포지션당 상한 %s",
                    amount.toPlainString(), limits.getPerPositionCapKrw().toPlainString()));
        }
        // 🔴 슬롯은 체결 전 포지션(size=0)도 센다 — 그것이 예약된 슬롯이다.
        long slots = positionRepository.countPoolSlots(excludePositionId);
        if (slots + 1 > limits.getMaxConcurrentPositions()) {
            return Decision.reject(SLOT_LIMIT, String.format("점유 슬롯 %d + 신규 1 > 동시 보유 상한 %d",
                    slots, limits.getMaxConcurrentPositions()));
        }
        BigDecimal exposure = nz(positionRepository.sumPoolExposureKrw(excludePositionId));
        if (exposure.add(amount).compareTo(limits.getTotalCapKrw()) > 0) {
            return Decision.reject(POOL_CAP, String.format("노출 %s + 신규 %s > 등록 총 자본 상한 %s",
                    exposure.toPlainString(), amount.toPlainString(), limits.getTotalCapKrw().toPlainString()));
        }
        return Decision.approve(String.format("풀: 슬롯 %d+1≤%d, 노출 %s+%s≤%s", slots,
                limits.getMaxConcurrentPositions(), exposure.toPlainString(), amount.toPlainString(),
                limits.getTotalCapKrw().toPlainString()));
    }

    /**
     * 계좌 여력 — 정의는 클래스 설명 참조.
     *
     * @param amount      새로 묶으려는 돈
     * @param alsoClaimed 여력에서 함께 빼야 할, 다른 세션이 쓸 권리를 가진 돈 (배정 검사에서만 쓴다)
     */
    private Decision checkAccountHeadroom(BigDecimal amount, BigDecimal alsoClaimed) {
        // 🔴 잔고와 기준 시각을 **한 번에** 읽는다 — 따로 읽으면 교체 순간 다른 스냅숏의 짝이 섞인다.
        PortfolioManager.CapitalSnapshot snap = portfolioManager.getCapitalSnapshot();
        Instant syncedAt = snap != null ? snap.asOf() : null;
        if (syncedAt == null) {
            return Decision.reject(ACCOUNT_SNAPSHOT_MISSING,
                    "계좌 잔고 스냅숏이 없다 — 계좌에 얼마가 있는지 모르므로 승인하지 않는다");
        }
        Duration age = Duration.between(syncedAt, Instant.now());
        if (age.compareTo(MAX_SNAPSHOT_AGE) > 0) {
            return Decision.reject(ACCOUNT_SNAPSHOT_STALE, String.format(
                    "계좌 잔고 스냅숏이 %d분 전 값이다 (허용 %d분) — 동기화가 멈췄을 수 있다",
                    age.toMinutes(), MAX_SNAPSHOT_AGE.toMinutes()));
        }
        BigDecimal snapshot = nz(snap.totalCapital());
        BigDecimal open = nz(orderRepository.sumOpenBuyCommitmentKrw());
        BigDecimal settledSince = nz(orderRepository.sumBuyCommitmentFilledSince(syncedAt));
        BigDecimal headroom = snapshot.subtract(open).subtract(settledSince).subtract(alsoClaimed);
        String detail = String.format("계좌: 총잔고 %s − 열린 매수 %s − 스냅숏 후 종결 %s − 세션 보유 %s = 여력 %s",
                snapshot.toPlainString(), open.toPlainString(), settledSince.toPlainString(),
                alsoClaimed.toPlainString(), headroom.toPlainString());
        if (amount.compareTo(headroom) > 0) {
            return Decision.reject(ACCOUNT_HEADROOM, detail + " < 신규 " + amount.toPlainString());
        }
        return Decision.approve(detail);
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private BigDecimal dynamicRealInitialCapital(long excludeId) {
        return dynamicSessionRepository == null ? BigDecimal.ZERO
                : nz(dynamicSessionRepository.sumRealInitialCapitalExcluding(DYNAMIC_ALLOCATING_STATUSES, excludeId));
    }

    private BigDecimal dynamicRealAvailableKrw(long excludeId) {
        return dynamicSessionRepository == null ? BigDecimal.ZERO
                : nz(dynamicSessionRepository.sumRealAvailableKrwExcluding(DYNAMIC_ALLOCATING_STATUSES, excludeId));
    }

    private static void requireTransaction(String what) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 🔴 트랜잭션 없이 부르면 잠금의 수명이 이 메서드로 끝나 직렬화가 성립하지 않는다.
            //    조용히 통과시키면 가드가 있는 줄 알면서 없는 상태가 된다.
            throw new IllegalStateException(what + " 는 호출자의 트랜잭션 안에서만 할 수 있다 — "
                    + "잠금 수명이 짧으면 한도 확인과 차감 사이가 벌어진다");
        }
    }

    private static Decision logged(Long sessionId, Decision d) {
        log.warn("[공유 풀] 승인하지 않는다 (sessionId={}, 사유={}): {} — 🔴 거래소 거절을 기다리지 않는다",
                sessionId, d.code(), d.message());
        return d;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * 계좌 잠금 행을 배타 잠금으로 읽는다.
     *
     * <p>🔴 스칼라로 읽는다 — 엔티티로 읽으면 영속성 맥락에 들어가 뜻하지 않은 쓰기를 만든다.
     * 🔴 H2 는 {@code FOR UPDATE} 를 지원하지만 PostgreSQL 과 동작이 같지 않다. 직렬화의 근거는 운영
     * DB(PostgreSQL)의 행 잠금이고, 시험은 <b>초과 승인이 0 인가</b>로 확인한다.</p>
     */
    private void lockAccount() {
        em.createNativeQuery("SELECT id FROM account_capital_lock WHERE id = 1 FOR UPDATE")
                .getSingleResult();
    }
}
