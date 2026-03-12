-- Paper Trading 다중 세션 지원: position/order에 session_id 추가
ALTER TABLE paper_trading.position ADD COLUMN session_id BIGINT REFERENCES paper_trading.virtual_balance(id);
ALTER TABLE paper_trading."order" ADD COLUMN session_id BIGINT REFERENCES paper_trading.virtual_balance(id);

CREATE INDEX idx_paper_position_session_id ON paper_trading.position(session_id);
CREATE INDEX idx_paper_order_session_id ON paper_trading."order"(session_id);
