"""P7 v2 로컬 시나리오 — 격리 PG(cat-rt-pg 컨테이너 안의 새 DB)와 가짜 거래소 잔고로 대조기를 검증한다.
운영 DB·거래소·HTTP 접속 없음. 기준 DB p7v2_base = rt_ledger(V94, SID203 LIVE REAL 1행) 사본. 시나리오마다 템플릿 복제 후 삭제.
거래소 잔고는 시나리오마다 손으로 계산한 고정 숫자다(대조기 식으로 만들지 않는다).
사용: python -I scenarios.py <out_dir>
"""
import json, os, subprocess, sys, copy
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decimal import Decimal as D
import reconcile as R

sys.stdout.reconfigure(encoding='utf-8', newline='\n')
HERE = os.path.dirname(os.path.abspath(__file__))
EXTRACT = open(os.path.join(HERE, 'extract.sql'), encoding='utf-8').read()
FP = open(os.path.join(HERE, 'fingerprint.sql'), encoding='utf-8').read()
PSQL = ['docker', 'exec', '-i', 'cat-rt-pg', 'psql', '-U', 'trader', '-X', '-At', '-v', 'ON_ERROR_STOP=1']


def psql(db, sql):
    r = subprocess.run(PSQL + ['-d', db, '-f', '-'], input=sql.encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f'psql rc={r.returncode}: {r.stderr.decode("utf-8", "replace")}')
    return r.stdout.decode('utf-8')


def body(out):
    # BEGIN/COMMIT 표시 줄을 빼고 나머지를 합친다(json_agg 는 원소 사이에 줄바꿈을 넣는다).
    return '\n'.join(l for l in out.splitlines() if l not in ('BEGIN', 'COMMIT', 'SET')).strip()


# ── SQL 조각 ──────────────────────────────────────────────────
def dyn(i, avail, status='RUNNING', mode='REAL', tf='H1', strat='COMPOSITE_BREAKOUT', cap='20000'):
    return (f"INSERT INTO dynamic_session (id,strategy_type,timeframe,initial_capital,available_krw,total_asset_krw,status,trading_mode,created_at) "
            f"VALUES ({i},'{strat}','{tf}',{cap},{avail},{cap},'{status}','{mode}',now());\n")


def upd(kind, i, avail=None, status=None):
    t = 'dynamic_session' if kind == 'DYNAMIC' else 'live_trading_session'
    sets = [f'available_krw={avail}'] if avail is not None else []
    if status:
        sets.append(f"status='{status}'")
    return f"UPDATE {t} SET {', '.join(sets)} WHERE id={i};\n"


def pos(i, kind, sid, coin, size, status='OPEN'):
    return (f"INSERT INTO position (id,session_kind,session_id,coin_pair,side,entry_price,avg_price,size,status,opened_at) "
            f"VALUES ({i},'{kind}',{sid},'{coin}','LONG',1,1,{size},'{status}',now());\n")


def pos_status(i, status):
    return f"UPDATE position SET status='{status}' WHERE id={i};\n"


def q(v):
    return 'NULL' if v is None else (f"'{v}'" if isinstance(v, str) and not v.replace('.', '').isdigit() else str(v))


def order(kind, sid, pid, coin, side, qty, state, filled, funds, fee, uuid, reserved='=', rep_funds='=', rep_fee='=',
          pending=False, oid=None):
    reserved = (qty if side == 'BUY' else None) if reserved == '=' else reserved
    rep_funds = funds if rep_funds == '=' else rep_funds
    rep_fee = fee if rep_fee == '=' else rep_fee
    cols = 'session_kind,session_id,position_id,coin_pair,side,order_type,quantity,reserved_krw,client_order_id,state,filled_quantity,' \
           'reported_executed_funds,reported_paid_fee,applied_executed_funds,applied_paid_fee,fill_accounting_pending,exchange_order_id,created_at'
    vals = [f"'{kind}'", str(sid), q(pid), f"'{coin}'", f"'{side}'", "'MARKET'", str(qty), q(reserved), f"'c-{uuid}'",
            f"'{state}'", q(filled), q(rep_funds), q(rep_fee), q(funds), q(fee), 'true' if pending else 'false', q(uuid), 'now()']
    if oid is not None:
        cols = 'id,' + cols
        vals = [str(oid)] + vals
    return f'INSERT INTO "order" ({cols}) VALUES ({",".join(vals)});\n'


