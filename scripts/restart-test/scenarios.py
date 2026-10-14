"""실제 프로세스 재시작 시나리오 (2026-10-13).

사용: python scenarios.py [S1 S2 ...]   (인자 없으면 전부)

각 시나리오는 LIVE 세션(10,000)에서 3,000 시장가 매수를 낸다. 거래소 대역의 기본 체결은
요청 3,000 중 2,940 사용·9.8개·수수료 1.47 → 미사용 58.53, 잔액 7,058.53.

판정은 제출 횟수만이 아니라 주문·잔액·포지션·예약·정리 대기 전부를 본다. 복구가 끝난 뒤 앱을 한 번 더
재기동해 값이 변하지 않는지 확인한다.
"""
import json
import sys
import time
import urllib.error

import rt

RESULTS = []


KIND = 'LIVE'


def seed_live_session(status='RUNNING'):
    if KIND == 'DYNAMIC':
        return int(rt.one(
            "INSERT INTO dynamic_session (strategy_type, timeframe, initial_capital, available_krw, total_asset_krw, "
            "status, scan_state, trading_mode, invest_ratio, stop_loss_pct, max_candidate_size, target_watch_size, "
            "min_atr_pct, max_spread_pct, watchlist_refresh_min, started_at) VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', "
            f"'H1', 10000, 10000, 10000, '{status}', 'POSITION_MONITORING', 'REAL', 0.3, 5, 30, 10, 0.5, 0.1, 60, now()) "
            "RETURNING id"))
    return int(rt.one(
        "INSERT INTO live_trading_session (strategy_type, coin_pair, timeframe, initial_capital, available_krw, "
        "total_asset_krw, status, session_type, started_at) VALUES ('COMPOSITE_MOMENTUM_ICHIMOKU_V2', 'KRW-DOGE', "
        f"'M5', 10000, 10000, 10000, '{status}', 'REAL', now()) RETURNING id"))


def buy(sid, expect_crash=False):
    try:
        r = rt.api('POST', f'/api/v1/restart-harness/buy?kind={KIND}&sessionId={sid}&krw=3000', timeout=15)
        rt.log(f'  buy → {r}')
        return r
    except Exception as e:
        if not expect_crash:
            raise
        rt.log(f'  buy 요청 중 연결 끊김(예상): {type(e).__name__}')
        return None


def state(sid):
    """세션 하나의 전체 상태 — 비교용 문자열."""
    orders = rt.psql(
        "SELECT id, side, state, coalesce(failure_code,''), (exchange_order_id IS NOT NULL), "
        "coalesce(filled_quantity::text,''), coalesce(applied_executed_funds::text,''), fill_accounting_pending, "
        "coalesce(reserved_krw::text,''), (cancel_requested_at IS NOT NULL), (cancel_confirmed_at IS NOT NULL), "
        "(sell_applied_at IS NOT NULL), coalesce(quantity::text,''), settlement_version "
        f"FROM \"order\" WHERE session_kind='{KIND}' AND session_id={sid} ORDER BY id")
    positions = rt.psql(
        "SELECT id, status, size::text, coalesce(invested_krw::text,''), coalesce(exit_reason,'') "
        f"FROM position WHERE session_kind='{KIND}' AND session_id={sid} ORDER BY id")
    table = 'dynamic_session' if KIND == 'DYNAMIC' else 'live_trading_session'
    session = rt.psql(f"SELECT status, available_krw::text FROM {table} WHERE id={sid}")
    cleanup = rt.psql(
        "SELECT position_id, reason, (done_at IS NOT NULL), attempts, coalesce(last_note,'') "
        f"FROM position_cleanup WHERE session_kind='{KIND}' AND session_id={sid} ORDER BY position_id")
    idents = [r[0] for r in rt.psql(
        f"SELECT client_order_id FROM \"order\" WHERE session_kind='{KIND}' AND session_id={sid} AND client_order_id IS NOT NULL")]
    ex = rt.exchange_orders()
    creates = [c for c in ex['creates'] if c['identifier'] in idents]
    ex_orders = [{k: o.get(k) for k in ('side', 'state', 'executed_volume', 'executed_funds')}
                 for o in ex['orders'] if o.get('identifier') in idents]
    return {'orders': orders, 'positions': positions, 'session': session, 'cleanup': cleanup,
            'exchange_creates': len(creates), 'exchange_orders': ex_orders}


