package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매수 예약의 <b>직렬화 지점</b> (2026-10-12, V85).
 *
 * <h3>고치는 문제</h3>
 *
 * <p>{@code executeSessionBuy} 의 §8 한도 확인은 <b>읽고 → 판단하고 → 쓴다</b>. 그 사이에 아무
 * 직렬화도 없고, 차감은 주문 제출 <b>뒤에</b> 일어났다. 두 세션이 동시에 들어오면 같은 합계를 읽고
 * 둘 다 통과한다 — 실측으로 <b>승인 2건 / 예약 합계 6,000 &gt; 상한 5,000</b> 이 재현됐다
 * ({@code ConcurrentEntryCapTest}).</p>
 *
 * <h3>🔴 거래소의 거절을 안전장치로 가정하지 않는다</h3>
 *
 * <p>거래소 계좌에 여유 자금이 있으면 <b>로컬 상한을 넘은 주문도 그대로 접수된다.</b> 그리고 접수된
 * 주문의 응답이 유실되면 그 초과분이 불명 상태로 남는다. 즉 초과 승인은 <b>로컬에서</b> 막아야
 * 한다.</p>
 *
 * <h3>왜 세션 행 잠금으로는 부족한가</h3>
 *
 * <p>한도는 <b>세션 사이에 공유된 풀</b>이다. 각자 자기 세션 행만 잠그면 서로를 막지 못한다.
 * 공유 자원에는 공유 잠금이 필요하므로 계좌 단위 행 하나를 잠근다.</p>
 *
 * <h3>경계</h3>
 *
 * <p>🔴 이 서비스는 <b>호출자의 트랜잭션 안에서</b> 돈다. 잠금의 수명이 그 트랜잭션이어야 하기
 * 때문이다 — 자기 트랜잭션을 열면 잠금이 먼저 풀려 한도 확인과 의도 저장 사이가 다시 벌어진다.
 * 그래서 {@code @Transactional} 을 붙이지 않고, 트랜잭션 없이 불리면 거절한다.</p>
 *
 * <p>🔴 거래소 접수와 DB 커밋의 원자성은 <b>여기서 해결되지 않는다.</b> 그 간극은 V84 의 식별자
 * 추적이 담당한다. 여기서 없애는 것은 로컬 초과 승인이다.</p>
 */
@Slf4j
@Service
public class CapitalReservationService {

    /** 한도 합산에 포함하는 세션 상태 — {@code LiveTradingService} §8 과 같아야 한다. */
    static final List<String> ACTIVE_SESSION_STATUSES = List.of("RUNNING", "PAUSED");

    private final LiveTradingSessionRepository sessionRepository;
    private final OrderRepository orderRepository;
    private final PortfolioManager portfolioManager;

    @PersistenceContext
    private EntityManager em;