# ── 등록표 ────────────────────────────────────────────────────
SID203 = {'kind': 'LIVE', 'id': 203, 'mode': 'REAL', 'strategy_type': 'DAILY_SMA20', 'timeframe': 'H1', 'initial_capital': '20200',
          'coin_pair': 'KRW-BTC', 'code_sha': '0e1d2a02', 'effective_from': '2026-11-01T00:00:00Z', 'effective_to': None,
          'rules': {'max_filled_buys_in_window': 1}}
DYN900 = {'kind': 'DYNAMIC', 'id': 900, 'mode': 'REAL', 'strategy_type': 'COMPOSITE_BREAKOUT', 'timeframe': 'H1', 'initial_capital': '20000',
          'coin_pair': 'DYNAMIC', 'code_sha': '0e1d2a02', 'effective_from': '2026-11-18T00:00:00Z', 'effective_to': None,
          'rules': {'max_open_positions': 1, 'excluded_coins': ['KRW-BTC'], 'max_buy_request_krw': '20000'}}
DYN901 = dict(DYN900, id=901, initial_capital='15000', effective_to='2026-12-31T00:00:00Z')
REG = {'registry_version': 'p7v2-draft-local', 'sessions': [SID203, DYN900]}


def reg_with(*sessions):
    return {'registry_version': 'p7v2-draft-local', 'sessions': [copy.deepcopy(s) for s in sessions]}


def ex(krw, locked='0', open_orders=(), done=(), **coins):
    acc = {'KRW': {'balance': krw, 'locked': locked}}
    for c, v in coins.items():
        acc[c] = {'balance': v, 'locked': '0'}
    return {'accounts': acc, 'open_orders': list(open_orders), 'done_order_ids': list(done)}


# 공통 거래 조각(숫자는 손 계산 — README §2-3 표)
BASE900 = dyn(900, '20000')
ETH_BUY = pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') + \
    order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'FILLED', '0.0032', '15991.987654', '7.99599383', 'u-eth-buy') + \
    upd('DYNAMIC', 900, '4000.02')
ETH_SELL = order('DYNAMIC', 900, 9001, 'KRW-ETH', 'SELL', '0.0032', 'FILLED', '0.0032', '16010.5', '8.00525', 'u-eth-sell') + \
    pos_status(9001, 'CLOSED') + upd('DYNAMIC', 900, '20002.51')
EX0 = ex('100000')
EX_ETH = ex('84000.01635217', done=['u-eth-buy'], ETH='0.0032')
EX_RT = ex('100002.51110217', done=['u-eth-buy', 'u-eth-sell'], ETH='0')

S = []


def sc(name, group, expect, codes=(), forbid=(), reg=REG, base_sql=BASE900, base_ex=EX0, now_sql='', now_ex=None,
       flows=(), now_hook=None, now_ex_before=None, now_query_errors=None, note=''):
    S.append(dict(name=name, group=group, expect=expect, codes=list(codes), forbid=list(forbid), reg=reg, base_sql=base_sql,
                  base_ex=base_ex, now_sql=now_sql, now_ex=now_ex, flows=flows, now_hook=now_hook,
                  now_ex_before=now_ex_before, now_query_errors=now_query_errors, note=note))


# 1 정상 ETH 보유·왕복
sc('A1_eth_hold', '정상 ETH 보유', 'OK', forbid=['ACCOUNT_KRW_MISMATCH'], now_sql=ETH_BUY, now_ex=EX_ETH,
   note='반올림 설명분 −0.00364783, 조정 잔차 0')
sc('A2_eth_roundtrip', '정상 ETH 왕복', 'OK', now_sql=ETH_BUY + ETH_SELL, now_ex=EX_RT)
# 2 기존 BTC 보유(DYNAMIC 시작 전 SID203 이 0.0002 보유) — 현재 보유 0.0002 이지만 구간 증감 0
SID_BTC = pos(8001, 'LIVE', 203, 'KRW-BTC', '0.0002') + \
    order('LIVE', 203, 8001, 'KRW-BTC', 'BUY', '20000.00', 'FILLED', '0.0002', '19990.00', '9.995', 'u-btc-203') + \
    upd('LIVE', 203, '200.01')
