package com.cryptoautotrader.exchange.upbit;

import java.util.concurrent.TimeUnit;

/**
 * 업비트 공개 시세 요청의 <b>프로세스 전체 공용 예산</b> — 요청 시작 시각을 원자적으로 예약한다 (2026-10-23).
 *
 * <h3>왜 바꿨나 (리뷰 2026-10-23)</h3>
 * <p>종전 {@code UpbitRestClient.throttle()} 은 마지막 요청 시각을 {@code static} 으로 공유하면서도 {@code synchronized} 는
 * <b>인스턴스 잠금</b>이었다. {@code new UpbitRestClient()} 로 만든 다른 인스턴스(데이터 수집·컨트롤러 등)가 <b>같은 순간에 같은 시각을
 * 읽고</b> 함께 요청할 수 있었다 — 공유 변수를 쓰지만 예산 사용이 원자적이지 않았다. 업비트 캔들 한도는 <b>같은 IP 에서 초당 10회</b>를
 * 공유하므로(서버의 파이썬 감시 작업도 같은 IP 다), 인스턴스 사이 경합은 그대로 429 로 이어질 수 있다.</p>
 *
 * <h3>무엇을 보장하나</h3>
 * <p>{@link #reserve} 가 한 잠금 안에서 "다음 빈 시각" 을 하나 떼어 가므로, 인스턴스·스레드 수와 무관하게 <b>이 프로세스의 요청 시작은
 * 서로 최소 {@code interval} 만큼 떨어진다</b>. 잠금은 예약에만 쓰고 기다림은 잠금 밖에서 한다(대기 중인 스레드가 다른 예약을 막지 않는다).
 * 간격 125ms 면 어떤 1초 창에도 이 프로세스의 요청 시작은 8회 이하다.</p>
 * <p>🔴 보장 범위: <b>이 프로세스 안</b>이다. 같은 IP 의 다른 프로세스(감시 작업)는 이 예산을 모른다 — 합산이 한도 안에 드는지는
 * 감시 작업의 속도와 함께 따로 확인한다({@code UpbitRequestLimiterTest} 가 감시 요청이 겹치는 경우를 함께 센다).</p>
 *
 * <p>간격은 시스템 속성 {@code upbit.quotation.min-interval-ms}(기본 125ms)로 바꿀 수 있다. 종전 값은 110ms 였다 — 앱만으로
 * 초당 약 9.1회까지 쓸 수 있어 감시 작업(초당 2회)과 겹치면 한도를 넘었다(2026-10-23 운영 :19분 429).</p>
 */
final class UpbitRequestLimiter {

    static final long DEFAULT_INTERVAL_MS = 125;

    /** 공개 시세(캔들·시세·호가 등) 요청 전체가 함께 쓰는 예산 — 모든 {@link UpbitRestClient} 인스턴스가 이것 하나를 쓴다. */
    static final UpbitRequestLimiter QUOTATION = new UpbitRequestLimiter(
            TimeUnit.MILLISECONDS.toNanos(Long.getLong("upbit.quotation.min-interval-ms", DEFAULT_INTERVAL_MS)));

    private final long intervalNanos;
    private long nextSlotNanos = Long.MIN_VALUE;   // 이 객체의 잠금으로 보호

    UpbitRequestLimiter(long intervalNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("간격은 0보다 커야 한다: " + intervalNanos);
        this.intervalNanos = intervalNanos;
    }

    /** 다음 빈 시각을 하나 예약하고, 지금부터 그때까지 기다릴 나노초를 돌려준다(0 이면 바로). */
    synchronized long reserve(long nowNanos) {
        long slot = nextSlotNanos == Long.MIN_VALUE ? nowNanos : Math.max(nowNanos, nextSlotNanos);
        nextSlotNanos = slot + intervalNanos;
        return slot - nowNanos;
    }

    /** 예약한 시각까지 기다린다 — 잠금 밖에서. */
    void acquire() throws InterruptedException {
        long waitNanos = reserve(System.nanoTime());
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    long intervalNanos() {
        return intervalNanos;
    }
}
