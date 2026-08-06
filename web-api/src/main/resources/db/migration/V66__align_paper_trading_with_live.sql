-- 모의투자(PAPER)를 실전매매(LIVE)와 동일 조건으로 돌릴 수 있도록 세션 설정 컬럼을 맞춘다.
--
-- 배경(2026-08-06): PaperTradingService가 LiveTradingService와 완전히 다른 로직으로 매매하고
-- 있었다 — 진입 게이트 5종(EMA200/BlackSwan/Range/BTC가드/쿨다운) 전무, SL/TP 산정식 상이,
-- 전략 SELL 게이트 없음, 닫힌 캔들 게이팅 없음, 슬리피지 0. 그 결과 "페이퍼에서 검증하고
-- 실전에 올린다"는 절차가 성립하지 않았다(거래 모집단 자체가 다름).
--
-- 이 마이그레이션은 그 정렬의 일부로, LIVE 세션이 갖고 있는 3개 설정을 PAPER에도 부여한다.
-- 전부 NULL 허용 — NULL이면 risk_config(ExitRuleConfig) 기본값으로 폴백하며, 이는
-- LiveTradingService가 세션값 미지정 시 쓰는 경로와 동일하다.

ALTER TABLE paper_trading.virtual_balance
    ADD COLUMN stop_loss_pct  NUMERIC(5,2),
    ADD COLUMN invest_ratio   NUMERIC(5,4),
    ADD COLUMN max_hold_hours INT;

COMMENT ON COLUMN paper_trading.virtual_balance.stop_loss_pct IS
    '손절률(%) — NULL이면 risk_config 기본값(5.0). ATR 기반 산정의 하한으로 쓰인다(ExitRuleCalculator).';
COMMENT ON COLUMN paper_trading.virtual_balance.invest_ratio IS
    '투자 비율(0.1~1.0) — NULL이면 risk_config 기본값(0.80). availableKrw × 이 값이 매수금.';
COMMENT ON COLUMN paper_trading.virtual_balance.max_hold_hours IS
    '최대 보유시간(시) — time stop. 초과 시 손익과 무관하게 청산. NULL·0 이하면 비활성(LIVE 기본값과 동일).';