sc('B1_preexisting_btc', '기존 BTC 보유', 'OK', base_sql=SID_BTC, base_ex=ex('80000.005', done=['u-btc-203'], BTC='0.0002'),
   now_sql=dyn(900, '20000', status='CREATED'), now_ex=ex('80000.005', done=['u-btc-203'], BTC='0.0002'),
   forbid=['ACCOUNT_COIN_MISMATCH'], note='기초 보유 BTC 0.0002 기록, 구간 BTC 증감 0')
sc('B2_preexisting_btc_plus_eth', '기존 BTC 보유+병행 ETH', 'OK', base_sql=SID_BTC + BASE900,
   base_ex=ex('80000.005', done=['u-btc-203'], BTC='0.0002'),
   now_sql=ETH_BUY, now_ex=ex('64000.02135217', done=['u-btc-203', 'u-eth-buy'], BTC='0.0002', ETH='0.0032'))
# 3 서로 다른 시작/정지 — 구간 안 새 배정(내부 이전)·정지 세션 포함
sc('C1_start_stop_window', '서로 다른 시작/정지', 'OK', reg=reg_with(SID203, DYN900, DYN901), base_sql='',
   now_sql=BASE900 + ETH_BUY + ETH_SELL + upd('DYNAMIC', 900, status='STOPPED') + dyn(901, '15000', status='STOPPED', cap='15000'),
   now_ex=EX_RT, note='900·901 초기 배정은 internal_allocations 로만 나옴')
sc('C2_stopped_session_unsettled_cash', '정지 세션 잔액 불일치', 'ANOMALY', codes=['SESSION_LEDGER_MISMATCH'],
   now_sql=ETH_BUY + ETH_SELL + upd('DYNAMIC', 900, avail='20000', status='STOPPED'), now_ex=EX_RT,
   note='정지 뒤 세션 잔액이 주문 장부와 다르면 정지해도 지우지 않고 이상')
# 4 같은 ID 다른 종류 / 잘못된 등록
bad_tf = copy.deepcopy(DYN900); bad_tf['timeframe'] = 'M15'
sc('D1_contract_mismatch_timeframe', '등록 계약 불일치(주기)', 'ANOMALY', reg=reg_with(SID203, bad_tf), now_sql=ETH_BUY, now_ex=EX_ETH,
   codes=['REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_ORDER', 'UNREGISTERED_REAL_POSITION', 'UNREGISTERED_REAL_SESSION_ACTIVE'])
live900 = dict(copy.deepcopy(SID203), id=900, coin_pair='KRW-ETH', strategy_type='COMPOSITE_BREAKOUT', initial_capital='20000',
               effective_from='2026-11-18T00:00:00Z', rules={})
sc('D2_same_id_other_kind', '같은 ID 다른 종류', 'ANOMALY', reg=reg_with(SID203, live900), now_sql=ETH_BUY, now_ex=EX_ETH,
   codes=['REGISTERED_SESSION_ROW_MISSING', 'UNREGISTERED_REAL_SESSION_ACTIVE', 'UNREGISTERED_REAL_ORDER'])
sc('D3_registry_duplicate_key', '잘못된 등록표(중복 키)', 'ANOMALY', reg=reg_with(SID203, DYN900, DYN900), now_sql=ETH_BUY, now_ex=EX_ETH,
   codes=['REGISTRY_INVALID', 'UNREGISTERED_REAL_ORDER'])
sc('D4_db_mode_lowercase_paper', '잘못된 모드 값(paper 소문자)', 'ANOMALY', base_sql=dyn(900, '20000', mode='paper'),
   now_sql=ETH_BUY, now_ex=EX_ETH, codes=['REGISTRY_CONTRACT_MISMATCH', 'UNREGISTERED_REAL_ORDER'],
   note='정확한 PAPER 가 아니면 실자금으로 보고 등록 계약 모드와 대조')
bad_rule = copy.deepcopy(DYN900); bad_rule['rules'] = {'max_buys_per_day': 1}
sc('D5_registry_unknown_rule', '잘못된 등록표(알 수 없는 규칙)', 'ANOMALY', reg=reg_with(SID203, bad_rule), now_sql=ETH_BUY, now_ex=EX_ETH,
   codes=['REGISTRY_INVALID'])
