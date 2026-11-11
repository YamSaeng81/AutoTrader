package com.cryptoautotrader.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 실시간 시세 → <b>실자금 보호 점검</b>(LIVE·DYNAMIC 실시간 손절) 전달 경계 (2026-11-12, 총괄 v1.153 F).
 *
 * <p>종전에는 두 엔진의 처리기가 각각 {@code @EventListener @Async("marketDataExecutor")} 였다. 실행기(코어 2·최대 4·대기열 100·AbortPolicy)가
 * 가득 차면 <b>먼저 불린 리스너의 접수 거절 예외가 발행 쪽으로 올라가</b>, 같은 시세를 나머지 리스너(다른 엔진 손절·시세 장부)가 받지 못했고
 * 발행 쪽(웹소켓 콜백·REST 폴백 묶음·강제 갱신)이 예외로 끊겼다(총괄 v1.152 F 관측).</p>
 *
 * <p>이 경계는 엔진별 <b>코인별 최신 시세 대기칸</b>을 둔다. 시세가 오면 대기칸을 최신 값으로 덮고, 엔진별 처리 작업이 접수돼 있지 않으면
 * 같은 실행기에 하나 접수한다. 처리 작업은 대기칸을 비울 때까지 엔진 처리기(트랜잭션 프록시)를 부른다.
 * <ul>
 *   <li>접수 거절: 그 시세를 버리지 않는다 — 대기칸에 남기고 <b>엔진별 거절 수·고정 사유</b>를 남긴다. 다음 시세 도착 때, 그리고 대기칸이
 *       비어 있지 않은 동안 1초마다({@link #retryPending}) 다시 접수한다(새 시세가 없어도 실행기가 풀리면 처리된다). 발행 쪽으로 예외를
 *       던지지 않으므로 다른 엔진·시세 장부·다른 코인 전달이 끊기지 않는다. 대기가 유효기간(120초)을 넘으면 처리기의 기존 N02 검사가 그 값을
 *       버린다 — 그때 남는 보호는 기존 정기 평가 경로다.</li>
 *   <li>가격이 아닌 값(0·음수·null)이나 더 이른 관측 시각의 시세는 대기 중인 유효 시세를 덮지 않는다.</li>
 *   <li>대기칸은 엔진 × 코인 하나씩이라 커지지 않는다(무제한 큐 아님). 같은 코인의 처리 전 새 시세는 최신 값으로 대체된다 — 손절 판정은
 *       최신 가격으로 하고, 낡은 값은 처리기의 기존 유효기간 검사(N02)가 버린다.</li>
 *   <li>실행기 크기·전역 거절 정책(AbortPolicy)·다른 비동기 작업(거래소 복구 이벤트 등)의 거절 계약은 바꾸지 않는다. 호출 스레드에서 대신
 *       처리하지 않는다(웹소켓 콜백 스레드를 DB 대기에 묶지 않는다 — 종전 비동기화의 이유).</li>
 *   <li>엔진 처리기의 다른 예외는 처리기가 기존대로 로그로 남긴다. 이 경계는 접수 거절만 다룬다.</li>
 * </ul>
 * 처리 순서·잠금은 종전과 같다 — 같은 실행기 스레드에서 같은 처리기 메서드(프록시, {@code @Transactional})를 부른다.</p>
 */
@Component
public class RealtimeProtectionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeProtectionDispatcher.class);

    /** 접수 거절의 고정 사유 — 로그·관측 값에 같은 문구를 쓴다. */
    public static final String REJECT_REASON = "실행기 포화 — 보호 점검 접수 거절, 코인별 최신 시세 대기 유지";

    public static final String LIVE = "LIVE";
    public static final String DYNAMIC = "DYNAMIC";

    private final Executor executor;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    public RealtimeProtectionDispatcher(@Qualifier("marketDataExecutor") Executor executor,
                                        @Lazy LiveTradingService liveTradingService,
                                        @Lazy DynamicTradingService dynamicTradingService) {
        this.executor = executor;
        lanes.put(LIVE, new Lane(LIVE, liveTradingService::onRealtimePriceEvent));
        lanes.put(DYNAMIC, new Lane(DYNAMIC, dynamicTradingService::onRealtimePriceEvent));
    }

    @EventListener
    public void onPrice(RealtimePriceEvent event) {
        if (event == null || event.getCoinCode() == null) return;
        for (Lane lane : lanes.values()) {
            lane.offer(event);
        }
    }

    /** 대기칸이 남은 엔진만 다시 접수한다 — 비어 있으면 아무것도 하지 않는다. 접수 거절이면 거절 수가 늘고 대기칸은 그대로다. */
    @Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
    public void retryPending() {
        for (Lane lane : lanes.values()) {
            if (!lane.pending.isEmpty()) lane.schedule();
        }
    }

    /** 관측 — 엔진별 누적 거절 수·지금 대기 중인 코인 수·처리한 시세 수·대체(덮어쓴) 수. */
    public Snapshot snapshot(String engine) {
        Lane l = lanes.get(engine);
        return new Snapshot(engine, l.rejected.get(), l.pending.size(), l.processed.get(), l.replaced.get(), l.lastRejectReason);
    }

    public record Snapshot(String engine, long rejected, int pendingCoins, long processed, long replaced, String lastRejectReason) { }

    /** 대기 중인 시세를 새 시세로 바꾸지 않는 경우 — 유효 가격을 비가격으로, 또는 더 늦은 관측을 더 이른 관측으로. */
    static boolean keepOld(RealtimePriceEvent old, RealtimePriceEvent neu) {
        if (MarkPriceBook.isPrice(old.getPrice()) && !MarkPriceBook.isPrice(neu.getPrice())) return true;
        return old.getObservedAt() != null && neu.getObservedAt() != null && neu.getObservedAt().isBefore(old.getObservedAt());
    }

    private final class Lane {
        final String engine;
        final Consumer<RealtimePriceEvent> handler;
        final Map<String, RealtimePriceEvent> pending = new ConcurrentHashMap<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong replaced = new AtomicLong();
        volatile String lastRejectReason;

        Lane(String engine, Consumer<RealtimePriceEvent> handler) {
            this.engine = engine;
            this.handler = handler;
        }

        void offer(RealtimePriceEvent e) {
            pending.merge(e.getCoinCode(), e, (old, neu) -> {
                replaced.incrementAndGet();
                return keepOld(old, neu) ? old : neu;
            });
            schedule();
        }

        void schedule() {
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException ex) {   // TaskRejectedException 도 이 하위 형이다
                scheduled.set(false);
                long n = rejected.incrementAndGet();
                lastRejectReason = REJECT_REASON;
                if (n == 1 || n % 100 == 0) {
                    log.warn("[실시간 보호] {} {} (대기 코인 {}, 누적 거절 {})", engine, REJECT_REASON, pending.size(), n);
                }
            }
        }

        void drain() {
            try {
                List<String> coins = new ArrayList<>(pending.keySet());
                while (!coins.isEmpty()) {
                    for (String coin : coins) {
                        RealtimePriceEvent e = pending.remove(coin);
                        if (e == null) continue;
                        try {
                            handler.accept(e);
                        } catch (RuntimeException ex) {
                            log.error("[실시간 보호] {} 처리기 예외 — coin={}", engine, coin, ex);
                        }
                        processed.incrementAndGet();
                    }
                    coins = new ArrayList<>(pending.keySet());
                }
            } finally {
                scheduled.set(false);
            }
            // 비운 뒤·플래그를 내리기 전 사이에 들어온 시세 — 다시 접수한다(접수 거절이면 대기칸에 남는다).
            if (!pending.isEmpty()) schedule();
        }
    }
}
