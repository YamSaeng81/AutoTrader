-- 20260415_analy.md Tier 2 §7 — LiveTradingSession 낙관적 락 도입.
-- race 시나리오: executeSessionBuy / reconcileOrphanBuyPositions / finalizeSellPosition /
-- updateSessionUnrealizedPnl 가 동일 세션을 read-modify-write 하면서 last-write-wins 덮어쓰기로
-- availableKrw 가 드리프트하던 문제를 JPA @Version 으로 차단한다.
ALTER TABLE live_trading_session
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