# 5 미등록 주문
XRP901 = dyn(901, '5000.00', cap='5000') + pos(9101, 'DYNAMIC', 901, 'KRW-XRP', '10') + \
    order('DYNAMIC', 901, 9101, 'KRW-XRP', 'BUY', '5000.00', 'FILLED', '10', '4997.5', '2.49875', 'u-xrp-901') + upd('DYNAMIC', 901, '0.00')
sc('E1_unregistered_session_order', '미등록 세션 주문', 'ANOMALY', now_sql=ETH_BUY + XRP901,
   now_ex=ex('79000.01760217', done=['u-eth-buy', 'u-xrp-901'], ETH='0.0032', XRP='10'),
   codes=['UNREGISTERED_REAL_SESSION_ACTIVE', 'UNREGISTERED_REAL_ORDER', 'UNREGISTERED_REAL_POSITION', 'ACCOUNT_KRW_MISMATCH', 'ACCOUNT_COIN_MISMATCH'])
sc('E2_orphan_order_deleted_session', '세션 행 없는 주문', 'ANOMALY', now_sql=ETH_BUY +
   order('DYNAMIC', 950, None, 'KRW-XRP', 'BUY', '5000.00', 'SUBMITTED', None, None, None, 'u-orphan', rep_funds=None, rep_fee=None),
   now_ex=ex('84000.01635217', done=['u-eth-buy'], open_orders=['u-orphan'], ETH='0.0032'),
   codes=['UNREGISTERED_REAL_ORDER', 'ORDER_IN_FLIGHT'], note='정지·삭제돼도 등록 밖 주문은 탐지')
sc('E3_exchange_order_not_in_db', '거래소에만 있는 주문(수동 매매)', 'ANOMALY', now_sql=ETH_BUY,
   now_ex=ex('79000.01760217', done=['u-eth-buy', 'u-manual'], ETH='0.0032', XRP='10'),
   codes=['EXCHANGE_ORDER_NOT_IN_DB', 'ACCOUNT_KRW_MISMATCH', 'ACCOUNT_COIN_MISMATCH'])
sc('E4_manual_trade_declared', '수동 매매 신고', 'OK', now_sql=ETH_BUY,
   now_ex=ex('79000.01760217', done=['u-eth-buy', 'u-manual'], ETH='0.0032', XRP='10'),
   flows=[{'kind': 'MANUAL_TRADE', 'ref': 'u-manual', 'currency': 'KRW', 'amount': '-4999.99875'},
          {'kind': 'MANUAL_TRADE', 'ref': 'u-manual', 'currency': 'XRP', 'amount': '10'}])
# 6 외부 입출금
sc('F1_deposit_declared', '외부 입금 신고', 'OK', now_sql=ETH_BUY, now_ex=ex('134000.01635217', done=['u-eth-buy'], ETH='0.0032'),
   flows=[{'kind': 'DEPOSIT', 'currency': 'KRW', 'amount': '50000', 'ref': 'dep-1'}])
sc('F2_deposit_undeclared', '외부 입금 미신고', 'ANOMALY', now_sql=ETH_BUY, now_ex=ex('134000.01635217', done=['u-eth-buy'], ETH='0.0032'),
   codes=['ACCOUNT_KRW_MISMATCH'])
sc('F3_flows_unknown', '입출금 이력 미확인', 'HOLD', now_sql=ETH_BUY, now_ex=EX_ETH, flows=None, codes=['EXTERNAL_FLOWS_UNCONFIRMED'],
   forbid=['ACCOUNT_KRW_MISMATCH'])
sc('F4_coin_withdraw_declared', '코인 출금 신고', 'ANOMALY', base_sql=SID_BTC + BASE900,
   base_ex=ex('80000.005', done=['u-btc-203'], BTC='0.0002'), now_ex=ex('80000.005', done=['u-btc-203'], BTC='0'),
   flows=[{'kind': 'WITHDRAW', 'currency': 'BTC', 'amount': '-0.0002', 'ref': 'wd-1'}], codes=['ACCOUNT_COIN_UNBACKED'],
   forbid=['ACCOUNT_COIN_MISMATCH'], note='증감식은 신고로 맞지만 SID203 열린 포지션 0.0002 를 거래소 실보유가 뒷받침하지 못함 — 이상')
