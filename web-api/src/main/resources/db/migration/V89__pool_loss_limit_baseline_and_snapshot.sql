-- 공유 풀 손실 한도의 기준 (2026-10-14, 선행 조건 1번)
--
-- ■ 고치는 문제 — 등록된 손실 한도가 등록된 뜻으로 계산되지 않았다
--
-- `LIVE_ENTRY_PREREG.md` §6(2026-10-06 개정)은 단계 누적 −15%·최근 24시간 −3% 를 **그 단계의 고정 투입 자본**을 분모로,
-- **비용·미실현손익을 포함한 순자산 변화**로 정했다. 구현(`RiskManagementService.checkRisk`)은 RUNNING 세션의 초기자본 합을
-- 분모로(없으면 1,000만원), 손실 체결만 합산했고(gross, 미실현 제외), DYNAMIC 실자금 매수는 그 판정을 지나지도 않았다.
--
-- 순자산 변화는 `PoolLossGuard` 가 주문 반영값·포지션 시가로 계산한다. 여기에 두는 것은 그 계산의 두 기준뿐이다.
--
--   capital_stage_baseline  단계가 시작된 시각. 실자금 배정·예약이 처음 일어날 때 계좌 잠금 안에서 한 번 만들고 바꾸지
--                           않는다. 세션 정지·재시작·프로세스 재시작과 무관하다. 대상 포지션은 이 시각 이후에 열린 것이다.
--   pool_equity_snapshot    1분마다 남기는 순자산 변화. 최근 24시간 한도는 "지금 − 24시간 전" 이므로 24시간 전 값이 있어야
--                           한다. 8일이 지난 기록은 지운다.
--
-- 🔴 기존 행·운영 설정을 바꾸지 않는다. 새 표 두 개만 만든다.

CREATE TABLE IF NOT EXISTS capital_stage_baseline (
    stage          VARCHAR(32)   PRIMARY KEY,
    started_at     TIMESTAMPTZ   NOT NULL,
    total_cap_krw  NUMERIC(20,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE capital_stage_baseline IS
    '공유 풀 단계 시작 시각 — 손실 한도의 대상 포지션 범위. 한 번 만들면 바꾸지 않는다 (V89)';

CREATE TABLE IF NOT EXISTS pool_equity_snapshot (
    id              BIGSERIAL     PRIMARY KEY,
    stage           VARCHAR(32)   NOT NULL,
    taken_at        TIMESTAMPTZ   NOT NULL,
    cash_flow_krw   NUMERIC(24,8) NOT NULL,
    holdings_krw    NUMERIC(24,8) NOT NULL,
    net_change_krw  NUMERIC(24,8) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pool_equity_snapshot_stage_taken ON pool_equity_snapshot (stage, taken_at);

COMMENT ON TABLE pool_equity_snapshot IS
    '공유 풀 순자산 변화(현금 흐름 + 보유 평가액) 1분 기록 — 최근 24시간 손실 한도의 기준 (V89)';
