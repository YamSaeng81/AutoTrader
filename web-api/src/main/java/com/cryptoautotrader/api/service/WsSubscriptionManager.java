package com.cryptoautotrader.api.service;

import com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Upbit WebSocket 구독 목록을 여러 소스(라이브 매매, 동적 멀티코인, ...)가 공유하기 위한 조정자.
 *
 * <p>{@link UpbitWebSocketClient#connect(List)}는 호출마다 구독 목록 전체를 "교체"한다
 * (Upbit WS는 연결당 단일 구독 메시지 — 코인별 개별 추가/제거가 없다). 여러 서비스가 각자
 * connect()를 직접 호출하면 나중 호출이 이전 호출의 구독을 지워버린다.
 * 이 클래스가 소스별 희망 코인 목록을 보관하고 합집합을 계산해 단일 진실 소스로 connect()를
 * 호출한다.</p>
 */
@Component
@Slf4j
public class WsSubscriptionManager {

    @Autowired(required = false)
    private UpbitWebSocketClient wsClient;

    private final Map<String, List<String>> coinsBySource = new ConcurrentHashMap<>();
    private volatile List<String> lastSubscribed = List.of();

    /**
     * 특정 소스(예: "LIVE", "DYNAMIC")의 구독 희망 코인 목록을 갱신하고, 전체 합집합으로
     * WebSocket 구독을 재계산한다. 빈 목록을 넘기면 해당 소스는 구독에서 제외된다.
     */
    public synchronized void updateSource(String source, List<String> coins) {
        if (coins == null || coins.isEmpty()) {
            coinsBySource.remove(source);
        } else {
            coinsBySource.put(source, List.copyOf(coins));
        }
        recompute();
    }

    /**
     * 현재 구독 중인 전체 코인(모든 소스의 합집합).
     *
     * <p>WS가 끊겼을 때의 REST 폴백은 <b>반드시 이 목록</b>을 대상으로 해야 한다. 소스별
     * 세션 테이블(LIVE만, DYNAMIC만)을 따로 훑으면 한쪽이 통째로 누락된다 —
     * 실제로 2026-08-03 KRW-ELSA 건이 그렇게 빠졌다 ({@code pollRestTickerFallback} 주석 참조).</p>
     */
    public List<String> getSubscribedCoins() {
        return lastSubscribed;
    }

    private void recompute() {
        // 합집합 계산은 wsClient 유무와 무관하게 항상 수행한다 — getSubscribedCoins()가
        // REST 폴백의 기준 목록이므로, 클라이언트 빈이 없다고 이 상태가 비어 있으면 안 된다.
        List<String> union = coinsBySource.values().stream()
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();

        if (union.equals(lastSubscribed)) {
            return; // 변경 없음 — 불필요한 재연결 방지
        }
        lastSubscribed = union;

        if (wsClient == null) return;   // 구독 부수효과만 스킵

        if (union.isEmpty()) {
            wsClient.disconnect();
            log.info("[WsSubscription] 구독 해제 (모든 소스 비어 있음)");
        } else {
            wsClient.connect(union);
            log.info("[WsSubscription] 구독 갱신: {}개 코인 {} (소스별: {})",
                    union.size(), union, coinsBySource);
        }
    }
}