# 7 부분 체결·수수료·반올림
PART = pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.001') + \
    order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'CANCELLED', '0.001', '5000.123456', '2.50006173', 'u-eth-part') + \
    upd('DYNAMIC', 900, '14997.38')
sc('G1_partial_fill_cancel', '부분 체결 후 취소', 'OK', now_sql=PART, now_ex=ex('94997.37648227', done=['u-eth-part'], ETH='0.001'))
TWO_SQL = pos(8001, 'LIVE', 203, 'KRW-BTC', '0.0002') + \
    order('LIVE', 203, 8001, 'KRW-BTC', 'BUY', '20000.00', 'FILLED', '0.0002', '19990', '10', 'u-btc-203') + upd('LIVE', 203, '200.00') + \
    pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') + \
    order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'FILLED', '0.0032', '15992', '8', 'u-eth-buy') + upd('DYNAMIC', 900, '4000.00')
sc('G2_unexplained_1_50_two_sessions', '원 단위 2세션 설명 불가 1.50원', 'ANOMALY', now_sql=TWO_SQL,
   now_ex=ex('63998.50', done=['u-btc-203', 'u-eth-buy'], BTC='0.0002', ETH='0.0032'), codes=['ACCOUNT_KRW_MISMATCH'],
   note='초안식 세션 수×1원(=2원)이면 통과했을 차이')
sc('G3_exact_two_sessions', '원 단위 2세션 정확', 'OK', now_sql=TWO_SQL,
   now_ex=ex('64000', done=['u-btc-203', 'u-eth-buy'], BTC='0.0002', ETH='0.0032'))
sc('G4_settlement_correction_between', '구간 사이 수수료 정정', 'OK',
   base_sql=BASE900 + pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') +
   order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'FILLED', '0.0032', '15991.987654', '7.9', 'u-eth-buy', oid=7001) +
   upd('DYNAMIC', 900, '4000.11'),
   base_ex=ex('84000.11234600', done=['u-eth-buy'], ETH='0.0032'),
   now_sql="UPDATE \"order\" SET applied_paid_fee=7.99599383, reported_paid_fee=7.99599383, settlement_version=1 WHERE id=7001;\n" +
   upd('DYNAMIC', 900, '4000.02'),
   now_ex=EX_ETH, note='기준에서 수수료 7.9 로 반영된 주문이 7.99599383 으로 정정 — 증감은 정정분만')
# 8 UNKNOWN·CLOSING·반영 대기·조회 실패·불안정
sc('H1_unknown_order', 'UNKNOWN 주문', 'ANOMALY', now_sql=pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') +
   order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'UNKNOWN', None, None, None, 'u-unk', rep_funds=None, rep_fee=None) +
   upd('DYNAMIC', 900, '4000.00'), now_ex=ex('84000.01635217', done=['u-unk'], ETH='0.0032'),
   codes=['ORDER_UNKNOWN', 'ORDER_EFFECT_PENDING'], note='보류 사유가 있어도 UNKNOWN 은 이상으로 남음')
SELL_SUB = order('DYNAMIC', 900, 9001, 'KRW-ETH', 'SELL', '0.0032', 'SUBMITTED', '0', None, None, 'u-sell', rep_funds=None, rep_fee=None) + \
    pos_status(9001, 'CLOSING')
sc('H2_closing_inflight', 'CLOSING·진행 중 매도', 'HOLD', now_sql=ETH_BUY + SELL_SUB,
   now_ex=ex('84000.01635217', done=['u-eth-buy'], open_orders=['u-sell'], ETH='0.0032'),
   codes=['POSITION_CLOSING', 'ORDER_IN_FLIGHT', 'EXCHANGE_OPEN_ORDERS'], forbid=['ACCOUNT_KRW_MISMATCH'])
sc('H3_accounting_pending', '회계 반영 대기', 'HOLD', now_sql=pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') +
   order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '16000.00', 'FILLED', '0.0032', None, None, 'u-eth-buy',
         rep_funds='15991.987654', rep_fee='7.99599383', pending=True) + upd('DYNAMIC', 900, '4000.00'),
   now_ex=EX_ETH, codes=['ACCOUNTING_PENDING', 'ORDER_EFFECT_PENDING'])
