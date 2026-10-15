package com.cryptoautotrader.api.restartharness;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.service.MarkPriceBook;
import com.cryptoautotrader.api.service.OrderExecutionEngine;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 실제 프로세스 재시작 시험의 진입점 (2026-10-13) — <b>운영 jar 에 없다</b>({@code restartHarness} 소스 세트).
 *
 * <p>평가 루프의 매수와 같은 경계를 지난다: 한 트랜잭션에서 포지션(슬롯)·예약·의도를 쓰고 커밋한 뒤, 커밋 후
 * 실행기가 claim·제출한다({@code reserveAndPrepareIntent} → {@code submitPreparedOrderAfterCommit}). 신호
 * 판단만 건너뛴다.</p>
 */
@RestController
@Profile("restart-harness")
@RequestMapping("/api/v1/restart-harness")
public class RestartHarnessController {

    private final OrderExecutionEngine engine;
    private final PositionRepository positionRepository;
    private final TransactionTemplate tx;
    private final MarkPriceBook markPriceBook;

    public RestartHarnessController(OrderExecutionEngine engine, PositionRepository positionRepository,
                                    PlatformTransactionManager txManager, MarkPriceBook markPriceBook) {
        this.engine = engine;
        this.positionRepository = positionRepository;
        this.tx = new TransactionTemplate(txManager);
        this.markPriceBook = markPriceBook;
    }

    /** 실시간 시세 한 건 — 대역에는 시세 구독이 없으므로 시험이 넣는다(2026-10-15, 포지션별 한도 S15). */
    @PostMapping("/mark")
    public Map<String, Object> mark(@RequestParam String coin, @RequestParam BigDecimal price) {
        markPriceBook.record(coin, price, Instant.now());
        return Map.of("coin", coin, "price", price);
    }

    @PostMapping("/buy")
    public Map<String, Object> buy(@RequestParam String kind, @RequestParam Long sessionId,
                                   @RequestParam BigDecimal krw, @RequestParam(defaultValue = "KRW-DOGE") String coin,
                                   @RequestParam(defaultValue = "300") BigDecimal signalPrice) {
        return tx.execute(st -> {
            PositionEntity pos = positionRepository.save(PositionEntity.builder()
                    .coinPair(coin).side("BUY")
                    .entryPrice(signalPrice).avgPrice(signalPrice)
                    .size(BigDecimal.ZERO).investedKrw(krw)
                    .entryAllocationKrw(krw)   // 엔진과 같이 진입 배정액을 남긴다 (V90)
                    .status("OPEN").sessionKind(kind).sessionId(sessionId)
                    .openedAt(Instant.now()).build());
            OrderRequest req = new OrderRequest();
            req.setCoinPair(coin);
            req.setSide("BUY");
            req.setOrderType("MARKET");
            req.setQuantity(krw);
            req.setReason("재시작 시험 매수");
            req.setSignalPrice(signalPrice);
            req.setSessionId(sessionId);
            req.setSessionKind(kind);
            req.setPositionId(pos.getId());
            Long orderId = engine.reserveAndPrepareIntent(req);
            engine.submitPreparedOrderAfterCommit(orderId, req);
            return Map.<String, Object>of("orderId", orderId, "positionId", pos.getId());
        });
    }
}
