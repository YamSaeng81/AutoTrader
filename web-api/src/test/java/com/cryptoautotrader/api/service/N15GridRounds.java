package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.util.TimeframeUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N15 세 시험(N15EntryExpiryTest·N15FinalApprovalBoundaryTest·N15PgLockWaitExpiryTest) 공용 준비 — 시험 전용 (총괄 v1.156 §124, 2026-11-12).
 *
 * <p>🔴 종전 준비는 마지막 봉을 "실제 현재 − 61분 − 초 오프셋 + 밀리초" 로 두었다. 두 가지가 틀렸다.</p>
 * <ol>
 *   <li><b>비정시 봉.</b> B01 뒤 LIVE 는 캔들 조회 상한·최신성을 공통 시계({@link EntryClock})로 본다. 승인 시각 E−1ms 의 정시가
 *       봉 시작 + 2시간으로 넘어가면(실행 시각의 분이 늦을 때) "필요 다음 정시 이후" 로 최신성에서 막혀, 만료 방어에 닿기 전에
 *       거절됐다(12:54·12:57 실행 실패 원문, 총괄 고정 시각 탐침 5/5). 운영 H1 봉은 언제나 정시에 시작한다.</li>
 *   <li><b>초 오프셋 회차 격리.</b> 회차 키는 (타임프레임, 봉 시작)뿐이고 닫힌 회차는 2일 남는다. "순번 × 5초" 오프셋은 앞 시험이
 *       5초 걸리면 실제 경과와 상쇄돼 앞 시험의 닫힌 키와 같아졌다(2026-11-12 ② DYNAMIC "접수 0" — ⑤ 시작 12:54:15, ② 12:54:20,
 *       두 base 가 같은 초).</li>
 * </ol>
 *
 * <p>여기서는 (1) LIVE 입력의 거래 코인·BTC 자료와 회차 키를 정시 격자에 두고 접수·승인 시각은 시험 시계로 고정하며,
 * (2) 독립 입력마다 <b>새 회차 상태</b>({@link EntryPriorityRounds} 새 인스턴스)를 엔진에 꽂았다가 되돌린다. 닫힌 회차를 다시 열거나
 * 지우지 않는다 — 원래 빈의 회차 상태는 손대지 않는다. 제품 코드·만료/마감/유예/최신성 규칙은 그대로다.</p>
 *
 * <p>🔴 스케줄러: 시험 프로필은 {@code app.scheduling.enabled=false} 라 {@code processDueRounds} 가 돌지 않는다(2026-10-13). 새 회차
 * 상태는 빈이 아니어서 켜진 프로필에서도 스케줄러가 가져갈 수 없다. {@link #assertNoBackgroundScheduler} 로 확인한다.</p>
 *
 * <p>🔴 남는 실제 시각 의존(제품): LIVE BTC 급락 관문의 최신성은 실제 시각이다(B01 접점 아님). 그래서 격자는 실제 현재의 정시에
 * 맞추고(L = 지금 정시 − 1시간, C = 지금 정시), BTC 자료는 진행 중 봉 C 까지 둔다(운영 DB 와 같다) — 시험 중 실제 정시가 바뀌어도
 * BTC 는 최신이다. DYNAMIC 은 접수·최신성·BTC 판정이 모두 실제 시각이라 정시 격자 회차는 실제 시각이 C~D(매시 0~5분)일 때만
 * 접수된다. DYNAMIC 을 정시로 옮기려면 DYNAMIC 제품 시계 접점이 필요하다 — 이번 수정 범위 밖(v1.156 §124-2)이라 분리 제출하고,
 * DYNAMIC 입력은 실제 시각 기준 자료({@link #dynamicLastOpen})에 새 회차 상태만 쓴다.</p>
 */
final class N15GridRounds {

    private N15GridRounds() { }

    /** LIVE 접수 시각 두 위치 — 회차 마감 C 직후(C+1s)와 접수 마감 직전(D−1ms). 벽시계의 분을 기다리지 않는다. */
    enum Intake {
        EARLY, LATE;

        Instant at(EntryPriorityRounds.RoundKey r) {
            Instant close = r.candleOpenTime().plus(Duration.ofMinutes(TimeframeUtils.toMinutes(r.timeframe())));
            return this == EARLY ? close.plusSeconds(1) : EntryPriorityRounds.deadline(r).minusMillis(1);
        }
    }

    /** LIVE 정시 회차의 마지막 닫힌 봉 시작 L = 실제 현재 정시 − 1시간 (C = 실제 현재 정시). */
    static Instant gridLastOpen() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
    }

    /** BTC 자료 마지막 봉 — 진행 중 봉 C(= L + 1시간). LIVE BTC 관문이 실제 시각으로 최신성을 보기 때문(위 설명). */
    static Instant btcLastOpen(Instant gridLastOpen) {
        return gridLastOpen.plus(1, ChronoUnit.HOURS);
    }

    /**
     * DYNAMIC 입력의 마지막 닫힌 봉 — 실제 현재 61분 전(비정시). DYNAMIC 은 제품이 실제 시각으로 접수·최신성을 보므로 정시 격자는
     * 접점 없이는 불가하다(위 설명, 분리 제출). 회차 격리는 오프셋이 아니라 새 회차 상태로 한다.
     */
    static Instant dynamicLastOpen() {
        return Instant.now().minus(61, ChronoUnit.MINUTES);
    }

    /** 시험 프로필에서 스케줄러가 꺼져 있음을 확인한다 — 시험이 직접 {@code processDue} 를 구동한다. */
    static void assertNoBackgroundScheduler(ApplicationContext ctx) {
        assertThat(ctx.getEnvironment().getProperty("app.scheduling.enabled")).as("시험 프로필 스케줄러 꺼짐").isEqualTo("false");
        assertThat(ctx.getBeanNamesForType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class))
                .as("@Scheduled 처리기 없음 — processDueRounds 가 배경에서 돌지 않는다").isEmpty();
    }

    /**
     * 독립 입력 하나의 회차 상태 — 새 {@link EntryPriorityRounds} 를 LIVE·DYNAMIC·PAPER 엔진에 꽂고 {@link #close} 에서 원래 빈으로
     * 되돌린다(되돌림 확인). 원래 빈의 열린/닫힌 회차는 건드리지 않는다. 한 번에 하나만 꽂는다 — 다음 입력 전에 앞 것을 닫는다
     * (꽂을 때 엔진이 원래 빈을 가리키는지 확인한다).
     */
    static final class Isolated implements AutoCloseable {
        final EntryPriorityRounds rounds;
        private final EntryPriorityRounds original;
        private final List<Object> targets = new ArrayList<>();
        private boolean closed;

        Isolated(ApplicationContext ctx) {
            this.original = ctx.getBean(EntryPriorityRounds.class);
            this.rounds = new EntryPriorityRounds(ctx.getBean(CapitalPoolLimits.class), ctx.getBean(EntryClock.class));
            this.rounds.setForcedForTest(true);
            for (Class<?> k : List.of(LiveTradingService.class, DynamicTradingService.class, PaperTradingService.class)) {
                Object target = AopTestUtils.getUltimateTargetObject(ctx.getBean(k));
                assertThat(ReflectionTestUtils.getField(target, "entryPriorityRounds")).as(k.getSimpleName() + " 원래 회차 빈").isSameAs(original);
                ReflectionTestUtils.setField(target, "entryPriorityRounds", rounds);
                targets.add(target);
            }
        }

        /** 한 번만 되돌린다. 닫은 뒤에도 {@link #rounds} 자체({@code processDue}·{@code openRounds})는 시험이 계속 볼 수 있다. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (Object target : targets) {
                ReflectionTestUtils.setField(target, "entryPriorityRounds", original);
                assertThat(ReflectionTestUtils.getField(target, "entryPriorityRounds")).as("회차 빈 복원").isSameAs(original);
            }
        }
    }
}