sc('H4_query_failed', '거래소 조회 실패', 'HOLD', now_sql=ETH_BUY, now_ex=None, now_query_errors=['거래소 잔고 조회 시간 초과(가정)'],
   codes=['QUERY_FAILED', 'OBSERVATION_MISSING'])
sc('H5_hold_plus_known_anomaly', '보류+이미 확인된 정산 불일치', 'ANOMALY',
   now_sql=ETH_BUY +
   "UPDATE \"order\" SET applied_paid_fee=7.99 WHERE exchange_order_id='u-eth-buy';\n" + SELL_SUB,
   now_ex=ex('84000.01635217', done=['u-eth-buy'], open_orders=['u-sell'], ETH='0.0032'),
   codes=['SETTLEMENT_MISMATCH', 'ORDER_IN_FLIGHT', 'POSITION_CLOSING'], note='미확정이 이상을 덮지 않음')
sc('H6_unresolved_discrepancy', '미해결 정산 불일치 행', 'ANOMALY', now_sql=ETH_BUY +
   "INSERT INTO order_settlement_discrepancy (order_id,detected_at,kind) SELECT id, now(), 'FEE_CHANGED' FROM \"order\" WHERE exchange_order_id='u-eth-buy';\n",
   now_ex=EX_ETH, codes=['SETTLEMENT_DISCREPANCY_UNRESOLVED'])
sc('H7_unstable_db_between_reads', '읽는 사이 DB 변경', 'HOLD', now_sql=ETH_BUY, now_ex=EX_ETH,
   now_hook=order('DYNAMIC', 900, None, 'KRW-XRP', 'BUY', '3000.00', 'INTENT_READY', None, None, None, 'u-new', rep_funds=None, rep_fee=None) +
   upd('DYNAMIC', 900, '1000.02'),
   codes=['UNSTABLE_DB', 'ORDER_IN_FLIGHT'], forbid=['SESSION_LEDGER_MISMATCH'], note='지문 앞값과 스냅샷 값이 달라 통과시키지 않음')
sc('H8_unstable_exchange_between_reads', '읽는 사이 거래소 변경', 'HOLD', now_sql=ETH_BUY, now_ex=EX_ETH,
   now_ex_before=ex('100000'), codes=['UNSTABLE_EXCHANGE'])
# 9 세션별 규칙 — 단독 규칙을 DYNAMIC 에 복사하지 않음
sc('R1_dynamic_excluded_coin_buy', 'DYNAMIC BTC 진입(제외 코인)', 'ANOMALY', now_sql=pos(9001, 'DYNAMIC', 900, 'KRW-BTC', '0.00016') +
   order('DYNAMIC', 900, 9001, 'KRW-BTC', 'BUY', '16000.00', 'FILLED', '0.00016', '15992', '8', 'u-btc-900') + upd('DYNAMIC', 900, '4000.00'),
   now_ex=ex('84000', done=['u-btc-900'], BTC='0.00016'), codes=['RULE_EXCLUDED_COIN_BUY'], forbid=['ACCOUNT_KRW_MISMATCH', 'ACCOUNT_COIN_MISMATCH'])
sc('R2_dynamic_two_buys_ok', 'DYNAMIC 구간 매수 2회(단독 규칙 미적용)', 'OK', now_sql=ETH_BUY + ETH_SELL +
   pos(9002, 'DYNAMIC', 900, 'KRW-XRP', '10') +
   order('DYNAMIC', 900, 9002, 'KRW-XRP', 'BUY', '5000.00', 'FILLED', '10', '4997.5', '2.49875', 'u-xrp-900') + upd('DYNAMIC', 900, '15002.51'),
   now_ex=ex('95002.51235217', done=['u-eth-buy', 'u-eth-sell', 'u-xrp-900'], ETH='0', XRP='10'), forbid=['RULE_FILLED_BUYS'])
sc('R3_sid203_two_buys', 'SID203 구간 매수 2회', 'ANOMALY', now_sql=pos(8001, 'LIVE', 203, 'KRW-BTC', '0.0002') +
   order('LIVE', 203, 8001, 'KRW-BTC', 'BUY', '10000.00', 'FILLED', '0.0001', '9995', '5', 'u-b1') +
   order('LIVE', 203, 8001, 'KRW-BTC', 'BUY', '10000.00', 'FILLED', '0.0001', '9995', '5', 'u-b2') + upd('LIVE', 203, '200.00'),
   now_ex=ex('80000', done=['u-b1', 'u-b2'], BTC='0.0002'), codes=['RULE_FILLED_BUYS', 'RULE_DUP_BUY_POSITION'])
