package com.cryptoautotrader.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * 20260415_analy.md Tier 2 §10 — Upbit API rate limit 대응.
 *
 * <p>🔴 <b>이 값은 거래소 한도가 아니라 내부 보수 한도다</b> (2026-10-13 정정). 업비트 공식 문서의 그룹별
 * 한도(초당): {@code order}(주문 생성·취소 후 재주문) <b>12</b>, {@code order-test} 8, {@code default}(주문 조회·
 * 취소·잔고 등) 30, {@code order-cancel-all} 2초당 1. 같은 그룹의 API 는 한도를 함께 차감한다. 종전 주석의
 * "주문 API 한도 초당 8건, 분당 200건" 은 현재 문서와 맞지 않는다 — 8 은 {@code order-test} 그룹의 값이다.</p>
 *
 * <p>이 제한기는 <b>그룹을 구분하지 않는 단일 창</b>(초당 {@value #PERMITS_PER_SECOND}건)이고, 적용 대상은
 * 주문 생성({@code order})·주문 취소·식별자 조회({@code default}) 세 호출뿐이다. 상태 폴링({@code getOrder})과
 * 잔고 조회는 이 창을 지나지 않는다. 응답 헤더 {@code Remaining-Req}({@code group=…; sec=남은 수})는
 * <b>읽지 않는다</b> — 거래소가 공지·장애 시 한도를 낮춰도 이 제한기는 알지 못한다.</p>
 *
 * <p>비상 청산(emergencyStopAll) 시 다수 SELL + CANCEL 이 동시에 발사되면 한도에 걸려 일부 주문이
 * 누락될 수 있다.</p>
 *
 * <p>🔴 <b>어느 1초 창에서도 {@value #PERMITS_PER_SECOND} 건을 넘지 않는 슬라이딩 윈도우</b>다
 * (2026-10-12 교체). 최근 발급 시각 {@value #PERMITS_PER_SECOND} 개를 기억하고, 가장 오래된 것이
 * 1초가 지나야 다음을 발급한다.</p>
 *
 * <p>🔴 종전 구현은 문서에는 슬라이딩 윈도우라고 적혀 있었지만 실제로는 <b>고정 주기 리필</b>이었다 —
 * 데몬이 생성 시점 기준 1초마다 부족분을 채웠다. 버스트가 그 리필 시각을 걸치면 1초 안에
 * <b>7 + 7 = 14건</b>이 나갈 수 있어 <b>내부 한도(7/초)를 두 배 가까이 넘었다</b>(주문 생성 그룹의
 * 거래소 한도 12/초도 넘을 수 있는 값이다). 전체 시험 실행에서 999ms 에 8건
 * 발급으로 드러났다. 주문 POST 가 한도로 거절되면 그 예외도 응답 불명({@code UNKNOWN})으로 처리되므로
 * (4xx 를 구분하지 않는다) 한도 초과는 미해결 주문과 신규 진입 차단으로 이어진다.</p>
 */
@Component
@Slf4j
public class UpbitApiRateLimiter {

    /** 내부 보수 한도 — 거래소 그룹 한도(order 12/초, default 30/초)보다 낮게 둔 단일 창. 근거 수치는 클래스 주석 참조. */
    static final int PERMITS_PER_SECOND = 7;
    /** acquire 시 최대 대기 시간 (초) */
    private static final long ACQUIRE_TIMEOUT_SEC = 10;

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** 최근 1초 안의 발급 시각({@link System#nanoTime()}) — 오래된 것이 앞. */
    private final Deque<Long> issued = new ArrayDeque<>();

    /**
     * API 호출 전 permit 확보. 한도 초과 시 창이 열릴 때까지 최대 10초 대기.
     * 타임아웃 시 false 반환 — 호출부가 재시도 또는 로깅 결정.
     */
    public boolean acquire() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ACQUIRE_TIMEOUT_SEC);
        synchronized (issued) {
            while (true) {
                long now = System.nanoTime();
                evictExpired(now);
                if (issued.size() < PERMITS_PER_SECOND) {
                    issued.addLast(now);
                    return true;
                }
                long waitNanos = Math.min(WINDOW_NANOS - (now - issued.peekFirst()), deadline - now);
                if (waitNanos <= 0) {
                    log.warn("[RateLimiter] {}초 대기 후에도 permit 확보 실패 — 호출부가 재시도 필요", ACQUIRE_TIMEOUT_SEC);
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(issued, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** 지금 즉시 발급 가능한 permit 수 (모니터링/테스트용) */
    public int availablePermits() {
        synchronized (issued) {
            evictExpired(System.nanoTime());
            return PERMITS_PER_SECOND - issued.size();
        }
    }

    private void evictExpired(long now) {
        while (!issued.isEmpty() && now - issued.peekFirst() >= WINDOW_NANOS) {
            issued.pollFirst();
        }
    }
}
