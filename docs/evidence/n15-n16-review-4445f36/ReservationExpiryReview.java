package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Focused unit reproduction: real candidate reservation code, mocked persistence and guards. */
public class ReservationExpiryReview {
    private static final Instant E = Instant.parse("2026-12-01T00:10:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("5000");

    private static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void run(String kind, String crossing, boolean expectApproved) throws Exception {
        EntryClock clock = new EntryClock();
        clock.setForTest(Clock.fixed(crossing.equals("already-expired") ? E : E.minusMillis(1), ZoneOffset.UTC));
        LiveTradingSessionRepository liveRepo = mock(LiveTradingSessionRepository.class);
        DynamicSessionRepository dynRepo = mock(DynamicSessionRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PortfolioManager portfolio = mock(PortfolioManager.class);
        CapitalPoolLimits limits = mock(CapitalPoolLimits.class);
        PoolLossGuard loss = mock(PoolLossGuard.class);
        PositionLimitGuard positionLimit = mock(PositionLimitGuard.class);
        EntityManager em = mock(EntityManager.class);
        Query lock = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        when(lock.getSingleResult()).thenReturn(1L);
        when(limits.isRegistered()).thenReturn(true);
        when(limits.getPerPositionCapKrw()).thenReturn(new BigDecimal("20000"));
        when(limits.getTotalCapKrw()).thenReturn(new BigDecimal("100000"));
        when(limits.getMaxConcurrentPositions()).thenReturn(3);
        when(positions.sumPoolExposureKrw(anyLong())).thenReturn(BigDecimal.ZERO);
        when(positionLimit.checkSessionForEntry(anyString(), anyLong()))
                .thenReturn(CapitalReservationService.Decision.approve("test-position"));
        when(positionLimit.checkUnjudgedForEntry(anyString(), anyLong(), any()))
                .thenReturn(CapitalReservationService.Decision.approve("test-mark"));
        when(portfolio.getCapitalSnapshot()).thenReturn(
                new PortfolioManager.CapitalSnapshot(new BigDecimal("100000"), Instant.now()));
        when(loss.checkForEntry(any(), any())).thenAnswer(inv -> {
            if (crossing.equals("loss-gate")) clock.setForTest(Clock.fixed(E, ZoneOffset.UTC));
            return CapitalReservationService.Decision.approve("test-loss");
        });
        LiveTradingSessionEntity live = LiveTradingSessionEntity.builder().availableKrw(new BigDecimal("20000")).build();
        DynamicSessionEntity dyn = DynamicSessionEntity.builder().availableKrw(new BigDecimal("20000")).tradingMode("REAL").build();
        when(liveRepo.findById(203L)).thenReturn(Optional.of(live));
        when(dynRepo.findById(203L)).thenReturn(Optional.of(dyn));
        doAnswer(inv -> {
            if (crossing.equals("session-refresh")) clock.setForTest(Clock.fixed(E, ZoneOffset.UTC));
            return null;
        }).when(em).refresh(eq(dyn), any(jakarta.persistence.LockModeType.class));
        CapitalReservationService service = new CapitalReservationService(liveRepo, orders, positions, portfolio, limits, loss, positionLimit);
        field(service, "entryClock", clock);
        field(service, "dynamicSessionRepository", dynRepo);
        field(service, "em", em);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        CapitalReservationService.Decision result;
        try {
            result = service.reserveForBuy(kind, 203L, AMOUNT, -1L, E);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        BigDecimal balance = kind.equals("LIVE") ? live.getAvailableKrw() : dyn.getAvailableKrw();
        System.out.printf("%s crossing=%s expiry=%s clock_after=%s approved=%s code=%s available=%s%n",
                kind, crossing, E, clock.now(), result.approved(), result.code(), balance.toPlainString());
        if (result.approved() != expectApproved) throw new AssertionError("unexpected reproduction result");
        if (expectApproved && balance.compareTo(new BigDecimal("15000")) != 0) throw new AssertionError("no debit observed");
        if (!expectApproved && !CapitalReservationService.ENTRY_EXPIRED.equals(result.code())) throw new AssertionError("wrong rejection");
    }

    public static void main(String[] args) throws Exception {
        run("LIVE", "already-expired", false);
        run("DYNAMIC", "already-expired", false);
        run("LIVE", "no-crossing", true);
        run("DYNAMIC", "no-crossing", true);
        run("LIVE", "loss-gate", true);
        run("DYNAMIC", "loss-gate", true);
        run("DYNAMIC", "session-refresh", true);
        System.out.println("7/7 reproduction expectations matched; 3 post-check expiry cases still approve and debit.");
    }
}