def show(tag, st):
    rt.log(f'  [{tag}] session={st["session"]} creates={st["exchange_creates"]}')
    for o in st['orders']:
        rt.log(f'     order {o}')
    for p in st['positions']:
        rt.log(f'     pos   {p}')
    for c in st['cleanup']:
        rt.log(f'     clean {c}')
    for e in st['exchange_orders']:
        rt.log(f'     exch  {e}')


def stable_after_restart(sid, settle_seconds=45):
    """복구가 끝난 상태에서 앱을 강제 종료·재기동해도 값이 변하지 않는가."""
    before = state(sid)
    rt.kill_app()
    rt.start_app(label='verify')
    time.sleep(settle_seconds)
    after = state(sid)
    # 정리 행의 메모·시도 횟수는 작업이 돌 때마다 갱신될 수 있으므로 금액·상태만 비교한다
    def core(st):
        return {k: v for k, v in st.items() if k != 'cleanup'} | {
            'cleanup': [c[:3] for c in st['cleanup']]}
    ok = core(before) == core(after)
    if not ok:
        show('재기동 전', before)
        show('재기동 후', after)
    return ok, after


def this_order_uuid(sid, side):
    """이 세션의 가장 최근 주문 — 🔴 대역의 전체 목록에서 고르면 다른 세션(비상 정지가 낸 매도 등)을 집는다."""
    return rt.one(f"SELECT exchange_order_id FROM \"order\" WHERE session_kind='{KIND}' AND session_id={sid} "
                  f"AND side='{side}' AND exchange_order_id IS NOT NULL ORDER BY id DESC LIMIT 1")


def this_sell_uuid(sid):
    return this_order_uuid(sid, 'SELL')


def stop_session(sid):
    path = f'/api/v1/dynamic-sessions/{sid}/stop' if KIND == 'DYNAMIC' else f'/api/v1/trading/sessions/{sid}/stop'
    rt.api('POST', path, timeout=30)


def check(name, cond, detail=''):
    name = f'[{KIND}] {name}'
    RESULTS.append((name, bool(cond), detail))
    rt.log(f'  {"PASS" if cond else "FAIL"} {name} {detail}')


def avail(st):
    return st['session'][0][1]


# ── 시나리오 ────────────────────────────────────────────────────────────────

def s1():
    rt.log('S1 의도 커밋 직후 강제 종료')
    sid = seed_live_session()
    rt.start_app(crashpoint='AFTER_INTENT_COMMIT', label='s1-crash')
    buy(sid, expect_crash=True)
    code = rt.wait_app_exit(30)
    st = state(sid)
    show('종료 직후', st)
    check('S1 강제 종료됨', code is not None, f'exit={code}')
    check('S1 종료 시점: 의도만 커밋, 거래소 0회', st['orders'][0][2] == 'INTENT_READY' and st['exchange_creates'] == 0)
    rt.start_app(label='s1-recover')
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 90, 2, 'S1 포지션 닫힘')
    st = state(sid)
    show('복구 후', st)
    o, p = st['orders'][0], st['positions'][0]
    check('S1 철회: FAILED/WITHDRAWN_OWNER_LOST', o[2] == 'FAILED' and o[3] == 'WITHDRAWN_OWNER_LOST')
    check('S1 거래소 제출 0회', st['exchange_creates'] == 0)
    check('S1 예약 한 번 복원(10,000)·포지션 CLOSED', float(avail(st)) == 10000 and p[1] == 'CLOSED', avail(st))
    ok, _ = stable_after_restart(sid)
    check('S1 재기동해도 불변', ok)


def s2():
    rt.log('S2 claim 커밋 직후 강제 종료')
    sid = seed_live_session()
    rt.start_app(crashpoint='AFTER_CLAIM_COMMIT', label='s2-crash')
    buy(sid)
    code = rt.wait_app_exit(30)
    st = state(sid)
    show('종료 직후', st)
    check('S2 강제 종료됨', code is not None, f'exit={code}')
    check('S2 종료 시점: SUBMITTING, 거래소 0회', st['orders'][0][2] == 'SUBMITTING' and st['exchange_creates'] == 0)
    rt.start_app(label='s2-recover')
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'UNKNOWN', 60, 2, 'S2 UNKNOWN')
    time.sleep(20)
    st = state(sid)
    show('복구 후', st)
    o, p = st['orders'][0], st['positions'][0]
    check('S2 재제출 없음(거래소 0회)', st['exchange_creates'] == 0)
    check('S2 미접수로 단정하지 않음: UNKNOWN, 예약 유지(7,000), 포지션 OPEN',
          o[2] == 'UNKNOWN' and float(avail(st)) == 7000 and p[1] == 'OPEN', f'{o[2]} {avail(st)} {p[1]}')
    ok, _ = stable_after_restart(sid)
    check('S2 재기동해도 불변', ok)


