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

    private void recompute() {
        if (wsClient == null) return;

        List<String> union = coinsBySource.values().stream()
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();

        if (union.equals(lastSubscribed)) {
            return; // 변경 없음 — 불필요한 재연결 방지
        }
        lastSubscribed = union;

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
