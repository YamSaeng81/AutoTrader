-- position.session_id 는 live_trading_session 과 dynamic_session 양쪽에서 재사용되는
-- BIGSERIAL 이라 값이 겹친다 (예: live id=3, dynamic id=3 공존 가능). 어느 세션 테이블 소속인지
-- 구분할 컬럼이 없어 동적 세션 포지션을 라이브 세션 reconcile 로직이 잘못 처리(KRW 미복원/오복원)하는
-- 문제가 있었다. session_kind 로 명확히 분리한다.
ALTER TABLE position ADD COLUMN session_kind VARCHAR(10) NOT NULL DEFAULT 'LIVE';

-- 과거 데이터는 전부 라이브/페이퍼 세션 소속이었으므로 LIVE 로 백필 (기본값으로 이미 채워짐, 명시적 보강)
UPDATE position SET session_kind = 'LIVE' WHERE session_kind IS NULL;

CREATE INDEX idx_position_session_kind_status ON position (session_kind, status);