def s3(external_kill=False):
    tag = 'S3b' if external_kill else 'S3'
    rt.log(f'{tag} 거래소 접수 후 응답 기록 전 종료 ({"외부 강제 종료" if external_kill else "강제 종료 지점"})')
    sid = seed_live_session()
    if external_kill:
        rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'done', 'hold_ms': 12000, 'once': True}})
        rt.start_app(label=f'{tag.lower()}-run')
        buy(sid)
        rt.wait_until(lambda: state(sid)['exchange_creates'] == 1, 20, 0.2, '접수')
        time.sleep(1)
        rt.kill_app()
        code = 'killed'
    else:
        rt.start_app(crashpoint='AFTER_EXCHANGE_ACCEPT', label=f'{tag.lower()}-crash')
        buy(sid)
        code = rt.wait_app_exit(30)
    st = state(sid)
    show('종료 직후', st)
    check(f'{tag} 종료됨', code is not None, f'exit={code}')
    check(f'{tag} 종료 시점: 거래소는 접수·체결, 로컬은 SUBMITTING(주문번호 없음)',
          st['exchange_creates'] == 1 and st['orders'][0][2] == 'SUBMITTING' and st['orders'][0][4] == 'f')
    rt.start_app(label=f'{tag.lower()}-recover')
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED', 90, 2, f'{tag} FILLED')
    time.sleep(10)
    st = state(sid)
    show('복구 후', st)
    o, p = st['orders'][0], st['positions'][0]
    check(f'{tag} 식별자로 찾아 한 번 반영: FILLED·버전 1·대기 없음', o[2] == 'FILLED' and o[13] == '1' and o[7] == 'f')
    check(f'{tag} 거래소 제출 1회(재제출 없음)', st['exchange_creates'] == 1)
    check(f'{tag} 잔액 7,058.53·수량 9.8', float(avail(st)) == 7058.53 and float(p[2]) == 9.8, f'{avail(st)} {p[2]}')
    ok, _ = stable_after_restart(sid)
    check(f'{tag} 재기동해도 불변', ok)


def s45(point):
    tag = 'S4' if point == 'AFTER_FILL_OBSERVED' else 'S5'
    rt.log(f'{tag} 체결 회계 {"관측 커밋 직후" if tag == "S4" else "트랜잭션 안(커밋 전)"} 강제 종료')
    sid = seed_live_session()
    rt.start_app(crashpoint=point, label=f'{tag.lower()}-crash')
    buy(sid)
    code = rt.wait_app_exit(60)
    st = state(sid)
    show('종료 직후', st)
    o = st['orders'][0]
    check(f'{tag} 강제 종료됨', code is not None, f'exit={code}')
    check(f'{tag} 종료 시점: 관측·대기만 남고 회계 미반영(잔액 7,000)',
          o[7] == 't' and o[6] == '' and float(avail(st)) == 7000, f'pending={o[7]} applied={o[6]} {avail(st)}')
    rt.start_app(label=f'{tag.lower()}-recover')
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED', 90, 2, f'{tag} FILLED')
    time.sleep(10)
    st = state(sid)
    show('복구 후', st)
    o, p = st['orders'][0], st['positions'][0]
    check(f'{tag} 재처리로 한 번 반영: 7,058.53·9.8·버전 1·대기 없음',
          float(avail(st)) == 7058.53 and float(p[2]) == 9.8 and o[13] == '1' and o[7] == 'f', f'{avail(st)} {p[2]} v{o[13]}')
    check(f'{tag} 거래소 제출 1회', st['exchange_creates'] == 1)
    ok, _ = stable_after_restart(sid)
    check(f'{tag} 재기동해도 불변', ok)