    public CapitalReservationService(LiveTradingSessionRepository sessionRepository,
                                      OrderRepository orderRepository,
                                      PortfolioManager portfolioManager) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.portfolioManager = portfolioManager;
    }

    /**
     * 한도를 확인하고 자금을 예약한다. <b>호출자의 트랜잭션 안에서 불러야 한다.</b>
     *
     * <p>잠금을 쥔 채 합계를 읽고 차감까지 끝내므로, 불변식 <b>{@code 활성 세션 가용 KRW 합 ≥ 0}</b>
     * 이 항상 유지된다 — 따라서 예약 합계는 처음 풀을 넘지 못한다.</p>
     *
     * @return 예약했으면 {@code true}. 한도를 넘으면 {@code false} 이고 <b>아무것도 바꾸지 않는다</b>
     */
    public boolean reserveWithinCurrentTransaction(Long sessionId, BigDecimal amount) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            // 🔴 트랜잭션 없이 부르면 잠금의 수명이 이 메서드로 끝나 직렬화가 성립하지 않는다.
            //    조용히 통과시키면 가드가 있는 줄 알면서 없는 상태가 된다.
            throw new IllegalStateException(
                    "자금 예약은 호출자의 트랜잭션 안에서만 할 수 있다 — 잠금 수명이 짧으면 "
                            + "한도 확인과 차감 사이가 벌어진다 (sessionId=" + sessionId + ")");
        }
        if (amount == null || amount.signum() <= 0) {
            return false;
        }

        // ① 공유 잠금 — 이 트랜잭션이 끝날 때까지 다른 예약은 여기서 기다린다.
        //    🔴 스칼라로 읽는다. 엔티티로 읽으면 영속성 맥락에 들어가 뜻하지 않은 쓰기를 만든다.
        lockAccount();

        // ② 예약 원장 — 🔴 **공유 풀의 상한은 거래소 계좌 잔고다.**
        //
        //    세션별 가용 KRW 검사는 "한 세션이 자기 배정을 넘지 않는가" 만 본다. 여러 세션의
        //    배정 합이 계좌 잔고를 넘은 상태(drift)에서는 **각자 통과하고 계좌를 초과한다.**
        //
        //    🔴 거래소의 잔고 부족 거절을 안전장치로 쓸 수 없다. 계좌에 여유가 있으면 초과 주문도
        //    접수되고, 그 응답이 유실되면 초과분이 불명으로 남는다. 그래서 로컬에서 막는다.
        BigDecimal cap = portfolioManager.getTotalCapital();
        if (cap != null && cap.signum() > 0) {
            BigDecimal open = orderRepository.sumOpenReservedKrw();
            if (open == null) {
                open = BigDecimal.ZERO;
            }
            if (open.add(amount).compareTo(cap) > 0) {
                log.warn("[예약 원장] 매수 차단: 미결 예약({}) + 신규({}) > 계좌 잔고({}) "
                                + "(sessionId={}). 🔴 거래소 거절을 기다리지 않는다.",
                        open, amount, cap, sessionId);
                return false;
            }
        }

        // ③ 한도 확인 — 잠금을 쥔 뒤에 읽어야 의미가 있다. 잠금 전에 읽은 값은 이미 낡았다.
        BigDecimal total = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        if (total.subtract(amount).signum() < 0) {
            log.warn("[§8] 매수 차단: 전 세션 가용KRW 합산({}) − 매수금({}) < 0 (sessionId={}). "
                            + "🔴 거래소 거절을 기다리지 않고 로컬에서 막는다 — 계좌에 여유 자금이 "
                            + "있으면 초과 주문도 접수된다.", total, amount, sessionId);
            return false;
        }

        // ④ 자금 예약 — 같은 트랜잭션·같은 잠금 안에서 차감한다.
        LiveTradingSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("자금 예약 실패: 세션을 찾을 수 없다 (sessionId={})", sessionId);
            return false;
        }
        BigDecimal before = session.getAvailableKrw() != null
                ? session.getAvailableKrw() : BigDecimal.ZERO;
        if (before.subtract(amount).signum() < 0) {
            log.warn("자금 예약 실패: 세션 가용 KRW({})보다 큰 금액({})이다 (sessionId={})",
                    before, amount, sessionId);
            return false;
        }
        session.setAvailableKrw(before.subtract(amount));
        sessionRepository.save(session);
        em.flush();   // 🔴 잠금 안에서 반영을 확정한다 — 다음 예약이 읽을 합계에 들어가야 한다.

        log.info("자금 예약 (sessionId={}): {} 차감, 세션 가용 {} → {}, 전체 합산 {} → {}",
                sessionId, amount, before, session.getAvailableKrw(), total, total.subtract(amount));
        return true;
    }

    /**
     * 계좌 잠금 행을 배타 잠금으로 읽는다.
     *
     * <p>🔴 H2 는 {@code FOR UPDATE} 를 지원하지만 PostgreSQL 과 동작이 같지 않다. 직렬화의 근거는
     * 운영 DB(PostgreSQL)의 행 잠금이고, 시험은 <b>초과 승인이 0 인가</b>로 확인한다.</p>
     */
    private void lockAccount() {
        em.createNativeQuery("SELECT id FROM account_capital_lock WHERE id = 1 FOR UPDATE")
                .getSingleResult();
    }
}
