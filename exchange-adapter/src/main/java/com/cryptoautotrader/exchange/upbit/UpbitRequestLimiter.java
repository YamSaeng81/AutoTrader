package com.cryptoautotrader.exchange.upbit;

import java.util.concurrent.TimeUnit;

/**
 * 업비트 공개 시세 요청의 <b>프로세스 전체 공용 간격</b> — 요청이 <b>통과하는 순간</b>에 공유 간격을 확인하고 갱신한다 (2026-10-23).
 *
 * <h3>왜 바꿨나</h3>
 * <p>① (리뷰 2026-10-23 1차) 종전 {@code UpbitRestClient.throttle()} 은 마지막 요청 시각을 {@code static} 으로 공유하면서
 * {@code synchronized} 는 <b>인스턴스 잠금</b>이라, 서로 다른 인스턴스가 같은 시각을 동시에 읽고 함께 진행할 수 있었다.</p>
 * <p>② (리뷰 2026-10-23 2차) 1차 수정은 "다음 빈 시각" 을 <b>미리 예약</b>하고 잠금 밖에서 기다렸다. 예약 시각은 서로 떨어져 있어도
 * GC·스케줄링 지연으로 여러 스레드가 늦게 깨어나면 <b>이미 지난 예약들이 거의 동시에 통과</b>할 수 있었다 — 예약의 원자성이 실제
 * 통과 간격을 보장하지 않았다. 이제는 예약하지 않는다: 깨어난 스레드가 잠금 안에서 <b>지금 시각과 마지막 통과 시각</b>을 다시 비교해,
 * 간격이 지났을 때만 통과하고 그 자리에서 마지막 통과 시각을 갱신한다. 아직이면 남은 만큼 다시 기다린다.</p>
 *
 * <h3>무엇을 보장하나 (시험 범위에 맞춘 문구)</h3>
 * <ul>
 *   <li><b>통과 시각</b>(잠금 안에서 읽은 {@code nanoTime})은 인스턴스·스레드 수와 무관하게 서로 최소 {@code interval} 떨어진다 —
 *       늦게 깨어난 스레드도 이 조건을 다시 확인한 뒤에만 통과한다({@code UpbitRequestLimiterTest} 의 지연 깨어남 시험).</li>
 *   <li>🔴 <b>요청이 실제로 나가는 시각</b>은 통과 뒤 HTTP 전송까지의 지연만큼 더 늦을 수 있고, 그 지연이 스레드마다 다르면 서버가 받는
 *       간격은 {@code interval} 보다 좁아질 수 있다. 모의 HTTP 서버가 받은 시각으로 다중 인스턴스 경로를 확인했지만(같은 시험), 그것은
 *       그 실행 조건에서의 분포다 — 모든 조건에서 "어떤 1초에도 8회 이하" 를 보장한다는 뜻이 아니다.</li>
 *   <li>🔴 범위는 <b>이 프로세스 안</b>이다. 같은 IP 의 다른 프로세스(서버 감시 작업)는 이 간격을 모른다 — 합산은 감시 작업 속도와 함께
 *       정기 점검(429 🔴)으로 계속 본다.</li>
 * </ul>
 * <p>간격은 시스템 속성 {@code upbit.quotation.min-interval-ms}(기본 125ms, 종전 110ms)로 바꿀 수 있다.</p>
 */
final class UpbitRequestLimiter {

    static final long DEFAULT_INTERVAL_MS = 125;

    /** 대기 방법 — 시험에서 늦게 깨어나는 상황을 만들려고 바꿔 끼운다. */
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    static final Sleeper REAL_SLEEP = nanos -> TimeUnit.NANOSECONDS.sleep(nanos);

    /** 공개 시세(캔들·시세·호가 등) 요청 전체가 함께 쓰는 간격 — 모든 {@link UpbitRestClient} 인스턴스가 이것 하나를 쓴다. */
    static final UpbitRequestLimiter QUOTATION = new UpbitRequestLimiter(
            TimeUnit.MILLISECONDS.toNanos(Long.getLong("upbit.quotation.min-interval-ms", DEFAULT_INTERVAL_MS)), REAL_SLEEP);

    private final long intervalNanos;
    private final Sleeper sleeper;
    private boolean passedOnce;          // 이 객체의 잠금으로 보호
    private long lastPassNanos;          // 이 객체의 잠금으로 보호

    UpbitRequestLimiter(long intervalNanos, Sleeper sleeper) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("간격은 0보다 커야 한다: " + intervalNanos);
        this.intervalNanos = intervalNanos;
        this.sleeper = sleeper;
    }

    /**
     * 통과해도 되는지 잠금 안에서 확인한다. 되면 마지막 통과 시각을 지금으로 갱신하고 0 을, 아니면 더 기다릴 나노초를 돌려준다.
     * 통과한 시각은 {@code passedAt} 에 담는다(시험 관측용, null 가능).
     */
    synchronized long tryPass(long nowNanos, long[] passedAt) {
        if (passedOnce) {
            long earliest = lastPassNanos + intervalNanos;
            if (nowNanos - earliest < 0) {
                return earliest - nowNanos;
            }
        }
        passedOnce = true;
        lastPassNanos = nowNanos;
        if (passedAt != null) passedAt[0] = nowNanos;
        return 0;
    }

    /** 간격이 지날 때까지 기다렸다 통과한다. 깨어날 때마다 잠금 안에서 다시 확인한다 — 늦게 깨어나도 몰려 통과하지 않는다. */
    void acquire() throws InterruptedException {
        acquire(null);
    }

    /** {@link #acquire()} 와 같고, 통과 시각(잠금 안의 nanoTime)을 {@code passedAt[0]} 에 담는다. */
    void acquire(long[] passedAt) throws InterruptedException {
        while (true) {
            long waitNanos = tryPass(System.nanoTime(), passedAt);
            if (waitNanos <= 0) return;
            sleeper.sleepNanos(waitNanos);
        }
    }

    long intervalNanos() {
        return intervalNanos;
    }
}