sc('R4_dynamic_request_over_cap', 'DYNAMIC 요청액 상한 초과', 'ANOMALY', base_sql=dyn(900, '30000', cap='30000'),
   reg=reg_with(SID203, dict(DYN900, initial_capital='30000')),
   now_sql=pos(9001, 'DYNAMIC', 900, 'KRW-ETH', '0.0032') +
   order('DYNAMIC', 900, 9001, 'KRW-ETH', 'BUY', '24000.00', 'FILLED', '0.0032', '23988', '12', 'u-eth-big') + upd('DYNAMIC', 900, '6000.00'),
   now_ex=ex('76000', done=['u-eth-big'], ETH='0.0032'), codes=['RULE_BUY_REQUEST_CAP'], forbid=['ACCOUNT_KRW_MISMATCH'])


def observe(db, ex_after, ex_before=None, hook=None, flows=(), query_errors=None):
    fp_pre = body(psql(db, FP))
    if hook:
        psql(db, hook)
    snap = json.loads(body(psql(db, EXTRACT)))
    fp_post = body(psql(db, FP))
    return {'db_fp_pre': fp_pre, 'exchange_before': ex_before if ex_before is not None else ex_after, 'db': snap,
            'exchange_after': ex_after, 'db_fp_post': fp_post,
            'external_flows': None if flows is None else list(flows), 'query_errors': query_errors or []}


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    psql('postgres', 'DROP DATABASE IF EXISTS p7v2_s WITH (FORCE);')
    rows, fails = [], 0
    for s in S:
        psql('postgres', 'DROP DATABASE IF EXISTS p7v2_s WITH (FORCE);\nCREATE DATABASE p7v2_s TEMPLATE p7v2_base;')
        try:
            if s['base_sql']:
                psql('p7v2_s', 'BEGIN;\n' + s['base_sql'] + 'COMMIT;\n')
            base = observe('p7v2_s', s['base_ex'], flows=[])
            if s['now_sql']:
                psql('p7v2_s', 'BEGIN;\n' + s['now_sql'] + 'COMMIT;\n')
            now = observe('p7v2_s', s['now_ex'], ex_before=s['now_ex_before'],
                          hook=('BEGIN;\n' + s['now_hook'] + 'COMMIT;\n') if s['now_hook'] else None,
                          flows=s['flows'], query_errors=s['now_query_errors'])
            out = R.reconcile(s['reg'], base, now)
        finally:
            psql('postgres', 'DROP DATABASE IF EXISTS p7v2_s WITH (FORCE);')
        got = {a['code'] for a in out['anomalies']} | {h['code'] for h in out['holds']}
        miss = [c for c in s['codes'] if c not in got]
        bad = [c for c in s['forbid'] if c in got]
        ok = out['overall'] == s['expect'] and not miss and not bad
        fails += not ok
        k = out['account']['krw']
        legacy = abs(D(k['raw_residual'])) <= len(s['reg']['sessions'])  # 초안식 세션 수×1원(참고만)
        rows.append(f"{'PASS' if ok else 'FAIL'} | {s['name']} | {s['group']} | 기대 {s['expect']} / 결과 {out['overall']} | "
                    f"코드 {','.join(sorted(got)) or '-'} | 원시 {k['raw_residual']} 설명 {k['explained_rounding']} 조정 {k['adjusted_residual']} | "
                    f"초안 ±세션수원 {'통과' if legacy else '불통과'}" + (f" | 누락 {miss}" if miss else '') + (f" | 금지 {bad}" if bad else ''))
        with open(os.path.join(out_dir, s['name'] + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'scenario': {k2: v for k2, v in s.items() if k2 not in ('base_sql', 'now_sql', 'now_hook')},
                       'base_sql': s['base_sql'], 'now_sql': s['now_sql'], 'now_hook': s['now_hook'],
                       'base': base, 'now': now, 'result': out}, f, ensure_ascii=False, indent=1, default=str)
    print('\n'.join(rows))
    print(f'합계 {len(S)} 시나리오, 통과 {len(S) - fails}, 실패 {fails}')
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
