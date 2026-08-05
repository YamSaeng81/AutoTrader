package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 회귀 테스트 — <b>REST 폴백 대상 = WS 구독 합집합</b>.
 *
 * <p><b>사고 재현</b>: {@code pollRestTickerFallback}이 폴백 대상 코인을
 * {@code live_trading_session}에서만 뽑는 바람에 <b>동적(멀티코인) 세션 보유 코인이 통째로
 * 빠졌다</b>. WS가 멎으면 동적 세션의 SL 감시는 60초 폴링만 남는다.</p>
 *
 * <p>실측 피해 — 2026-08-03 pos 2383 KRW-ELSA: SL 76.19를 뚫었는데 74.50에서야 폴링이 잡아
 * (2.21%p 초과) −8.33%로 청산. 청산 사유가 {@code 손절}(폴링)이고, 하루 뒤 KRW-META2는
 * {@code 실시간 손절(WS)}로 정상 동작한 것이 이 경로 누락의 증거다.</p>
 *
 * <p>{@code wsClient}가 없으면(테스트 환경) 실제 구독은 스킵되므로, 여기서는 폴백이 참조하는
 * <b>합집합 계산</b>만 검증한다.</p>
 */
class WsSubscriptionUnionTest {

    @Test
    @DisplayName("★ LIVE와 DYNAMIC 코인이 모두 폴백 대상에 들어간다 — ELSA 누락 재발 방지")
    void unionCoversBothSources() {
        WsSubscriptionManager manager = new WsSubscriptionManager();

        manager.updateSource("LIVE", List.of("KRW-BTC", "KRW-ETH"));
        manager.updateSource("DYNAMIC", List.of("KRW-ELSA", "KRW-BTC"));

        assertThat(manager.getSubscribedCoins())
                .as("동적 세션 보유 코인이 빠지면 WS 끊김 시 SL 감시가 60초 폴링만 남는다")
                .containsExactlyInAnyOrder("KRW-BTC", "KRW-ETH", "KRW-ELSA");
    }

    @Test
    @DisplayName("소스가 비면 그 소스만 빠지고 나머지는 유지된다")
    void emptySourceRemovedOnly() {
        WsSubscriptionManager manager = new WsSubscriptionManager();

        manager.updateSource("LIVE", List.of("KRW-BTC"));
        manager.updateSource("DYNAMIC", List.of("KRW-ELSA"));
        manager.updateSource("DYNAMIC", List.of());

        assertThat(manager.getSubscribedCoins()).containsExactly("KRW-BTC");
    }

    @Test
    @DisplayName("구독이 하나도 없으면 빈 목록 — 폴백이 빈 markets로 REST를 때리지 않는다")
    void emptyWhenNoSources() {
        WsSubscriptionManager manager = new WsSubscriptionManager();

        assertThat(manager.getSubscribedCoins()).isEmpty();

        manager.updateSource("LIVE", List.of("KRW-BTC"));
        manager.updateSource("LIVE", null);
        assertThat(manager.getSubscribedCoins()).isEmpty();
    }
}
