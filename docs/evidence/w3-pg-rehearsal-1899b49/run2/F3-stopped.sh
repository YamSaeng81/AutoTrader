SID=203; M="D:/tmp/w3-evidence/run2/f"; mkdir -p "$M"
PSQL="docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
EGS_LOCAL='1|0|1|true|-|-|0'   # 로컬: 실자금 LIVE 1 · DYNAMIC 0 · 기준 행 1 · 위험 한도 3.0/15.0 · 과거 비상 정지 없음
START_DATE=2026-12-01   # 시작 예정일(KST). 연기하면 이 줄을 문서에서 고쳐 커밋한 뒤 그 값으로 쓴다
NEW=1899b49f633f8aefdc66b15c42debdaba8b3852c   # v1.132: #26(11-06 13:36 KST) 결과 수용. 이전 값 ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8(#25). v1.129: #25(11-06 10:32 KST) 결과 수용. 이전 값 7a944723c2959898226c2ad80f6b079ab5678296(#24). v1.123: #24(11-05 19:41 KST) 결과 수용. 이전 값 1103f48505a1457029f6555547a87aa986535738(#23). v1.116: 개입 #23(2026-11-05 15:29 KST) 운영 결과 수용 뒤 기대 HEAD. 이전 값 fe01207f3f60e97e308925157f84a1bb2da04388(#22). 이전 값 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00(#21). 이전 값 b1d7664db73222aec5d86f2e9137debef0ff4df3(#20). 이전 값 ea4490099f0fa6b614fe71102534b0ba84c0eb7f(#19). 이전 값 5bb0544926a694832e75234670bb8f99dfe33bb0(#18), cad393956e131c257195fcd08fcac18c83802788(#17), f0872e2547c918a7b5146f027d325bd441ddf0d7(#16), 82d15d42fa22bb7d0f24a8243ebc888a2a7f071c
sc() { $PSQL -At -F ' | ' -c "
  SELECT count(*) AS total,
         count(*) FILTER (WHERE status = 'RUNNING' AND id NOT IN (576,577,578)) AS running_63,
         count(*) FILTER (WHERE status = 'STOPPED' AND id IN (576,577,578))     AS stopped_3,
         coalesce(string_agg(id || ':' || status, ' ' ORDER BY id) FILTER (WHERE NOT (
             (id IN (576,577,578) AND status = 'STOPPED') OR
             (id NOT IN (576,577,578) AND status = 'RUNNING'))), '-') AS mismatch
    FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" ; }
svc() { for s in backend db redis db-backup frontend; do docker inspect --format "$s {{.Image}} {{.State.StartedAt}}" "$($DC ps -q $s)"; done ; }
EG="SELECT (SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')) || '|' ||
           (SELECT count(*) FROM capital_stage_baseline) || '|' ||
           (SELECT (max_daily_loss_pct = 3.0 AND max_portfolio_drawdown_pct = 15.0)::text FROM risk_config ORDER BY id DESC LIMIT 1) || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           (SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '-') FROM dynamic_session WHERE trading_mode = 'REAL' AND status = 'EMERGENCY_STOPPED') || '|' ||
           ((SELECT count(*) FROM live_trading_session WHERE session_type = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')) +
            (SELECT count(*) FROM dynamic_session WHERE trading_mode = 'REAL' AND status NOT IN ('RUNNING','PAUSED','CREATED','EMERGENCY_STOPPED','STOPPED','DELETED')))"
EGS='1|0|1|true|157,161,166,167,168,183,184,185,186,187,188,189,190,191,192,193,194,195,196,200|92|0'
LIVE1="SELECT coalesce(string_agg(id || ':' || status, ',' ORDER BY id), '-') FROM live_trading_session WHERE session_type = 'REAL' AND status IN ('RUNNING','PAUSED','CREATED')"
BASE="SELECT stage || '|' || to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '|' || total_cap_krw FROM capital_stage_baseline"
BASEX='STAGE_1|2026-10-30 04:16:57.997762|100000.00'
KEEP="SELECT session_type = 'REAL' AND strategy_type = 'DAILY_SMA20' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1'
  AND initial_capital = 20200 AND invest_ratio = 0.995 AND max_investment = 20000 AND stop_loss_pct = 5.0
  AND max_hold_hours = 0 AND coalesce(strategy_params::text, '{}') = '{}'
  AND to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') = '2026-11-06 07:58:35.892055' FROM live_trading_session WHERE id = $SID"
# v1.56 (N08): BTC H1 입력 — B1 필요 완료 봉(운영 CandleFreshnessGuard 와 같은 2분 유예) 충족 | 최신 완료 봉 | 필요 봉 | B3 최근 20일 마감 봉(23:00 UTC 시작) 개수/20
BTCIN="WITH h AS (SELECT max(time) FILTER (WHERE time + interval '1 hour' <= now()) AS done, date_trunc('hour', now() - interval '2 minutes') - interval '1 hour' AS need FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'),
  d AS (SELECT (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 hour' - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM generate_series(0, 19) k)
SELECT coalesce(h.done >= h.need, false) || '|' || coalesce(to_char(h.done AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI'), '-') || '|' || to_char(h.need AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')
  || '|' || (SELECT count(c.close) FROM d JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t) || '/20' FROM h"
S=$($PSQL -At -c "SELECT started_at FROM live_trading_session WHERE id = $SID")
TOKEN=rt-token
$PSQL -v s="$S" <<'SQL' 2>&1 | tee "$M/f3.txt"
BEGIN TRANSACTION READ ONLY;
\echo '== G1 SID 203 판단 기록(시작 뒤 전부)'
SELECT created_at AT TIME ZONE 'Asia/Seoul' AS kst, signal, signal_price, was_executed, blocked_reason, reason FROM strategy_log WHERE session_type = 'LIVE' AND session_id = 203 AND created_at >= :'s'::timestamptz ORDER BY id;
\echo '== G2 SID 203 주문'
SELECT id, position_id, side, order_type, state, quantity, filled_quantity, reported_executed_funds, applied_executed_funds, reported_paid_fee, applied_paid_fee, fee_source, settlement_final, fill_accounting_pending, signal_reason, created_at AT TIME ZONE 'Asia/Seoul' AS kst FROM "order" WHERE session_kind = 'LIVE' AND session_id = 203 ORDER BY id;
\echo '== G3 SID 203 포지션'
SELECT id, coin_pair, status, size, entry_price, avg_price, invested_krw, position_fee, realized_pnl, exit_reason, opened_at AT TIME ZONE 'Asia/Seoul' AS opened_kst, closed_at AT TIME ZONE 'Asia/Seoul' AS closed_kst FROM position WHERE session_kind = 'LIVE' AND session_id = 203 ORDER BY id;
\echo '== G4 SID 203 세션 잔액'
SELECT status, available_krw, total_asset_krw FROM live_trading_session WHERE id = 203;
\echo '== G5 정리 대기 — 기대 0'
SELECT count(*) FROM position_cleanup WHERE done_at IS NULL;
\echo '== G6 SID 203 미해결 정산 불일치 — 기대 0행'
SELECT d.id, d.order_id, d.kind, d.previous_value, d.received_value, d.previous_state, d.received_state, d.detected_at AT TIME ZONE 'Asia/Seoul' AS kst FROM order_settlement_discrepancy d JOIN "order" o ON o.id = d.order_id WHERE o.session_kind = 'LIVE' AND o.session_id = 203 AND d.resolved_at IS NULL ORDER BY d.id;
ROLLBACK;
SQL
# 장부(체결 있는 주문의 반영값만, 없는 값은 0 으로 채우지 않는다): 세션 상태 · 세션 잔액 · 원시 장부 = 20200 − Σ매수(반영 대금 + 반영 수수료) + Σ매도(반영 대금 − 반영 수수료)
#   · 잔액 기대(주문별 두 자리, v1.33) = 20200 − Σ매수(요청액 − round(요청액 − 반영 대금 − 반영 수수료, 2)) + Σ매도 round(반영 대금 − 반영 수수료, 2) · 주문 순체결량 · 열린 포지션 수량 합계
LEDGER=$($PSQL -At -F '|' -c "
SELECT l.status, l.available_krw,
       20200 - coalesce(sum(o.applied_executed_funds + o.applied_paid_fee) FILTER (WHERE o.side = 'BUY' AND o.filled_quantity > 0), 0)
             + coalesce(sum(o.applied_executed_funds - o.applied_paid_fee) FILTER (WHERE o.side = 'SELL' AND o.filled_quantity > 0), 0),
       20200 - coalesce(sum(o.quantity - round(o.quantity - o.applied_executed_funds - o.applied_paid_fee, 2)) FILTER (WHERE o.side = 'BUY' AND o.filled_quantity > 0), 0)
             + coalesce(sum(round(o.applied_executed_funds - o.applied_paid_fee, 2)) FILTER (WHERE o.side = 'SELL' AND o.filled_quantity > 0), 0),
       coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'BUY'), 0) - coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'SELL'), 0),
       (SELECT coalesce(sum(size), 0) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING'))
  FROM live_trading_session l LEFT JOIN \"order\" o ON o.session_kind = 'LIVE' AND o.session_id = l.id
 WHERE l.id = $SID GROUP BY l.status, l.available_krw")
# 개수: 판단 기록 · 체결 매수 · 체결 매도 · 미종결 주문 · 회계 반영 대기 · 체결 주문의 보고값·반영값 없음 · 보고값≠반영값 · settlement_final 거짓(참고) · 열린 포지션 · 정상 사유 밖 청산 · 정리 대기 · 미해결 정산 불일치
CNT=$($PSQL -At -F '|' -c "
SELECT (SELECT count(*) FROM strategy_log WHERE session_type = 'LIVE' AND session_id = $SID AND created_at >= (SELECT started_at FROM live_trading_session WHERE id = $SID)),
       count(*) FILTER (WHERE side = 'BUY' AND coalesce(filled_quantity, 0) > 0),
       count(*) FILTER (WHERE side = 'SELL' AND coalesce(filled_quantity, 0) > 0),
       count(*) FILTER (WHERE state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')),
       count(*) FILTER (WHERE fill_accounting_pending),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND (applied_executed_funds IS NULL OR applied_paid_fee IS NULL OR reported_executed_funds IS NULL OR reported_paid_fee IS NULL)),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND (reported_executed_funds <> applied_executed_funds OR reported_paid_fee <> applied_paid_fee)),
       count(*) FILTER (WHERE coalesce(filled_quantity, 0) > 0 AND NOT settlement_final),
       (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING')),
       (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status = 'CLOSED' AND coalesce(exit_reason, '-') NOT IN ('STOP_LOSS','TAKE_PROFIT','TRAILING_STOP')),
       (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL),
       (SELECT count(*) FROM order_settlement_discrepancy d JOIN \"order\" o ON o.id = d.order_id WHERE o.session_kind = 'LIVE' AND o.session_id = $SID AND d.resolved_at IS NULL)
  FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = $SID")
# v1.57 (N08 검토): 입력 검사는 RUNNING 첫날 F3 에만 적용한다. F5-3 종료 대조에서는 조회하지 않는다.
# 입력 조회가 실패해도 기존 장부·계좌 대조를 보존한다. 실패한 조회의 부분 출력은 사용하지 않는다.
BTC3=; INP=
if [ "${LEDGER%%|*}" = RUNNING ]; then
BTC3=$($PSQL -At -c "$BTCIN") || BTC3=
INP=$($PSQL -At -F '|' -c "
SELECT count(*) FILTER (WHERE coalesce(reason, '') NOT LIKE '일봉 마감 봉 아님%' AND coalesce(reason, '') NOT LIKE '닫힌 캔들 미갱신%' AND NOT (
         coalesce(reason, '') LIKE '데이터 부족%' OR coalesce(reason, '') LIKE '일봉 종가 누락%' OR coalesce(reason, '') LIKE '전략 요구 캔들 미달%'
         OR coalesce(reason, '') LIKE 'BTC_MARKET_GUARD 판정 불가%' OR coalesce(blocked_reason, '') LIKE '%판정 불가%')),
       count(*) FILTER (WHERE coalesce(reason, '') LIKE '데이터 부족%' OR coalesce(reason, '') LIKE '일봉 종가 누락%' OR coalesce(reason, '') LIKE '전략 요구 캔들 미달%'
         OR coalesce(reason, '') LIKE 'BTC_MARKET_GUARD 판정 불가%' OR coalesce(blocked_reason, '') LIKE '%판정 불가%')
  FROM strategy_log WHERE session_type = 'LIVE' AND session_id = $SID AND created_at >= (SELECT started_at FROM live_trading_session WHERE id = $SID)") || INP=
fi
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18081/api/v1/account/summary > "$M/account-f3.json"
python3 - "$M/account-f1.json" "$M/account-f3.json" "$LEDGER" "$CNT" "$BTC3" "$INP" <<'PY' 2>&1 | tee -a "$M/f3.txt"
import json, sys
from decimal import Decimal as D
def acct(p):  # 업비트 KRW(주문 가능 + 묶임, 원 단위 반올림) · BTC(보유 + 묶임)
    d = json.load(open(p))["data"]
    btc = sum((D(str(h["balance"])) + D(str(h["locked"])) for h in d.get("holdings", []) if h["currency"] == "BTC"), D(0))
    return D(str(d["availableKrw"])) + D(str(d["lockedKrw"])), btc
try:
    k1, b1 = acct(sys.argv[1]); k3, b3 = acct(sys.argv[2])
    st, avail, raw, expect, net, posq = sys.argv[3].split("|"); avail, raw, expect, net, posq = D(avail), D(raw), D(expect), D(net), D(posq)
    logs, buys, sells, unres, pend, miss, diff, unfinal, opn, badexit, cleanup, disc = (int(x) for x in sys.argv[4].split("|"))
except Exception as e:  # 장부·개수·계좌 조회 중 하나라도 실패하면 정상 판정을 내지 않는다
    print(f"❔ 판정: 조회 실패({type(e).__name__}) — 정상 판정 아님. F3 를 다시 조회, 그대로면 §6-2 대응·보고")
    sys.exit(1)
print("== 대조 (시작 전 기준 = F1 의 account-f1.json)")
print("세션 상태", st)
print("업비트 KRW", k1, "→", k3, "· BTC", b1, "→", b3)
if miss or pend:
    print("세션 잔액", avail, "· 장부 기대 계산 불가(반영값 없음 또는 회계 반영 대기)")
else:
    print("세션 잔액", avail, "· 잔액 기대(주문별 두 자리)", expect, "· 차이", avail - expect, "· 원시 장부", raw, "· 원시 차이(참고)", avail - raw)
print("거래소 KRW 증감", k3 - k1, "· 세션 증감", avail - 20200, "· 차이", (k3 - k1) - (avail - 20200), "(±1원 = 반올림)")
print("거래소 BTC 증감", b3 - b1, "· 주문 순체결량", net, "· 열린 포지션 수량", posq)
print("판단 기록", logs, "· 체결 매수", buys, "· 체결 매도", sells, "· 미종결 주문", unres, "· 회계 반영 대기", pend,
      "· 반영값·보고값 없음", miss, "· 보고≠반영", diff, "· settlement_final 거짓(참고)", unfinal,
      "· 열린 포지션", opn, "· 정상 사유 밖 청산", badexit, "· 정리 대기", cleanup, "· 미해결 정산 불일치", disc)
match = (b3 - b1) == net == posq and abs((k3 - k1) - (avail - 20200)) <= 1
if st != "RUNNING":
    print(f"⛔ 판정: 세션 상태 {st} — 정상 운영 아님. 이 출력 전체를 별도 보고(§6-2 완료 확인). 재시작·재요청 없음")
elif logs == 0 or unres or cleanup or badexit or buys > 1 or diff or disc:
    print("🔴 판정: 판단 기록 없음·미종결 주문·정리 대기·정상 사유 밖 청산·중복 매수·보고값≠반영값·미해결 정산 불일치(G6) 중 하나 — §6-2 대응·보고")
elif miss or pend:
    print("❔ 판정: 확인 불가 — 체결 주문의 보고값·반영값이 비었거나 회계 반영 대기. G2 줄로 확인하고 F3 를 다시 조회, 그대로면 §6-2 대응·보고")
elif avail != expect or not match:
    print("🔴 판정: 세션 잔액·장부·거래소 증감·포지션 수량 중 설명되지 않는 차이 — §6-2 대응·보고")
elif buys == 0 and sells == 0 and opn == 0:
    print("✅ 판정: 매수 없음 — 현금 대기 (현재 조회 기준 일치)")
elif buys == 1 and sells == 0 and opn == 1:
    print("✅ 판정: 매수 1건 보유 중 (현재 조회 기준 일치 — 정산 최종 확정을 뜻하지 않음)")
elif buys == 1 and sells == 1 and opn == 0:
    print("✅ 판정: 매수 뒤 정상 사유(손절·익절·추적) 청산 (현재 조회 기준 일치 — 정산 최종 확정을 뜻하지 않음)")
else:
    print("🔴 판정: 위 세 경우 밖의 조합 — §6-2 대응·보고")
print("== 입력 (정산 판정과 별개, v1.57)")
if st != "RUNNING":
    print(f"입력 판정: 적용 제외 — 세션 {st}. 종료 확인은 위 장부·계좌 대조와 F5-3 조건으로 판단")
else:
    try:
        in_ok, in_done, in_need, in_b3 = sys.argv[5].split("|")
        dec, short = (int(x) for x in sys.argv[6].split("|"))
        if in_ok not in ("true", "false") or dec < 0 or short < 0:
            raise ValueError("입력 조회 형식")
    except Exception as e:
        print(f"❔ 입력 판정: 조회 실패({type(e).__name__}) — 입력 정상 판정 아님. 위 정산 대조와 별도로 G1·조회 오류를 보고, F3 재조회")
        sys.exit(1)
    print("현재 BTC H1 최신 완료 봉", in_done, "· 필요(2분 유예)", in_need, "· 충족", in_ok, "· 최근 20일 마감 봉", in_b3)
    print("시작 뒤 누적 일봉 판단", dec, "· 시작 뒤 누적 입력 부족·판정 불가 기록", short, "(과거 기록 포함, 현재 미회복 확정 아님)")
    if in_ok != "true" or in_b3 != "20/20":
        print("🔴 입력 판정: 현재 BTC 입력 미충족 — 정상 전략 HOLD 로 쓰지 않는다. G1·이 출력을 보고(데이터 부족을 매수로 바꾸거나 기준을 완화하지 않는다)")
    elif dec == 0:
        print("🔴 입력 판정: 일봉 판단 없음 — 현재 BTC 입력 충족만으로 정상 전략 HOLD 로 쓰지 않는다. G1·이 출력을 보고")
    elif short:
        print("🔴 입력 판정: 시작 뒤 입력 부족·판정 불가 기록 있음 — 현재 BTC 입력은 충족. 과거 사유·회복 여부를 G1 과 대조해 보고, 정상 전략 HOLD 로 확정하지 않는다")
    else:
        print("✅ 입력 판정: 현재 입력 충족·일봉 판단 있음 — HOLD 면 정상 전략 HOLD, blocked_reason 이 있으면 관문 차단")
PY
