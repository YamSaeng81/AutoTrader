package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 11 — <b>서로 다른 세션의 동시 진입 요청</b> (2026-10-12). 선행 조건 3번의 닫는 조건.
 *
 * <h3>경계</h3>
 *
 * <p>{@code LiveTradingService.executeSessionBuy} 의 §8 검사는 이렇게 생겼다:</p>
 *
 * <pre>
 * BigDecimal totalAvailableKrw = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
 * if (totalAvailableKrw.subtract(investAmount).compareTo(BigDecimal.ZERO) &lt; 0) {
 *     return;   // 차단
 * }
 * ...
 * balanceUpdater.apply(sessionId, s -&gt; s.setAvailableKrw(s.getAvailableKrw().subtract(investAmount)));
 * </pre>
 *
 * <p>🔴 <b>읽고 → 판단하고 → 쓴다.</b> 그 사이에 아무 직렬화도 없다. 게다가 차감은
 * {@code submitOrderAfterCommit} <b>뒤에</b> 일어나므로, 두 세션이 같은 합계를 읽고 둘 다 통과한
 * 다음 각자 제출하는 순서가 가능하다 — <b>예약 합계가 상한을 넘는다.</b></p>
 *
 * <h3>🔴 이 시험이 증명하는 것과 증명하지 않는 것</h3>
 *
 * <table>
 *   <tr><th></th><th>내용</th></tr>
 *   <tr><td>증명한다</td><td>§8 의 <b>읽기-판단-쓰기 경계 자체</b>에 직렬화가 없다는 것. 같은 협력
 *       객체({@code sumAvailableKrwByStatusIn} · {@code balanceUpdater})를 같은 순서로 쓴다</td></tr>
 *   <tr><td>🔴 증명하지 않는다</td><td>{@code executeSessionBuy} 를 끝까지 구동한 것이 아니다 —
 *       그 메서드는 {@code private} 이고 캔들·신호·레짐 준비가 필요하다. 그래서 이 시험은
 *       <b>경계의 재현</b>이며 운영 경로의 발생 빈도를 말하지 않는다</td></tr>
 * </table>
 *
 * <h3>🔴 측정 결과 (2026-10-12) — 재현됐다. 미해결·배포 차단</h3>
 *
 * <pre>
 * 승인 = 2건 (기대 1건)
 * 예약 합계 = 6,000  &gt;  상한 5,000
 * 잔여 합계 = −1,000
 * </pre>
 *
 * <p>두 작업자가 <b>같은 합계를 읽고 둘 다 통과</b>했다. 거래소에서는 한쪽이 잔고 부족으로 거절될
 * 텐데, 🔴 <b>그 거절은 응답 불명과 구분되지 않을 수 있다</b> — 선행 조건 3번과 5번이 맞물리는
 * 지점이다. V84 의 불명 추적은 "확인할 수 있게" 만들었지만 <b>초과 승인 자체를 막지는 않는다.</b></p>
 *
 * <p>🔴 <b>이 시험을 통과시키려고 단정을 느슨하게 하거나 지우지 않는다.</b> 요구사항이 맞고 구현이
 * 아직 그것을 만족하지 않는다 — 선행 조건 3번은 <b>미충족</b>이고 운영 배포는 보류다. 닫는 방법은
 * 예약 생성 경계를 직렬화하는 것(계좌 단위 잠금 또는 예약 원장)이며 별도 작업이다.</p>
 */