def s6():
    rt.log('S6 정지 → 늦은 체결 → 정리 매도 부분 체결 중 강제 종료')
    sid = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}, 'ask': {'fill': 'partial_wait', 'once': True}})
    rt.start_app(label='s6-run')
    r = buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'SUBMITTED', 30, 1, 'SUBMITTED')
    # 정지 직전에 거래소에서 체결된다 — 정지의 취소 전 조회가 체결을 보고, 반영은 정지 후 폴러가 한다
    ex = rt.exchange_orders()
    buy_uuid = this_order_uuid(sid, 'BUY')
    rt.ctl('POST', '/_ctl/fill', {'uuid': buy_uuid, 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    stop_session(sid)
    rt.wait_until(lambda: any(o[1] == 'SELL' for o in state(sid)['orders']), 60, 1, '정리 매도')
    rt.wait_until(lambda: any(o[1] == 'SELL' and o[4] == 't' for o in state(sid)['orders']), 30, 1, '매도 접수')
    st = state(sid)
    show('정리 매도 접수(거래소 절반 체결·대기)', st)
    check('S6 늦은 체결 반영: 7,058.53·9.8', float(avail(st)) == 7058.53)
    rt.kill_app()
    rt.log('  앱 강제 종료 — 거래소에는 4.9 팔림·주문 대기, 로컬은 CLOSING 9.8')
    rt.start_app(label='s6-recover')
    time.sleep(25)
    # CLOSING 시간 초과(8분)를 앞당겨 OPEN 롤백을 일으킨다 — 그래도 다른 경로가 다시 팔면 안 된다
    pid = st['positions'][0][0]
    rt.psql(f"UPDATE position SET closing_at = now() - interval '9 minutes' WHERE id={pid} AND status='CLOSING'")
    time.sleep(20)
    # 다른 경로도 부른다 — 전체 비상 정지(실행 중 세션의 포지션을 시장가로 판다)
    rt.api('POST', '/api/v1/trading/emergency-stop', timeout=60)
    time.sleep(25)
    st = state(sid)
    show('재기동 + CLOSING 시간 초과 후', st)
    ask_creates = sum(1 for e in st['exchange_orders'] if e['side'] == 'ask')
    check('S6 진행 중 매도가 있는 동안 재매도 0(거래소 매도 1건)', ask_creates == 1, f'ask={ask_creates}')
    check('S6 정리 미완료 유지', st['cleanup'] and st['cleanup'][0][2] == 'f')
    # 거래소가 매도를 4.9 에서 잔량 취소로 끝낸다 → 남은 4.9 만 다시 판다
    sell_uuid = this_sell_uuid(sid)
    rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'done', 'once': True}})
    rt.ctl('POST', '/_ctl/fill', {'uuid': sell_uuid, 'state': 'cancel'})
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 120, 2, 'S6 CLOSED')
    rt.wait_until(lambda: state(sid)['cleanup'][0][2] == 't', 30, 2, 'S6 정리 완료')
    st = state(sid)
    show('청산 완료', st)
    asks = [e for e in st['exchange_orders'] if e['side'] == 'ask']
    sold = sum(float(e['executed_volume'] or 0) for e in asks)
    check('S6 거래소 매도 2건·합계 9.8(초과 매도 없음)', len(asks) == 2 and abs(sold - 9.8) < 1e-9, f'{len(asks)} {sold}')
    check('S6 포지션 CLOSED·정리 완료', st['positions'][0][1] == 'CLOSED' and st['cleanup'][0][2] == 't')
    ok, _ = stable_after_restart(sid)
    check('S6 재기동해도 불변', ok)


def s7():
    rt.log('S7 정리 매도가 앱 종료 중 거래소에서 전량 체결 — 재기동 후 한 번만 정산·청산')
    sid = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}, 'ask': {'fill': 'wait', 'once': True}})
    rt.start_app(label='s7-run')
    buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'SUBMITTED', 30, 1, 'SUBMITTED')
    ex = rt.exchange_orders()
    buy_uuid = this_order_uuid(sid, 'BUY')
    rt.ctl('POST', '/_ctl/fill', {'uuid': buy_uuid, 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    stop_session(sid)
    rt.wait_until(lambda: any(o[1] == 'SELL' and o[4] == 't' for o in state(sid)['orders']), 60, 1, '매도 접수')
    rt.kill_app()
    sell_uuid = this_sell_uuid(sid)
    rt.ctl('POST', '/_ctl/fill', {'uuid': sell_uuid, 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    rt.log('  앱 종료 중 거래소에서 매도 전량 체결 — 거래소 보유 0, 로컬 CLOSING 9.8')
    rt.start_app(label='s7-recover')
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 90, 2, 'S7 CLOSED')
    rt.api('POST', '/api/v1/trading/emergency-stop', timeout=60)
    time.sleep(70)     # 계좌 대조(60초)·정리 작업·청산 확정이 여러 번 돈다
    st = state(sid)
    show('복구 후', st)
    asks = [e for e in st['exchange_orders'] if e['side'] == 'ask']
    # 대역의 매도 수수료는 0.04% — 7,058.53 + (2,940 − 1.176) = 9,997.354 → 9,997.35 (추정 0.05% 였다면 9,997.06)
    check('S7 거래소 매도 1건(재매도 없음)', len(asks) == 1, f'{len(asks)}')
    check('S7 대금 한 번(거래소 보고 수수료 1.176): 9,997.35·포지션 CLOSED·정리 완료',
          abs(float(avail(st)) - 9997.35) < 0.006 and st['positions'][0][1] == 'CLOSED' and st['cleanup'][0][2] == 't',
          f'{avail(st)} {st["positions"][0][1]}')
    ok, _ = stable_after_restart(sid, settle_seconds=70)
    check('S7 재기동해도 불변', ok)


def s8():
    rt.log('S8 거래소 명시적 거절(잔고 부족) — 식별자로 없음 확인 후 FAILED/EXCHANGE_REJECTED, 예약 한 번 복원')
    sid = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'reject': {'status': 400, 'name': 'insufficient_funds_bid'}, 'once': True}})
    rt.start_app(label='s8-run')
    buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FAILED', 30, 1, 'S8 FAILED')
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 90, 2, 'S8 복원')
    st = state(sid)
    show('거절 후', st)
    o = st['orders'][0]
    check('S8 FAILED/EXCHANGE_REJECTED', o[2] == 'FAILED' and o[3] == 'EXCHANGE_REJECTED', f'{o[2]} {o[3]}')
    check('S8 거래소에 주문 없음', st['exchange_creates'] == 0)
    check('S8 예약 한 번 복원(10,000)·포지션 CLOSED', float(avail(st)) == 10000 and st['positions'][0][1] == 'CLOSED',
          avail(st))
    ok, _ = stable_after_restart(sid)
    check('S8 재기동해도 불변', ok)


