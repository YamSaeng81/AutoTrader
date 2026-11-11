-- 모의투자 선택 모드(LIVE_ALIGNED) 런타임 상태 2열 (2026-11-11, 총괄 v1.148 §3-3 — 로컬 후보·PostgreSQL 시험까지만 승인, 서버 DDL 승인 아님)
--
-- position.exit_decided_at   청산 대기(CLOSING)로 바꾼 판단 시각. 사유(exit_reason)·상태와 같은 트랜잭션에 쓴다. 재기동 뒤에도 보존된다.
-- virtual_balance.mdd_peak_krw  MDD 피크(총자산 고점). LIVE public.live_trading_session.mdd_peak_capital 대응. 코드는 "저장값보다 클 때만"
--                               조건부 UPDATE 로 올리기만 하며(version 불변), 엔티티 저장으로는 쓰지 않는다.
--
-- 🔴 NULL 허용, 기본값 없음, 기존 행을 채우지 않는다. 과거 판단 시각·미실현 고점은 알 수 없으므로 추정해 넣지 않는다.
--    키 없는 세션(종전 PAPER·사전 등록 66)은 코드가 두 열을 읽지도 쓰지도 않는다 — 운영 장부 행에 쓰기가 생기지 않는다.
--
-- 번호: 운영은 V94 까지 적용. main 의 미배포 V95__paper_session_slippage.sql 과 번호가 겹치지 않게 V96 으로 둔다.
--       최종 통합 때 V95 를 함께 내보내지 않으면 V95 를 V96 보다 뒤 번호로 옮겨야 한다(V95 를 나중에 적용하면 Flyway 순서 위반).
--       자세한 순서·충돌 해결은 docs/evidence 의 제출 README 에 적는다. 이 파일은 이미 적용된 SQL 을 바꾸지 않는다.
ALTER TABLE paper_trading.position ADD COLUMN exit_decided_at TIMESTAMPTZ;
ALTER TABLE paper_trading.virtual_balance ADD COLUMN mdd_peak_krw NUMERIC(20, 2);

COMMENT ON COLUMN paper_trading.position.exit_decided_at IS
    '선택 모드 청산 대기 판단 시각(CLOSING 전환과 같은 트랜잭션). NULL = 판단 없음 또는 V96 이전';
COMMENT ON COLUMN paper_trading.virtual_balance.mdd_peak_krw IS
    '선택 모드 MDD 피크(올리기만). NULL = 키 없는 세션 또는 아직 판정 전';
