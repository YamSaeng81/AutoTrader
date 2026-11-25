-- 1회 제한 B 후보 → 구판 복구 전 읽기 확인 v2 (v1.242 §3-3). PG 미검증·운영 미실행. 읽기 전용.
-- 원본: docs/evidence/one-entry-b-20261125/README.md §4 초안(수정하지 않음).
-- :sid = 1회 제한 시험 LIVE 세션 id.
-- 통과 = status 'STOPPED' 이고 아래 '_block' 열이 모두 0. '_info' 열은 판단 보조이며 단독 통과·차단 근거가 아니다.
-- 거래소 잔량(장부 밖 체결)은 이 SQL로 확정하지 않는다 — 기존 계좌 대조(A3·장부) 절차가 맡는다.
SELECT s.id, s.status, s.stopped_at,
  s.strategy_params->>'maxEntries' AS max_entries,
  -- ① 활성 주문: TradingConstants.ACTIVE_ORDER_STATES 와 같은 6개(정지 경로가 아직 끝내지 못한 주문 전부)
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = s.id
     AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS active_orders_block,
  -- ② 열린 포지션·잔량: OPEN/CLOSING 또는 size≠0 (확인 없는 취소 매수·반영 대기 매도는 포지션을 열어 둔다)
  (SELECT count(*) FROM position p WHERE p.session_kind = 'LIVE' AND p.session_id = s.id
     AND (p.status IN ('OPEN','CLOSING') OR p.size <> 0)) AS open_or_residual_positions_block,
  -- ③ 정리 대기: 🔴 v2 수정 — 미완료만. V87은 완료 행(done_at 있음)을 지우지 않는다.
  --    PositionCleanupRepository.countPending 과 같은 식(session_kind·session_id·done_at IS NULL).
  (SELECT count(*) FROM position_cleanup c WHERE c.session_kind = 'LIVE' AND c.session_id = s.id
     AND c.done_at IS NULL) AS cleanup_pending_block,
  -- ④ 미정산: OrderRepository.findSettlementDue 와 같은 대상 조건(시각 조건 제외).
  --    settlement_final 기본값이 FALSE 라 거래소에 안 나간 주문(exchange_order_id 없음)·V81 이전(fee_source 없음)은 뺀다.
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = s.id
     AND o.settlement_final = false AND o.fee_source IS NOT NULL AND o.exchange_order_id IS NOT NULL) AS unsettled_orders_block,
  -- 참고: 기존 정지 경고 기준(LiveTradingService.stopSession·completeCleanup의 countUnresolvedSubmissions). ①의 부분집합.
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = s.id
     AND o.state IN ('INTENT_READY','SUBMITTING','UNKNOWN')) AS unresolved_submissions_info,
  -- 참고: 체결 없음 근거가 없는 종결(근거 코드 없는 FAILED·확인 없는 CANCELLED). 정리 작업은 이를 사람 확인으로 둔다.
  --    NOT_EXECUTED_CODES 목록은 코드 상수라 여기서는 failure_code 값만 보인다 — 0 이 아니면 행을 따로 읽는다.
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = s.id
     AND ((o.state = 'CANCELLED' AND o.cancel_confirmed_at IS NULL)
       OR (o.state = 'FAILED' AND o.failure_code IS NULL))) AS terminal_without_proof_info
FROM live_trading_session s WHERE s.id = :sid;
