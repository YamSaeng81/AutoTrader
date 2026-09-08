package com.cryptoautotrader.api.util;

import com.cryptoautotrader.strategy.Candle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 한 틱 동안만 유효한 캔들 캐시 — <b>LIVE·DYNAMIC·PAPER 공용</b> (2026-09-08).
 *
 * <h3>왜 필요한가</h3>
 * <p>세 엔진 모두 틱마다 세션을 순회하며 세션별로 {@code (코인, 타임프레임)} 캔들을 조회한다.
 * 그런데 <b>여러 세션이 같은 조합을 본다.</b> 페이퍼는 코인 N × 전략 M 격자 실험이라 특히 심하고,
 * 동적 세션은 워치리스트를 세션마다 통째로 훑어 세션 수만큼 같은 조회를 반복한다.</p>
 *
 * <p>PAPER 는 이 캐시를 갖고 있었지만 <b>LIVE·DYNAMIC 에는 없었다</b> — 08-19 정합성 감사가
 * 기록한 "한 엔진에만 적용하고 나머지를 잊는다" 목록의 마지막 항목이다.</p>
 *
 * <h3>왜 ThreadLocal 인가</h3>
 * <p>DYNAMIC 의 세션 처리는 {@code @Transactional} 프록시를 경유하므로
 * ({@code self.processTick(session)}) 캐시를 인자로 넘기려면 공개 메서드 시그니처를 바꿔야 한다.
 * 스케줄러 스레드마다 스코프를 잡으면 그럴 필요가 없다.</p>
 *
 * <p>스코프 밖(= 웹소켓 체결 콜백 등 틱이 아닌 경로)에서는 캐시가 없으므로 {@code loader} 가
 * 그대로 실행된다 — <b>동작은 같고 조회 횟수만 준다.</b></p>
 *
 * <h3>stale 위험이 없는 이유</h3>
 * <p>스코프가 틱 하나로 끝난다. 틱 사이에 캐시가 남아 있지 않으므로 다음 틱은 항상 새 데이터를
 * 읽는다. 한 틱 안에서 같은 조합의 캔들이 달라질 일은 없다 — 오히려 세션마다 다른 시점의
 * 데이터를 보던 종전 동작이 미묘하게 비일관적이었다.</p>
 */
public final class TickCandleCache {

    private TickCandleCache() {}

    private static final ThreadLocal<Map<String, List<Candle>>> SCOPE = new ThreadLocal<>();

    /**
     * 틱 스코프를 연다. <b>반드시 {@code try/finally} 로 {@link #end()} 와 짝지을 것</b> —
     * 빠뜨리면 그 스레드에 캐시가 남아 다음 틱이 옛 캔들을 본다.
     */
    public static void begin() {
        SCOPE.set(new HashMap<>());
    }

    /** 틱 스코프를 닫는다. {@code finally} 에서 호출한다. */
    public static void end() {
        SCOPE.remove();
    }

    /**
     * 캐시를 경유한 캔들 조회 — 같은 {@code (코인, 타임프레임)} 은 틱당 한 번만 실제 조회한다.
     *
     * <p>스코프가 열려 있지 않으면 {@code loader} 를 그대로 실행한다(캐시 없음).</p>
     */
    public static List<Candle> get(String coinPair, String timeframe, Supplier<List<Candle>> loader) {
        Map<String, List<Candle>> cache = SCOPE.get();
        if (cache == null) {
            return loader.get();
        }
        return cache.computeIfAbsent(coinPair + ":" + timeframe, k -> loader.get());
    }

    /** 현재 스코프의 캐시 항목 수 — 로그·테스트용. 스코프가 없으면 −1. */
    public static int size() {
        Map<String, List<Candle>> cache = SCOPE.get();
        return cache == null ? -1 : cache.size();
    }
}