def s9():
    rt.log('S9 거래소가 주문을 만들고 오류(create_bid_error)로 응답 — 거절로 단정하지 않고 식별자로 찾아 한 번 반영')
    sid = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'done', 'error_after_create': {'status': 400, 'name': 'create_bid_error'},
                                          'once': True}})
    rt.start_app(label='s9-run')
    buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED', 90, 2, 'S9 FILLED')
    time.sleep(10)
    st = state(sid)
    show('복구 후', st)
    o, p = st['orders'][0], st['positions'][0]
    check('S9 FILLED·버전 1(거절로 닫지 않음)', o[2] == 'FILLED' and o[13] == '1' and o[3] == '', f'{o[2]} {o[3]} v{o[13]}')
    check('S9 거래소 1회·재제출 없음', st['exchange_creates'] == 1)
    check('S9 잔액 7,058.53·수량 9.8', float(avail(st)) == 7058.53 and float(p[2]) == 9.8, f'{avail(st)} {p[2]}')
    ok, _ = stable_after_restart(sid)
    check('S9 재기동해도 불변', ok)


SCEN = {'S1': s1, 'S2': s2, 'S3': lambda: s3(False), 'S3b': lambda: s3(True),
        'S4': lambda: s45('AFTER_FILL_OBSERVED'), 'S5': lambda: s45('IN_FILL_ACCOUNTING'), 'S6': s6, 'S7': s7, 'S8': s8, 'S9': s9}

if __name__ == '__main__':
    args = sys.argv[1:]
    fresh = '--fresh' in args
    names = [a for a in args if not a.startswith('--')] or list(SCEN)
    kinds = ['LIVE', 'DYNAMIC'] if '--both' in args else (['DYNAMIC'] if '--dynamic' in args else ['LIVE'])
    if fresh:
        rt.log('DB 컨테이너를 새로 만든다')
        rt.fresh_db()
    rt.start_double(fresh=fresh)
    if fresh:
        rt.start_app(label='migrate')     # Flyway V1~V87
        rt.log('  flyway: ' + str(rt.psql("select max(version::int), count(*), bool_and(success) from flyway_schema_history where version is not null")))
        rt.kill_app()
    try:
        for kind in kinds:
            KIND = kind
            globals()['KIND'] = kind
            for n in names:
                if kind == 'DYNAMIC' and n not in ('S3', 'S3b', 'S6', 'S7', 'S8', 'S9'):
                    continue   # DYNAMIC 은 접수 후 종료·부분 청산 중 종료·종료 중 체결만 (의도·claim·회계 지점은 공통 엔진)
                try:
                    SCEN[n]()
                except Exception as e:
                    check(f'{n} 실행 오류', False, repr(e))
                finally:
                    rt.kill_app()
    finally:
        rt.stop_double()
    rt.log('==== 요약 ====')
    for name, ok, detail in RESULTS:
        rt.log(f'{"PASS" if ok else "FAIL"} {name} {detail}')
    rt.log(f'{sum(1 for r in RESULTS if r[1])}/{len(RESULTS)} 통과')