@DisplayName("시험 11 — 서로 다른 세션의 동시 진입과 예약 상한")
class ConcurrentEntryCapTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private SessionBalanceUpdater balanceUpdater;

    /** 운영 코드가 §8 에서 쓰는 것과 같은 상태 목록. */
    private static final List<String> ACTIVE_SESSION_STATUSES = List.of("RUNNING", "PAUSED");

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(sessionRepository::deleteById);
        createdSessions.clear();
    }

    private Long session(String coinPair, BigDecimal availableKrw) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair(coinPair).timeframe("M5")
                .initialCapital(new BigDecimal("5000.00"))
                .availableKrw(availableKrw)
                .totalAssetKrw(new BigDecimal("5000.00"))
                .status("RUNNING").startedAt(Instant.now()).build());
        createdSessions.add(s.getId());
        return s.getId();
    }

    /**
     * §8 의 검사와 예약을 <b>그 순서대로</b> 흉내낸다.
     *
     * @return 진입을 승인하고 예약했으면 {@code true}
     */
    private boolean checkThenReserve(Long sessionId, BigDecimal investAmount, CountDownLatch afterRead) {
        BigDecimal totalAvailableKrw = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
        if (totalAvailableKrw.subtract(investAmount).compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        // 🔴 읽기와 쓰기 사이의 간극 — 운영에서는 SL/TP 계산·포지션 저장·주문 제출이 여기 들어간다.
        afterRead.countDown();
        try {
            afterRead.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(s.getAvailableKrw().subtract(investAmount)));
        return true;
    }

    @org.junit.jupiter.api.Disabled("🔴 미해결·배포 차단: §8 의 읽기-판단-쓰기 경계에 직렬화가 없어 "
            + "초과 승인 2건·예약 합계 6,000 > 상한 5,000 으로 재현됐다. 최소 재현으로 보존한다 — "
            + "선행 조건 3번을 닫을 때 해제한다")
    @Test
    @DisplayName("🔴 미해결 — 두 세션이 동시에 진입하면 예약 합계가 상한을 넘는다")
    void concurrentEntriesAgainstSharedCap() throws Exception {
        // 두 세션의 가용 KRW 합계 = 5,000. 각자 3,000 을 쓰려 한다 → 합계 6,000 > 5,000.
        Long a = session("KRW-DOGE", new BigDecimal("2500.00"));
        Long b = session("KRW-ADA", new BigDecimal("2500.00"));
        BigDecimal invest = new BigDecimal("3000.00");

        BigDecimal capBefore = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
        assertThat(capBefore)
                .as("상한(전 세션 가용 KRW 합산)은 5,000 이다")
                .isEqualByComparingTo(new BigDecimal("5000.00"));

        CountDownLatch afterRead = new CountDownLatch(2);
        List<Boolean> approvals = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread t1 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(a, invest, afterRead));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(b, invest, afterRead));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        t1.start();
        t2.start();
        t1.join(20_000);
        t2.join(20_000);

        long approved = approvals.stream().filter(Boolean::booleanValue).count();
        BigDecimal capAfter = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
        BigDecimal reserved = capBefore.subtract(capAfter);

        System.out.printf("[시험 11] 승인=%d건 예약합계=%s 상한=%s 잔여합계=%s 오류=%s%n",
                approved, reserved, capBefore, capAfter, errors);

        assertThat(errors).as("예약 경로에서 예외가 나지 않아야 한다").isEmpty();

        // 🔴 **닫는 조건**: 예약 합계가 상한을 넘지 않고, 초과 승인이 0건이어야 한다.
        assertThat(reserved)
                .as("🔴 예약 합계가 상한을 넘으면 거래소 잔고보다 많이 주문하는 것이다 — "
                        + "실제로는 한쪽이 잔고 부족으로 거절되고, 그 거절은 **응답 불명과 구분되지 "
                        + "않을 수 있다**(선행 조건 3·5 가 맞물리는 지점이다)")
                .isLessThanOrEqualTo(capBefore);
        assertThat(approved)
                .as("🔴 초과 승인 0건 — 합계 6,000 은 상한 5,000 을 넘으므로 **한쪽만** 승인돼야 한다. "
                        + "두 쪽 모두 승인되면 §8 의 읽기-판단-쓰기 경계에 직렬화가 없다는 뜻이다")
                .isEqualTo(1L);
    }
}
