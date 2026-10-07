package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 5번 ② — <b>취소 결과가 불명일 때 주문을 취소된 것으로 기록해서는 안 된다.</b>
 *
 * <h3>왜 중요한가</h3>
 *
 * <p>자동정지가 OFF 이므로 위험 경보 대응은 사람이 정지를 부르고, 정지는 먼저 그 세션의 활성
 * 주문을 취소한다({@code LiveTradingService.cancelSessionActiveOrders}). 그런데 취소는 세 갈래로
 * 끝날 수 있다:</p>
 *
 * <ol>
 *   <li><b>취소 성공</b> — 주문이 사라졌다.</li>
 *   <li><b>취소 실패인데 이미 체결</b> — 거래소에 물어보면 FILLED 다. 이 경우를 CANCELLED 로
 *       기록하면 <b>체결을 취소로 오기록</b>한다.</li>
 *   <li>🔴 <b>결과 불명</b> — 취소 요청이 시간 초과·네트워크 오류로 끝나고 <b>상태 재조회도
 *       실패</b>했다. 주문이 거래소에 살아 있는지 알 수 없다.</li>
 * </ol>
 *
 * <p>③에서 로컬 상태를 {@code CANCELLED} 로 박으면 <b>아무도 그 주문을 더 추적하지 않는다</b> —
 * {@code pollActiveOrders()}(5초 주기)는 활성 주문만 거래소와 동기화하므로 취소로 기록된 주문은
 * 폴링 대상에서 빠진다. 그 주문이 나중에 체결되면 <b>관리 주체 없는 포지션</b>과 어긋난 KRW 가
 * 남는다. <b>취소 요청을 보냈다는 사실이 주문이 사라졌다는 증거가 아니다.</b></p>
 *
 * <p>그래서 ③에서는 상태를 바꾸지 않고 <b>활성으로 남겨</b> 5초 폴러가 결말을 내게 한다. 거짓
 * 종결보다 미결이 낫다 — 미결은 자동으로 해소되지만 거짓 종결은 영구히 남는다.</p>
 */
@DisplayName("취소 결과가 불명이면 취소로 기록하지 않는다 (선행 조건 5)")
class CancelOutcomeUnknownTest extends IntegrationTestBase {

    @Autowired
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /**
     * 🔴 세션과 포지션을 실제로 만든다. 처음에는 존재하지 않는 {@code sessionId=901} 만 넣었는데,
     * 상태 전이 후처리(세션 조회·KRW 복원)가 실패하면서 그 예외가 삼켜진 채 트랜잭션이
     * rollback-only 로 표시돼 커밋에서 {@code UnexpectedRollbackException} 이 났다 — 테스트가
     * 재현하려는 것과 무관한 실패였다.
     */
    private final List<Long> createdSessions = new ArrayList<>();

    /**
     * 🔴 생성한 행을 반드시 치운다. H2 가 테스트 간에 공유되므로 남겨두면 전체 실행에서 세션·주문
     * 수를 세는 다른 테스트가 깨진다 — 단독 통과·전체 실패라는 가장 찾기 어려운 실패가 된다.
     */
    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionIdAndStateIn(id,
                    List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"))
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    private OrderEntity submittedOrder() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)          // 매수 체결 대기 — 취소 대상이 되는 상태
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN")
                .sessionKind("LIVE")
                .sessionId(session.getId())
                .openedAt(Instant.now())
                .build());

        createdSessions.add(session.getId());
        return orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .orderType("LIMIT")
                .quantity(new BigDecimal("3000.00000000"))
                .state("SUBMITTED")
                .sessionKind("LIVE")
                .sessionId(session.getId())
                .positionId(pos.getId())
                .exchangeOrderId("exchange-uuid-1")
                .createdAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("취소 실패 + 재조회 실패 → CANCELLED 로 박지 않고 활성으로 남긴다")
    void unknownOutcomeMustNotBeRecordedAsCancelled() throws Exception {
        OrderEntity order = submittedOrder();

        // 취소 요청이 시간 초과로 끝나고, 상태 재조회도 실패한다 — 결과를 알 수 없는 상태
        Mockito.doThrow(new RuntimeException("request timed out"))
                .when(upbitOrderClient).cancelOrder("exchange-uuid-1");
        Mockito.doThrow(new RuntimeException("request timed out"))
                .when(upbitOrderClient).getOrder("exchange-uuid-1");

        try {
            orderExecutionEngine.cancelOrder(order.getId());
        } catch (Exception ignored) {
            // 호출부가 실패를 보는 것은 허용된다 — 중요한 것은 상태를 거짓으로 종결하지 않는 것이다
        }

        OrderEntity after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getState())
                .as("결과가 불명인데 CANCELLED 로 박으면 5초 폴러의 추적 대상에서 빠져 영구 미해결이 된다")
                .isNotEqualTo("CANCELLED");
        assertThat(after.getState())
                .as("활성 상태로 남아야 pollActiveOrders() 가 결말을 낸다")
                .isIn("SUBMITTED", "PENDING", "PARTIAL_FILLED");
        assertThat(after.getCancelledAt())
                .as("취소 시각이 찍히면 취소된 것으로 읽힌다")
                .isNull();
    }

    @Test
    @DisplayName("취소 실패 + 거래소에 주문이 아직 살아 있으면 CANCELLED 로 박지 않는다")
    void stillActiveOnExchangeMustNotBeRecordedAsCancelled() throws Exception {
        OrderEntity order = submittedOrder();

        Mockito.doThrow(new RuntimeException("cancel rejected"))
                .when(upbitOrderClient).cancelOrder("exchange-uuid-1");

        // 재조회는 성공했지만 거래소에서 아직 대기(wait) 상태 — 즉 주문은 살아 있다.
        // 🔴 체결이 아니라는 이유로 CANCELLED 로 기록하면 살아 있는 주문을 추적에서 뺀다.
        OrderResponse stillOpen = new OrderResponse();
        stillOpen.setState("wait");
        stillOpen.setExecutedVolume(BigDecimal.ZERO);
        Mockito.doReturn(stillOpen).when(upbitOrderClient).getOrder("exchange-uuid-1");

        try {
            orderExecutionEngine.cancelOrder(order.getId());
        } catch (Exception ignored) {
            // 호출부가 실패를 보는 것은 허용된다
        }

        OrderEntity after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getState())
                .as("거래소에 살아 있는 주문을 CANCELLED 로 기록하면 아무도 더 쫓지 않는다")
                .isNotEqualTo("CANCELLED");
        assertThat(after.getCancelledAt()).isNull();
    }
}
