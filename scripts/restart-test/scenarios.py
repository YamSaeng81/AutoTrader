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


# ── 단일 인스턴스·잠금 순서·설정 (2026-10-14) ──────────────────────────────────

OTHER_PORT = 18082
LOCK_KEY = 0x43415431


def lock_holders():
    """단일 인스턴스 advisory lock 을 쥔 백엔드 pid 목록."""
    return [r[0] for r in rt.psql(
        "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted "
        f"AND ((classid::bigint << 32) | objid::bigint) = {LOCK_KEY}")]


def s10():
    rt.log('S10 두 번째 인스턴스는 기동을 거부한다 — 먼저 뜬 쪽이 죽으면 그때 뜬다')
    rt.start_app(label='s10-A')
    check('S10 A 가 잠금을 쥐었다(보유자 1)', len(lock_holders()) == 1, str(lock_holders()))
    creates_before = len(rt.exchange_orders()['creates'])
    b, rc = rt.start_other(OTHER_PORT, label='s10-B')
    blog = rt.app_log(b)
    check('S10 B 기동 거부(비정상 종료)', rc is not None and rc != 0, f'rc={rc}')
    check('S10 거부 사유가 단일 인스턴스 잠금', '다른 인스턴스가 이미 실행 중' in blog)
    check('S10 B 는 기동 완료에 닿지 않았다(복구·폴러·API 미기동)',
          'Started CryptoAutoTraderApplication' not in blog and 'Tomcat started' not in blog)
    check('S10 B 가 거래소를 부르지 않았다', len(rt.exchange_orders()['creates']) == creates_before)
    check('S10 A 는 계속 살아 있다', rt.app_alive() and rt.api('GET', '/actuator/health')['status'] == 'UP')
    # A 가 매수를 끝까지 처리한다 — 실행 주체가 하나
    sid = seed_live_session()
    buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED', 60, 1, 'S10 FILLED')
    st = state(sid)
    check('S10 A 단독으로 매수 1회 반영(7,058.53)', st['exchange_creates'] == 1 and float(avail(st)) == 7058.53,
          f'{st["exchange_creates"]} {avail(st)}')
    rt.kill_app()
    rt.wait_until(lambda: len(lock_holders()) == 0, 30, 0.5, 'A 잠금 해제')
    check('S10 A 강제 종료 → 잠금 해제', len(lock_holders()) == 0)
    b2, rc2 = rt.start_other(OTHER_PORT, label='s10-B2')
    check('S10 A 가 죽은 뒤 B 는 뜬다', rc2 is None, f'rc={rc2}')
    rc3 = rt.start_app(label='s10-A2')
    check('S10 이제는 A 가 거부된다', rc3 is not None and rc3 != 0
          and '다른 인스턴스가 이미 실행 중' in rt.app_log(), f'rc={rc3}')
    check('S10 보유자는 여전히 하나', len(lock_holders()) == 1)
    st2 = state(sid)
    check('S10 인스턴스 교대 후 값 불변', st2['exchange_creates'] == 1 and avail(st2) == avail(st))
    rt.kill_all()


def s11():
    rt.log('S11 잠금 연결을 잃으면(관리자 종료) 즉시 멈추고, 그 뒤 다른 인스턴스가 뜬다')
    rt.start_app(label='s11-A')
    holders = lock_holders()
    check('S11 보유자 1', len(holders) == 1, str(holders))
    rt.psql(f"SELECT pg_terminate_backend({holders[0]})")
    t0 = time.time()
    code = rt.wait_app_exit(30)
    took = time.time() - t0
    check('S11 A 가 스스로 멈췄다(종료 코드 3)', code == 3, f'exit={code} {took:.1f}s')
    check('S11 감시 주기(5초) 안팎에 멈췄다', took <= 12, f'{took:.1f}s')
    check('S11 멈춘 이유가 기록됐다', 'SINGLE_INSTANCE_LOCK_LOST' in rt.app_log())
    b, rc = rt.start_other(OTHER_PORT, label='s11-B')
    check('S11 B 기동', rc is None, f'rc={rc}')
    rt.kill_all()


def hold(lock_sql, seconds, label):
    """다른 연결이 행 잠금을 seconds 동안 쥔다."""
    p = rt.psql_background(f"BEGIN; {lock_sql}; SELECT pg_sleep({seconds}); COMMIT;", label)
    time.sleep(1.5)    # 잠금이 잡힐 시간
    return p


def timed_buy(sid):
    t0 = time.time()
    try:
        r = rt.api('POST', f'/api/v1/restart-harness/buy?kind={KIND}&sessionId={sid}&krw=3000', timeout=40)
        return ('ok', r, time.time() - t0)
    except urllib.error.HTTPError as e:
        return ('http', e.code, time.time() - t0)
    except Exception as e:
        return ('err', repr(e), time.time() - t0)


def session_table():
    return 'dynamic_session' if KIND == 'DYNAMIC' else 'live_trading_session'


def s12():
    rt.log('S12 잠금 경합 — 외부 연결이 계좌·세션 행을 쥔 동안 매수·체결 반영·정지, 제한 시간 뒤 회복')
    dl0 = rt.deadlocks()
    # 앞 시나리오가 남긴 열린 포지션이 슬롯 상한(10)을 채우지 않게 — S12 는 잠금만 본다(슬롯 거절은 S13·CapitalPoolLimitTest 몫)
    rt.start_app(label='s12-run', props={'trading.capital-pool.max-concurrent-positions': '1000'})
    table = session_table()

    # ① 계좌·세션 잠금을 쥔 동안 매수 → 10초 뒤 실패, 흔적 없음 → 풀린 뒤 정상
    for what in ('계좌', '세션'):
        sid = seed_live_session()
        sql = ('SELECT id FROM account_capital_lock WHERE id = 1 FOR UPDATE' if what == '계좌'
               else f'SELECT id FROM {table} WHERE id = {sid} FOR UPDATE')
        h = hold(sql, 15, f's12-{what}')
        import threading
        res = {}
        th = threading.Thread(target=lambda: res.update(r=timed_buy(sid)))
        th.start()
        time.sleep(4)
        during = pg_activity()
        guard_held_during = len(lock_holders()) == 1
        th.join()
        kind_, detail, took = res['r']
        after_timeout = pg_activity()
        idle_detail = idle_in_tx_detail()
        time.sleep(2.5)
        idle_detail_later = idle_in_tx_detail()
        h.wait()
        rt.log(f'  잠금 대기 중 연결: {during}')
        rt.log(f'  제한 시간 직후 연결: {after_timeout}')
        check(f'S12 {what} 대기 중: 업무용 풀 연결이 잠금 대기(Lock), 단일 인스턴스 연결은 대기 아님·잠금 유지',
              count_act(during, 'pool', wait='Lock') >= 1 and count_act(during, 'guard', wait='Lock') == 0
              and count_act(during, 'guard') == 1 and guard_held_during, str(during))
        rt.log(f'  제한 시간 직후 트랜잭션 중 유휴: {idle_detail} → 2.5초 뒤: {idle_detail_later}')
        # 2026-10-15: 순간 측정 한 번은 다른 작업(DYNAMIC 틱 트랜잭션 등)이 SQL 사이에 잠깐 유휴인 것과 오래 열린 트랜잭션을
        # 구분하지 못한다(208/209 회차에서 2건 관측). 오래 열린 트랜잭션은 유휴가 계속되므로 "2초 넘게 유휴인 트랜잭션 0" 을
        # 2.5초 간격 두 번 모두에서 요구한다. 잠금 대기 0 은 그대로.
        # 🔴 판정 범위 (2026-10-15 리뷰): 이것은 장기 미종료 트랜잭션의 관측 기준이다. 트랜잭션 없이 대여된 채 남은 연결이나
        #    실행 중 정체는 잡지 못한다 — 연결 반환 자체의 입증으로 쓰지 않는다.
        check(f'S12 {what} 제한 시간 뒤: 롤백 — 장기 미종료 트랜잭션 없음(2초 넘는 idle in transaction 0·잠금 대기 0)',
              not stale_idle_in_tx(idle_detail) and not stale_idle_in_tx(idle_detail_later)
              and count_act(after_timeout, 'pool', wait='Lock') == 0,
              f'{after_timeout} 유휴상세={idle_detail} / {idle_detail_later}')
        st = state(sid)
        check(f'S12 {what} 잠금 중 매수 — 제한 시간(10초) 뒤 실패', kind_ != 'ok' and 8 <= took <= 14,
              f'{kind_} {detail} {took:.1f}s')
        check(f'S12 {what} 잠금 중 매수 — 주문·포지션·예약 흔적 없음(10,000)',
              not st['orders'] and not st['positions'] and float(avail(st)) == 10000 and st['exchange_creates'] == 0,
              f'orders={len(st["orders"])} pos={len(st["positions"])} {avail(st)}')
        buy(sid)
        rt.wait_until(lambda: state(sid)['orders'] and state(sid)['orders'][0][2] == 'FILLED', 60, 1, f'S12 {what} 회복')
        st = state(sid)
        check(f'S12 {what} 잠금 해제 뒤 매수 정상(1회·7,058.53)',
              st['exchange_creates'] == 1 and float(avail(st)) == 7058.53, f'{st["exchange_creates"]} {avail(st)}')

    # ② 체결 반영이 세션 잠금에 막힌다 → 풀린 뒤 한 번만 반영
    sid = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}})
    buy(sid)
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'SUBMITTED', 30, 1, 'S12 SUBMITTED')
    h = hold(f'SELECT id FROM {table} WHERE id = {sid} FOR UPDATE', 25, 's12-fill')
    rt.ctl('POST', '/_ctl/fill', {'uuid': this_order_uuid(sid, 'BUY'), 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    time.sleep(15)
    mid = state(sid)
    check('S12 세션 잠금 중에는 체결이 반영되지 않는다(7,000)', float(avail(mid)) == 7000, avail(mid))
    h.wait()
    rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED' and state(sid)['orders'][0][7] == 'f', 90, 2,
                  'S12 반영')
    time.sleep(10)
    st = state(sid)
    o, p = st['orders'][0], st['positions'][0]
    check('S12 잠금 해제 뒤 한 번만 반영: 7,058.53·9.8·버전 1·대기 없음',
          float(avail(st)) == 7058.53 and float(p[2]) == 9.8 and o[13] == '1' and o[7] == 'f',
          f'{avail(st)} {p[2]} v{o[13]} pending={o[7]}')

    # ③ 정지가 세션 잠금에 막힌다 → 이중 매도 없음 → 필요하면 재시도로 정지
    rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'done', 'once': True}})
    h = hold(f'SELECT id FROM {table} WHERE id = {sid} FOR UPDATE', 15, 's12-stop')
    t0 = time.time()
    try:
        stop_session(sid)
        first = 'ok'
    except Exception as e:
        first = repr(e)[:120]
    took = time.time() - t0
    h.wait()
    rt.log(f'  첫 정지: {first} ({took:.1f}s)')
    time.sleep(20)
    mid = state(sid)
    show('첫 정지 뒤', mid)
    asks = [e for e in mid['exchange_orders'] if e['side'] == 'ask']
    check('S12 잠금 중 정지 — 무한 대기 없이 끝난다(≤ 40초)', took <= 40, f'{took:.1f}s {first}')
    check('S12 잠금 중 정지 — 거래소 매도 ≤ 1', len(asks) <= 1, f'ask={len(asks)}')
    if mid['session'][0][0] != 'STOPPED':
        try:
            stop_session(sid)
        except Exception as e:
            rt.log(f'  재시도 정지 오류: {e!r}')
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 120, 2, 'S12 CLOSED')
    rt.wait_until(lambda: abs(float(avail(state(sid))) - 9997.35) < 0.006, 30, 2, 'S12 대금')
    time.sleep(5)
    st = state(sid)
    show('정지 완료', st)
    asks = [e for e in st['exchange_orders'] if e['side'] == 'ask']
    check('S12 정지 완료: STOPPED·CLOSED·매도 1건·대금 한 번(9,997.35)',
          st['session'][0][0] == 'STOPPED' and st['positions'][0][1] == 'CLOSED' and len(asks) == 1
          and abs(float(avail(st)) - 9997.35) < 0.006, f'{st["session"]} ask={len(asks)}')

    # ④ 잠금 없이 실제 경로끼리 동시에 — 매수(계좌→세션) · 시작(계좌→세션) · 정지
    rt.psql(f"UPDATE {table} SET status = 'STOPPED' WHERE status = 'RUNNING'")   # 동시 실행 세션 수 상한 회피(시험 DB)
    import threading
    for rnd in range(3):
        a = seed_live_session()
        b_ = seed_live_session(status='STOPPED')
        c = seed_live_session()
        rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'done'}})
        buy(c)
        rt.wait_until(lambda: state(c)['orders'][0][2] == 'FILLED', 60, 1, 'c FILLED')
        out = {}

        def run(k, f):
            try:
                f()
                out[k] = 'ok'
            except Exception as e:
                out[k] = repr(e)[:100]
        start_path = (f'/api/v1/dynamic-sessions/{b_}/start' if KIND == 'DYNAMIC'
                      else f'/api/v1/trading/sessions/{b_}/start')
        ths = [threading.Thread(target=run, args=('buy', lambda: buy(a))),
               threading.Thread(target=run, args=('start', lambda: rt.api('POST', start_path, timeout=40))),
               threading.Thread(target=run, args=('stop', lambda: stop_session(c)))]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        rt.wait_until(lambda: state(c)['positions'][0][1] == 'CLOSED'
                      and state(a)['orders'] and state(a)['orders'][0][2] == 'FILLED', 90, 2, 'S12④ 정리')
        sa, sb, sc = state(a), state(b_), state(c)
        check(f'S12④ 회차 {rnd + 1}: 세 호출 모두 성공', all(v == 'ok' for v in out.values()), str(out))
        rt.wait_until(lambda: abs(float(avail(state(c))) - 9997.35) < 0.006, 30, 2, 'S12④ 대금')
        sc = state(c)
        check(f'S12④ 회차 {rnd + 1}: 매수 1회 반영·시작 RUNNING·정지 STOPPED/CLOSED·대금 9,997.35(보고 수수료)',
              float(avail(sa)) == 7058.53 and sb['session'][0][0] == 'RUNNING'
              and sc['session'][0][0] == 'STOPPED' and sc['positions'][0][1] == 'CLOSED'
              and abs(float(avail(sc)) - 9997.35) < 0.006,
              f'{avail(sa)} {sb["session"]} {sc["session"]} {sc["positions"]}')
        rt.psql(f"UPDATE {table} SET status = 'STOPPED' WHERE id IN ({a}, {b_})")
    rt.ctl('POST', '/_ctl/plan', {'ask': None})
    dl1 = rt.deadlocks()
    check('S12 교착 0(pg_stat_database.deadlocks 증가 없음)', dl1 == dl0, f'{dl0}→{dl1}')
    check('S12 앱 로그에 교착 없음', 'deadlock detected' not in rt.app_log().lower())
    final = pg_activity()
    rt.log(f'  종료 시 연결: {final}')
    check('S12 끝: 트랜잭션 중 유휴 0·잠금 대기 0·단일 인스턴스 잠금 유지',
          count_act(final, 'pool', state='idle in transaction') == 0 and count_act(final, 'pool', wait='Lock') == 0
          and len(lock_holders()) == 1, str(final))


POOL_KEYS = ['trading.capital-pool.stage', 'trading.capital-pool.total-cap-krw',
             'trading.capital-pool.per-position-cap-krw', 'trading.capital-pool.max-concurrent-positions']


def stage_props(stage, total, per, slots):
    return dict(zip(POOL_KEYS, [stage, total, per, slots]))


def s13():
    rt.log('S13 설정 누락·등록값 불일치·risk_config 없음 → 실자금 진입 차단, 흔적 없음')
    cases = [
        ('풀 설정 전부 누락', {k: None for k in POOL_KEYS}, '한도 설정 누락'),
        ('총상한 하나만 누락', {'trading.capital-pool.total-cap-krw': None}, '한도 설정 누락'),
        ('STAGE_1 이름에 다른 값(동시 4)', stage_props('STAGE_1', '100000', '20000', '4'), '등록값'),
        ('STAGE_2 이름에 1단계 값', stage_props('STAGE_2', '100000', '20000', '3'), '등록값'),
    ]
    for i, (name, props, reason) in enumerate(cases):
        rt.start_app(label=f's13-{i}', props=props)
        log = rt.app_log()
        check(f'S13 {name}: 기동 로그에 차단 경보', '공유 풀 한도가 사전 등록과 맞지 않는다' in log and reason in log)
        sid = seed_live_session()
        kind_, detail, _ = timed_buy(sid)
        st = state(sid)
        check(f'S13 {name}: 매수 거절·흔적 없음', kind_ != 'ok' and not st['orders'] and not st['positions']
              and float(avail(st)) == 10000 and st['exchange_creates'] == 0, f'{kind_} {detail}')
        check(f'S13 {name}: 거절 사유 POOL_NOT_REGISTERED', 'POOL_NOT_REGISTERED' in rt.app_log())
        rt.kill_app()

    # 대조군: STAGE_1 정확한 값이면 승인 — 같은 조건의 거절이 설정 때문임을 보인다
    rt.psql("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
    rt.psql("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
    # 🔴 2026-10-14(선행 조건 1번): STAGE_1 은 risk_config 도 등록값(24시간 3.0·누적 15.0)이어야 승인된다. 대조군은 둘 다 맞춘다.
    rc_saved = rt.psql("SELECT id, max_daily_loss_pct::text, max_portfolio_drawdown_pct::text FROM risk_config ORDER BY id DESC LIMIT 1")[0]
    # 앞 시나리오가 남긴 열린 포지션이 1단계 슬롯 3 을 채우지 않게 닫는다(기본 순서에서 S1~S12 가 먼저 돈다 — 시험 준비).
    rt.psql("UPDATE position SET status = 'CLOSED' WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC')")
    rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0 WHERE id = {rc_saved[0]}")
    try:
        rt.start_app(label='s13-stage1-ok', props=stage_props('STAGE_1', '100000', '20000', '3'))
        check('S13 대조군: 기동 로그에 차단 경보 없음', '공유 풀 한도가 사전 등록과 맞지 않는다' not in rt.app_log())
        sid = seed_live_session()
        buy(sid)
        rt.wait_until(lambda: state(sid)['orders'] and state(sid)['orders'][0][2] == 'FILLED', 60, 1, 'S13 대조군')
        st = state(sid)
        check('S13 대조군 STAGE_1 정확한 값: 매수 승인·1회 반영', st['exchange_creates'] == 1 and float(avail(st)) == 7058.53,
              f'{st["exchange_creates"]} {avail(st)}')
        rt.kill_app()
    finally:
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = {rc_saved[1]}, max_portfolio_drawdown_pct = {rc_saved[2]} "
                f"WHERE id = {rc_saved[0]}")

    # risk_config 행 없음 → 신규 매수 차단 (행은 시험 뒤 그대로 되돌린다)
    saved = rt.one("SELECT json_agg(r) FROM risk_config r")
    rt.psql("DELETE FROM risk_config")
    try:
        rt.start_app(label='s13-no-risk')
        sid = seed_live_session()
        kind_, detail, _ = timed_buy(sid)
        st = state(sid)
        check('S13 risk_config 없음: 매수 거절·흔적 없음', kind_ != 'ok' and not st['orders'] and not st['positions']
              and float(avail(st)) == 10000 and st['exchange_creates'] == 0, f'{kind_} {detail}')
        # 2026-10-14: 행이 없으면 예약 지점의 손실 한도 대조(RISK_CONFIG_MISMATCH)가 먼저 막는다 — 종전 사유(제출 승인 지점)도 받는다.
        log = rt.app_log()
        check('S13 risk_config 없음: 사유 기록', 'risk_config 행이 없어 신규 매수를 승인하지 않는다' in log
              or ('RISK_CONFIG_MISMATCH' in log and 'risk_config 행이 없다' in log))
        check('S13 risk_config 없음: 읽기 경로가 행을 만들지 않았다', rt.one("SELECT count(*) FROM risk_config") == '0')
        rt.kill_app()
    finally:
        rt.psql("INSERT INTO risk_config SELECT * FROM json_populate_recordset(NULL::risk_config, '"
                + saved.replace("'", "''") + "'::json)")
    check('S13 risk_config 복원', rt.one("SELECT count(*) FROM risk_config") == '1')


# ── 후속 판정 (2026-10-14 검토 반영) ─────────────────────────────────────────

def app_requests():
    """거래소 대역이 받은 앱 요청(/v1) 수 — 시험 제어(/_ctl)는 세지 않는다."""
    import os
    with open(os.path.join(rt.WORK, 'double.log'), encoding='utf-8', errors='replace') as f:
        return sum(1 for line in f if '"GET /v1' in line or '"POST /v1' in line or '"DELETE /v1' in line)


def pg_activity():
    """업무용 풀 연결과 단일 인스턴스 잠금 연결을 따로 센다."""
    rows = rt.psql(
        "SELECT CASE WHEN application_name = 'cat-single-instance' THEN 'guard' ELSE 'pool' END, "
        "coalesce(state, ''), coalesce(wait_event_type, ''), count(*) FROM pg_stat_activity "
        "WHERE datname = 'crypto_auto_trader' AND pid <> pg_backend_pid() AND application_name <> 'psql' "
        "GROUP BY 1, 2, 3 ORDER BY 1, 2, 3")
    return [tuple(r) for r in rows]


def idle_in_tx_detail():
    """트랜잭션 중 유휴 연결의 내용 — (pid, 유휴 지속 초, 트랜잭션 나이 초, 마지막 쿼리 앞부분)."""
    rows = rt.psql(
        "SELECT pid, round(extract(epoch FROM now() - state_change)::numeric, 2), "
        "round(extract(epoch FROM now() - xact_start)::numeric, 2), left(regexp_replace(query, '\\s+', ' ', 'g'), 90) "
        "FROM pg_stat_activity WHERE datname = 'crypto_auto_trader' AND state = 'idle in transaction' "
        "AND application_name <> 'cat-single-instance' AND application_name <> 'psql'")
    return [tuple(r) for r in rows]


def stale_idle_in_tx(detail, seconds=2.0):
    """장기 미종료 트랜잭션 — 트랜잭션 중 유휴가 seconds 넘게 이어진 것. 다른 작업이 SQL 사이에서 잠깐 유휴인 것은 세지 않는다.
    트랜잭션 없이 대여된 채 남은 연결이나 실행 중 정체는 이 기준으로 잡히지 않는다."""
    return [d for d in detail if float(d[1]) > seconds]


def count_act(act, who, state=None, wait=None):
    return sum(int(r[3]) for r in act if r[0] == who and (state is None or r[1] == state)
               and (wait is None or r[2] == wait))


def s10b():
    rt.log('S10b 잠금을 다른 연결이 쥔 동안 뜬 인스턴스 — 주문·복구·정리 실행 0건, 풀린 뒤 복구')
    # 복구할 일거리 둘: ① 의도만 커밋된 주문(소유자 상실) ② 접수된 뒤 앱이 죽은 사이 체결된 주문
    #    🔴 순서: 접수 주문을 먼저 만들고(대기), 의도는 마지막 앱이 만든다 — 앞 앱의 기동 복구가 뒤 일거리를 치우지 않게.
    s_fill = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}})
    rt.start_app(label='s10b-submit')
    buy(s_fill)
    rt.wait_until(lambda: state(s_fill)['orders'][0][2] == 'SUBMITTED', 30, 1, 'SUBMITTED')
    rt.kill_app()
    s_intent = seed_live_session()
    rt.start_app(crashpoint='AFTER_INTENT_COMMIT', label='s10b-intent')
    buy(s_intent, expect_crash=True)
    rt.wait_app_exit(30)
    rt.ctl('POST', '/_ctl/fill', {'uuid': this_order_uuid(s_fill, 'BUY'), 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    before_i, before_f = state(s_intent), state(s_fill)
    check('S10b 준비: 의도 INTENT_READY · 접수 주문 SUBMITTED(거래소는 체결)',
          before_i['orders'][0][2] == 'INTENT_READY' and before_f['orders'][0][2] == 'SUBMITTED')

    holder = rt.psql_background(f"SELECT pg_advisory_lock({LOCK_KEY}); SELECT pg_sleep(90);", 's10b-holder')
    time.sleep(2)
    req0 = app_requests()
    b, rc = rt.start_other(OTHER_PORT, label='s10b-B')
    time.sleep(3)
    blog = rt.app_log(b)
    check('S10b 기동 거부', rc is not None and rc != 0 and '다른 인스턴스가 이미 실행 중' in blog, f'rc={rc}')
    check('S10b 거부된 인스턴스의 거래소 호출 0건(조회 포함)', app_requests() == req0, f'{req0}→{app_requests()}')
    after_i, after_f = state(s_intent), state(s_fill)
    check('S10b 주문·포지션·잔액·정리 대기 불변(복구·철회·반영 0건)',
          after_i == before_i and after_f == before_f)
    check('S10b 기동 완료·스케줄 작업 로그 없음',
          'Started CryptoAutoTraderApplication' not in blog and '고아' not in blog and '정리 작업' not in blog)
    holder.kill()
    rt.psql(f"SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' "
            f"AND ((classid::bigint << 32) | objid::bigint) = {LOCK_KEY}")
    rt.wait_until(lambda: len(lock_holders()) == 0, 20, 0.5, '외부 보유 해제')
    rt.start_app(label='s10b-recover')
    rt.wait_until(lambda: state(s_fill)['orders'][0][2] == 'FILLED'
                  and state(s_intent)['positions'][0][1] == 'CLOSED', 120, 2, 'S10b 복구')
    time.sleep(5)
    si, sf = state(s_intent), state(s_fill)
    check('S10b 풀린 뒤 복구: 의도 철회(WITHDRAWN_OWNER_LOST)·예약 복원 10,000·거래소 0',
          si['orders'][0][3] == 'WITHDRAWN_OWNER_LOST' and float(avail(si)) == 10000 and si['exchange_creates'] == 0,
          f'{si["orders"][0][2]} {si["orders"][0][3]} {avail(si)}')
    check('S10b 풀린 뒤 복구: 체결 한 번 반영(7,058.53·9.8·버전 1)',
          float(avail(sf)) == 7058.53 and sf['orders'][0][13] == '1' and sf['exchange_creates'] == 1,
          f'{avail(sf)} v{sf["orders"][0][13]}')
    rt.kill_all()


def s11b():
    rt.log('S11b 잠금 상실 — 신규 주문 승인이 멈추는 시점과 진행 중 주문의 복구')
    s_open = seed_live_session()
    s_new = seed_live_session()
    rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}})
    rt.start_app(label='s11b-A')
    buy(s_open)
    rt.wait_until(lambda: state(s_open)['orders'][0][2] == 'SUBMITTED', 30, 1, 'SUBMITTED')
    creates0 = len(rt.exchange_orders()['creates'])
    import threading
    attempts = []
    stop_flag = threading.Event()

    def hammer():
        while not stop_flag.is_set():
            t = time.time()
            try:
                rt.api('POST', f'/api/v1/restart-harness/buy?kind={KIND}&sessionId={s_new}&krw=1000', timeout=5)
                attempts.append((t, 'ok'))
            except Exception as e:
                attempts.append((t, type(e).__name__))
            time.sleep(0.1)
    th = threading.Thread(target=hammer)
    th.start()
    time.sleep(1.0)
    t_lost = time.time()
    rt.psql(f"SELECT pg_terminate_backend({lock_holders()[0]})")
    code = rt.wait_app_exit(30)
    t_exit = time.time()
    time.sleep(1)
    stop_flag.set()
    th.join()
    ok_after = [a for a in attempts if a[0] >= t_lost and a[1] == 'ok']
    ok_before = [a for a in attempts if a[0] < t_lost and a[1] == 'ok']
    rt.log(f'  잠금 상실 {t_lost:.2f} → 종료 {t_exit:.2f} ({t_exit - t_lost:.2f}s), 상실 전 승인 {len(ok_before)}, 상실 후 승인 {len(ok_after)}')
    log = rt.app_log()
    check('S11b 종료 코드 3·사유 기록', code == 3 and 'SINGLE_INSTANCE_LOCK_LOST' in log, f'exit={code}')
    st_new = state(s_new)
    new_creates = len(rt.exchange_orders()['creates']) - creates0
    sent_after = [o for o in st_new['orders'] if o[4] == 't']
    rt.log(f'  상실 전후 생성된 주문 {len(st_new["orders"])}건, 거래소 접수 증가 {new_creates}')
    check('S11b 잠금 상실 뒤 거래소로 나간 신규 주문 0 (claim 직전 확인)',
          new_creates == len([o for o in st_new['orders'] if o[4] == 't']) and
          all(o[2] != 'SUBMITTING' for o in st_new['orders'] if o[4] == 'f'),
          f'creates+{new_creates} states={[o[2] for o in st_new["orders"]]}')
    # 진행 중이던 주문 — 앱이 죽은 사이 체결된다
    rt.ctl('POST', '/_ctl/plan', {'bid': None})
    rt.ctl('POST', '/_ctl/fill', {'uuid': this_order_uuid(s_open, 'BUY'), 'state': 'done', 'volume': '9.8', 'funds': '2940'})
    b, rc = rt.start_other(OTHER_PORT, label='s11b-B')
    check('S11b 다른 인스턴스 기동', rc is None, f'rc={rc}')
    rt.wait_until(lambda: state(s_open)['orders'][0][2] == 'FILLED', 90, 2, 'S11b 진행 주문 복구')
    time.sleep(70)   # 고아 정리·폴러가 여러 번 돈다
    so, sn = state(s_open), state(s_new)
    show('S11b 복구 후(진행 주문)', so)
    show('S11b 복구 후(신규 매수 세션)', sn)
    check('S11b 진행 주문은 다른 인스턴스가 한 번 반영(7,058.53·버전 1)',
          float(avail(so)) == 7058.53 and so['orders'][0][13] == '1' and so['exchange_creates'] == 1,
          f'{avail(so)} v{so["orders"][0][13]}')
    unsent = [o for o in sn['orders'] if o[4] == 'f']
    check('S11b 나가지 않은 의도는 철회되고 예약 복원', all(o[2] == 'FAILED' for o in unsent),
          str([(o[2], o[3]) for o in unsent]))
    filled = sum(1 for o in sn['orders'] if o[2] == 'FILLED')
    expect = 10000 - 980.49 * filled   # 대역: 1,000 요청 → 980 사용·수수료 0.49
    rt.log(f'  신규 매수 세션: FILLED {filled}건, 잔액 {avail(sn)} (기대 {expect:.2f})')
    check('S11b 신규 매수 세션: 모든 주문 종결(FILLED/FAILED)·잔액 = 10,000 − 체결분(미체결 예약 잔존 0)',
          all(o[2] in ('FILLED', 'FAILED') for o in sn['orders']) and abs(float(avail(sn)) - expect) < 0.006,
          f'{avail(sn)} filled={filled} states={[o[2] for o in sn["orders"]]}')
    rt.kill_all()


def s13b():
    rt.log('S13b 설정 불일치·risk_config 없음에서도 기존 주문 추적·위험 축소(정지·청산)는 계속된다')
    for case in ('pool', 'risk'):
        sid = seed_live_session()
        rt.ctl('POST', '/_ctl/plan', {'bid': {'fill': 'wait', 'once': True}})
        rt.start_app(label=f's13b-{case}-submit')
        buy(sid)
        rt.wait_until(lambda: state(sid)['orders'][0][2] == 'SUBMITTED', 30, 1, 'SUBMITTED')
        rt.kill_app()
        saved = None
        if case == 'pool':
            props = stage_props('STAGE_1', '100000', '20000', '4')
        else:
            props = None
            saved = rt.one("SELECT json_agg(r) FROM risk_config r")
            rt.psql("DELETE FROM risk_config")
        try:
            rt.start_app(label=f's13b-{case}-blocked', props=props)
            tag = '등록값 불일치' if case == 'pool' else 'risk_config 없음'
            other = seed_live_session()
            kind_, detail, _ = timed_buy(other)
            check(f'S13b {tag}: 신규 매수는 거절', kind_ != 'ok' and not state(other)['orders'], f'{kind_} {detail}')
            rt.ctl('POST', '/_ctl/fill', {'uuid': this_order_uuid(sid, 'BUY'), 'state': 'done',
                                          'volume': '9.8', 'funds': '2940'})
            rt.wait_until(lambda: state(sid)['orders'][0][2] == 'FILLED', 90, 2, f'S13b {tag} 추적')
            st = state(sid)
            check(f'S13b {tag}: 기존 주문 추적·체결 한 번 반영(7,058.53·9.8)',
                  st['orders'][0][2] == 'FILLED' and float(avail(st)) == 7058.53 and float(st['positions'][0][2]) == 9.8,
                  f'{st["orders"][0][2]} {avail(st)}')
            rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'done', 'once': True}})
            stop_session(sid)
            rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 90, 2, f'S13b {tag} 청산')
            time.sleep(5)
            st = state(sid)
            asks = [e for e in st['exchange_orders'] if e['side'] == 'ask']
            check(f'S13b {tag}: 정지·청산 가능(STOPPED·CLOSED·매도 1건·9,997.35)',
                  st['session'][0][0] == 'STOPPED' and st['positions'][0][1] == 'CLOSED' and len(asks) == 1
                  and abs(float(avail(st)) - 9997.35) < 0.006, f'{st["session"]} ask={len(asks)} {avail(st)}')
            rt.kill_app()
        finally:
            if saved is not None:
                rt.psql("INSERT INTO risk_config SELECT * FROM json_populate_recordset(NULL::risk_config, '"
                        + saved.replace("'", "''") + "'::json)")
    check('S13b risk_config 복원', rt.one("SELECT count(*) FROM risk_config") == '1')


def s11c():
    rt.log('S11c claim 직전 잠금 확인 — 감시 주기를 600초로 늦추고, 의도 커밋 뒤·claim 전에 잠금을 끊는다')
    sid = seed_live_session()
    rt.start_app(label='s11c-A', crashpoint=None,
                 props={'trading.single-instance.watchdog-seconds': '600', 'pausepoint': 'BEFORE_CLAIM_CHECK:6000'})
    creates0 = len(rt.exchange_orders()['creates'])
    buy(sid)    # 의도 커밋 후 응답, 제출 작업은 claim 직전에서 6초 멈춘다
    rt.wait_until(lambda: state(sid)['orders'] and state(sid)['orders'][0][2] == 'INTENT_READY', 5, 0.2, '의도')
    t0 = time.time()
    rt.psql(f"SELECT pg_terminate_backend({lock_holders()[0]})")
    code = rt.wait_app_exit(30)
    took = time.time() - t0
    log = rt.app_log()
    st = state(sid)
    show('종료 직후', st)
    check('S11c 종료 코드 3 — 감시 주기(600초)가 아니라 claim 직전 확인이 멈췄다',
          code == 3 and 'SINGLE_INSTANCE_LOCK_LOST [claim 직전]' in log and took < 15, f'exit={code} {took:.1f}s')
    check('S11c 거래소 제출 0·주문은 INTENT_READY(claim 전)',
          len(rt.exchange_orders()['creates']) == creates0 and st['orders'][0][2] == 'INTENT_READY',
          f'{st["orders"][0][2]} creates+{len(rt.exchange_orders()["creates"]) - creates0}')
    b, rc = rt.start_other(OTHER_PORT, label='s11c-B')
    rt.wait_until(lambda: state(sid)['positions'][0][1] == 'CLOSED', 90, 2, 'S11c 복구')
    st = state(sid)
    check('S11c 다른 인스턴스가 철회(WITHDRAWN_OWNER_LOST)·예약 복원 10,000·거래소 0',
          rc is None and st['orders'][0][3] == 'WITHDRAWN_OWNER_LOST' and float(avail(st)) == 10000
          and st['exchange_creates'] == 0, f'{st["orders"][0][2]} {st["orders"][0][3]} {avail(st)}')
    rt.kill_all()


# ── 선행 조건 1번 — 공유 풀 손실 한도 (2026-10-14) ───────────────────────────────

def buy_krw(sid, krw):
    try:
        r = rt.api('POST', f'/api/v1/restart-harness/buy?kind={KIND}&sessionId={sid}&krw={krw}', timeout=40)
        return ('ok', r)
    except urllib.error.HTTPError as e:
        return ('http', e.code)
    except Exception as e:
        return ('err', repr(e))


def start_session(sid):
    path = f'/api/v1/dynamic-sessions/{sid}/start' if KIND == 'DYNAMIC' else f'/api/v1/trading/sessions/{sid}/start'
    rt.api('POST', path, timeout=40)


def loss_decisions_after(n, timeout=10):
    """판정 줄이 n 개보다 많아질 때까지 기다린다 — 앱 로그가 파일에 늦게 쓰일 수 있다."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = loss_decisions()
        if len(d) > n:
            return d
        time.sleep(0.5)
    return loss_decisions()


def loss_decisions():
    """앱 로그의 손실 한도 판정 줄 — (순자산 변화, 누적 한도, 24시간 한도, 총 자본)."""
    import re
    out = []
    for line in rt.app_log().splitlines():
        if '한도 도달' in line and '순자산 변화' in line:
            m = re.search(r'순자산 변화 (-?[\d.]+) .*한도 누적 −([\d.]+) · 24시간 −([\d.]+) \(총 자본 ([\d.]+) 고정\)', line)
            if m:
                out.append(m.groups())
    return out


def s14():
    rt.log('S14 손실 한도 — risk_config 대조, 실제 체결 손실로 24시간 한도 도달, 신규 진입만 차단, 세션·프로세스 재시작에도 같은 한도')
    stage1 = stage_props('STAGE_1', '100000', '20000', '3')
    table = session_table()
    # 앞 시나리오가 남긴 열린 포지션·세션을 닫는다 — 1단계 슬롯 3 을 채우지 않게. 단계 기준도 새로 시작한다(시험 준비).
    rt.psql("UPDATE position SET status = 'CLOSED' WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC')")
    rt.psql("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
    rt.psql("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING' AND trading_mode = 'REAL'")
    rt.psql("DELETE FROM pool_equity_snapshot")
    rt.psql("DELETE FROM capital_stage_baseline")
    saved = rt.psql("SELECT id, max_daily_loss_pct::text, max_portfolio_drawdown_pct::text FROM risk_config ORDER BY id DESC LIMIT 1")[0]
    rid = saved[0]
    try:
        # ① 풀은 STAGE_1 정확한 값, risk_config 는 V88 기본값(24시간 1.5) → 등록값과 달라 진입 차단
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = 1.5, max_portfolio_drawdown_pct = 15.0 WHERE id = {rid}")
        rt.start_app(label='s14-mismatch', props=stage1)
        log = rt.app_log()
        check('S14① risk_config 1.5: 기동 로그 경보', '위험 한도 설정이 사전 등록과 맞지 않는다' in log
              and 'max_daily_loss_pct=1.5' in log)
        sid = seed_live_session()
        kind_, detail = buy_krw(sid, 3000)
        st = state(sid)
        check('S14① 매수 거절(RISK_CONFIG_MISMATCH)·흔적 없음', kind_ != 'ok' and 'RISK_CONFIG_MISMATCH' in rt.app_log()
              and not st['orders'] and not st['positions'] and float(avail(st)) == 10000 and st['exchange_creates'] == 0,
              f'{kind_} {detail}')
        rt.psql(f"UPDATE {table} SET status = 'STOPPED' WHERE id = {sid}")
        rt.kill_app()

        # ② 등록값(3.0 · 15.0)이면 승인. 보유 하나(C)를 열고, 다른 세션(A)은 9,000 매수 후 5,000 에 팔아 실제 손실을 낸다.
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0 WHERE id = {rid}")
        rt.start_app(label='s14-run', props=stage1)
        check('S14② risk_config 3.0·15.0: 기동 경보 없음', '위험 한도 설정이 사전 등록과 맞지 않는다' not in rt.app_log())
        c = seed_live_session()
        buy_krw(c, 3000)
        rt.wait_until(lambda: state(c)['orders'] and state(c)['orders'][0][2] == 'FILLED', 60, 1, 'S14 C 매수')
        a = seed_live_session()
        buy_krw(a, 9000)
        rt.wait_until(lambda: state(a)['orders'] and state(a)['orders'][0][2] == 'FILLED', 60, 1, 'S14 A 매수')
        check('S14② 한도 안에서는 승인(C 3,000·A 9,000 체결)',
              state(c)['exchange_creates'] == 1 and state(a)['exchange_creates'] == 1,
              f'{avail(state(c))} {avail(state(a))}')
        rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'wait', 'once': True}})
        stop_session(a)
        rt.wait_until(lambda: any(o[1] == 'SELL' and o[4] == 't' for o in state(a)['orders']), 60, 1, 'S14 A 매도 접수')
        rt.ctl('POST', '/_ctl/fill', {'uuid': this_sell_uuid(a), 'state': 'done', 'volume': '29.4', 'funds': '5000'})
        rt.wait_until(lambda: state(a)['positions'][0][1] == 'CLOSED', 90, 2, 'S14 A 청산')
        time.sleep(3)
        cash = rt.one("SELECT ROUND(SUM(CASE WHEN o.side = 'BUY' THEN -(o.applied_executed_funds + o.applied_paid_fee) "
                      "ELSE o.applied_executed_funds - o.applied_paid_fee END), 2) FROM \"order\" o JOIN position p ON p.id = o.position_id "
                      "WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.opened_at >= (SELECT started_at FROM capital_stage_baseline)")
        rt.log(f'  단계 시작 이후 실자금 현금 흐름 = {cash} (A: −8,824.41 + 4,998.00, C: −2,941.47)')

        # ③ 신규 진입 차단 — 대역 요청 없이, 흔적 없이
        b = seed_live_session()
        kind_, detail = buy_krw(b, 3000)
        st = state(b)
        d1 = loss_decisions_after(0)
        check('S14③ 24시간 한도 도달: 매수 거절·흔적 없음·거래소 0', kind_ != 'ok' and not st['orders'] and not st['positions']
              and float(avail(st)) == 10000 and st['exchange_creates'] == 0 and bool(d1), f'{kind_} {detail} {d1[-1:]}')
        check('S14③ 판정: 순자산 변화 −3,829.35(현금 −6,767.88 + 보유 2,938.53), 한도 15,000/3,000(총 자본 100,000 고정)',
              bool(d1) and d1[-1] == ('-3829.35', '15000.00', '3000.00', '100000.00'), str(d1[-1:]))
        check('S14③ 사유: 최근 24시간 순자산 감소 한도', '최근 24시간 순자산 감소 한도 도달' in rt.app_log())

        # ④ 세션 정지·재시작 — 한도 금액·순자산 변화가 그대로
        stop_session(b)
        rt.wait_until(lambda: state(b)['session'][0][0] == 'STOPPED', 30, 1, 'S14 B 정지')
        start_session(b)
        rt.wait_until(lambda: state(b)['session'][0][0] == 'RUNNING', 30, 1, 'S14 B 재시작')
        kind_, detail = buy_krw(b, 3000)
        d2 = loss_decisions_after(len(d1))
        check('S14④ 세션 정지·재시작 뒤에도 같은 판정(같은 순자산 변화·같은 한도 금액)',
              kind_ != 'ok' and len(d2) > len(d1) and d2[-1] == d1[-1] and state(b)['exchange_creates'] == 0,
              f'{d1[-1:]} → {d2[-1:]}')

        # ⑤ 위험 축소는 계속 — 차단 중에도 C 정지 매도가 나가고 한 번 반영된다
        rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'done', 'once': True}})
        stop_session(c)
        rt.wait_until(lambda: state(c)['positions'][0][1] == 'CLOSED', 90, 2, 'S14 C 청산')
        sc = state(c)
        asks = [e for e in sc['exchange_orders'] if e['side'] == 'ask']
        check('S14⑤ 차단 중에도 정지·청산 계속(매도 1건, CLOSED)', len(asks) == 1 and sc['session'][0][0] == 'STOPPED', str(asks))

        # ⑥ 기록 — 1분 스냅숏이 쌓인다(24시간 기준)
        rt.wait_until(lambda: int(rt.one("SELECT count(*) FROM pool_equity_snapshot WHERE stage = 'STAGE_1'")) > 0,
                      120, 5, 'S14 스냅숏')
        snap = rt.psql("SELECT taken_at, net_change_krw::text FROM pool_equity_snapshot WHERE stage = 'STAGE_1' "
                       "ORDER BY taken_at DESC LIMIT 1")
        check('S14⑥ 순자산 변화 1분 기록이 남는다', bool(snap), str(snap))
        base1 = rt.one("SELECT started_at FROM capital_stage_baseline WHERE stage = 'STAGE_1'")

        # ⑦ 프로세스 재시작 — 기준 시각·한도 금액이 그대로이고 차단이 이어진다
        rt.kill_app()
        rt.start_app(label='s14-restart', props=stage1)
        kind_, detail = buy_krw(b, 3000)
        d3 = loss_decisions_after(0)
        base2 = rt.one("SELECT started_at FROM capital_stage_baseline WHERE stage = 'STAGE_1'")
        check('S14⑦ 프로세스 재시작: 기준 시각 불변·같은 한도 금액·차단 지속',
              kind_ != 'ok' and base1 == base2 and bool(d3)
              and d3[-1][1:] == ('15000.00', '3000.00', '100000.00') and state(b)['exchange_creates'] == 0,
              f'{base1}→{base2} {d3[-1:]}')
        rt.log(f'  재시작 뒤 판정 {d3[-1:]} (C 청산으로 보유 평가 2,938.53 → 매도 순대금으로 바뀌었다)')
        rt.kill_app()
    finally:
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = {saved[1]}, max_portfolio_drawdown_pct = {saved[2]} WHERE id = {rid}")
        rt.psql("UPDATE position SET status = 'CLOSED' WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC')")
        rt.psql("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
        rt.psql("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING' AND trading_mode = 'REAL'")
    check('S14 risk_config 복원', rt.psql(f"SELECT max_daily_loss_pct::text FROM risk_config WHERE id = {rid}")[0][0] == saved[1])


# ── 선행 조건 1번 1-g — 포지션별 한도 (2026-10-15) ────────────────────────────────

def plim(sid):
    """이 세션 첫 포지션의 (배정액, 투입금, 고점, 도달 코드, 처리)."""
    rows = rt.psql(
        "SELECT coalesce(p.entry_allocation_krw::text,''), coalesce(p.invested_krw::text,''), coalesce(p.peak_value_krw::text,''), "
        "coalesce(b.code,''), coalesce(b.stop_action,'') FROM position p LEFT JOIN position_limit_breach b ON b.position_id = p.id "
        f"WHERE p.session_kind='{KIND}' AND p.session_id={sid} ORDER BY p.id LIMIT 1")
    return rows[0] if rows else None


def mark(price, coin='KRW-DOGE'):
    rt.api('POST', f'/api/v1/restart-harness/mark?coin={coin}&price={price}', timeout=10)


def num(v):
    return float(v) if v not in (None, '') else None


def s15():
    rt.log('S15 포지션별 한도 — 배정액·고점이 정산·프로세스 재시작을 넘어 유지, 실시간 시세로만 판정, 도달 → 진입 차단 → 정지')
    stage1 = stage_props('STAGE_1', '100000', '20000', '3')
    rt.psql("UPDATE position SET status = 'CLOSED' WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC')")
    rt.psql("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
    rt.psql("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING' AND trading_mode = 'REAL'")
    rt.psql("DELETE FROM pool_equity_snapshot")
    rt.psql("DELETE FROM capital_stage_baseline")
    saved = rt.psql("SELECT id, max_daily_loss_pct::text, max_portfolio_drawdown_pct::text FROM risk_config ORDER BY id DESC LIMIT 1")[0]
    rid = saved[0]
    try:
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = 3.0, max_portfolio_drawdown_pct = 15.0 WHERE id = {rid}")
        rt.start_app(label='s15-run', props=stage1)

        # ① 매수 3,000 → 체결 2,940 + 1.47. 정산이 투입금을 실제 사용액으로 덮어써도 배정액은 3,000.
        a = seed_live_session()
        buy_krw(a, 3000)
        rt.wait_until(lambda: state(a)['orders'] and state(a)['orders'][0][2] == 'FILLED', 60, 1, 'S15 A 매수')
        rt.wait_until(lambda: num(plim(a)[1]) == 2941.47, 30, 1, 'S15 A 정산')
        p = plim(a)
        check('S15① 배정액 3,000 (투입금은 정산이 2,941.47 로 덮어씀)', num(p[0]) == 3000 and num(p[1]) == 2941.47, str(p))

        # ② 실시간 시세 400 → 고점 9.8 × 400 × 0.9995 = 3,918.04
        mark(400)
        rt.wait_until(lambda: num(plim(a)[2]) == 3918.04, 60, 2, 'S15 고점')
        check('S15② 실시간 시세로 고점 기록 3,918.04', num(plim(a)[2]) == 3918.04, str(plim(a)))

        # ③ 프로세스 재시작 — 시세 기록이 사라진 동안 대체 시세(평균단가 300 = 고점 대비 −25%)로 도달을 기록하지 않는다
        rt.kill_app()
        rt.start_app(label='s15-restart', props=stage1)
        time.sleep(45)   # 감시 첫 실행 25초 + 주기 10초를 넘긴다
        p = plim(a)
        check('S15③ 재시작: 배정액·고점 유지, 실시간 시세 전에는 도달 기록 없음',
              num(p[0]) == 3000 and num(p[2]) == 3918.04 and p[3] == '', str(p))

        # ④ 실시간 시세 310 → 3,036.48: 고점 대비 −881.56 ≤ −783.61 → MAX_DRAWDOWN (손익 +95 라 CAPITAL_LOSS 아님). 자동 정지 OFF → 경보
        mark(310)
        rt.wait_until(lambda: plim(a)[4] != '', 60, 2, 'S15 도달 처리')
        p = plim(a)
        check('S15④ MAX_DRAWDOWN 도달·경보(자동 정지 OFF)·세션 RUNNING 유지',
              p[3] == 'MAX_DRAWDOWN' and p[4] == 'ALERTED' and state(a)['session'][0][0] == 'RUNNING', str(p))

        # ⑤ 신규 진입 차단 — 흔적 없이
        before = state(a)
        kind_, detail = buy_krw(a, 3000)
        after = state(a)
        check('S15⑤ 도달 세션의 매수 거절(POSITION_LIMIT)·흔적 없음·거래소 0',
              kind_ != 'ok' and 'POSITION_LIMIT' in rt.app_log() and len(after['orders']) == len(before['orders'])
              and len(after['positions']) == len(before['positions']) and avail(after) == avail(before)
              and after['exchange_creates'] == before['exchange_creates'], f'{kind_} {detail}')
        rt.kill_app()

        # ⑥ 자동 정지 ON 으로 재시작 — 이미 처리한 기록은 다시 처리하지 않고 차단은 이어진다
        rt.start_app(label='s15-autostop', props=stage1 | {'kill-criteria.auto-stop': 'true'})
        time.sleep(40)
        kind_, detail = buy_krw(a, 3000)
        check('S15⑥ 재시작 뒤에도 차단 지속·기록 재처리 없음(A 는 RUNNING, 처리 ALERTED 그대로)',
              kind_ != 'ok' and plim(a)[4] == 'ALERTED' and state(a)['session'][0][0] == 'RUNNING', f'{kind_} {plim(a)}')

        # ⑦ 새 세션 B: 300 에 사서 250 → 2,448.78, 손익 −492.69 ≤ −450 (배정액 3,000 × 15%) → CAPITAL_LOSS → 자동 정지
        mark(300)
        b = seed_live_session()
        buy_krw(b, 3000)
        rt.wait_until(lambda: state(b)['orders'] and state(b)['orders'][0][2] == 'FILLED', 60, 1, 'S15 B 매수')
        rt.ctl('POST', '/_ctl/plan', {'ask': {'fill': 'wait', 'once': True}})
        mark(250)
        rt.wait_until(lambda: plim(b)[4] != '', 60, 2, 'S15 B 도달 처리')
        t_stop = time.time()
        p = plim(b)
        sb = state(b)
        check('S15⑦ CAPITAL_LOSS 도달 → 자동 정지(세션 STOPPED, 청산 매도 접수)',
              p[3] == 'CAPITAL_LOSS' and p[4] == 'AUTO_STOPPED' and sb['session'][0][0] == 'STOPPED'
              and any(o[1] == 'SELL' and o[4] == 't' for o in sb['orders']), f'{p} {sb["session"]}')
        check('S15⑦ 정지 성공 ≠ 정리 완료: 매도 체결 전 포지션 CLOSING',
              sb['positions'][0][1] == 'CLOSING', str(sb['positions']))
        rt.ctl('POST', '/_ctl/fill', {'uuid': this_sell_uuid(b), 'state': 'done', 'volume': '9.8', 'funds': '2450'})
        rt.wait_until(lambda: state(b)['positions'][0][1] == 'CLOSED', 90, 2, 'S15 B 청산')
        rt.log(f'  정지 처리 → 정리 완료(포지션 CLOSED) {time.time() - t_stop:.1f}초')
        check('S15⑦ 청산 체결 반영 후 정리 완료(CLOSED, 매도 1건)',
              state(b)['positions'][0][1] == 'CLOSED'
              and len([e for e in state(b)['exchange_orders'] if e['side'] == 'ask']) == 1, str(state(b)['positions']))
        rt.kill_app()
    finally:
        rt.psql(f"UPDATE risk_config SET max_daily_loss_pct = {saved[1]}, max_portfolio_drawdown_pct = {saved[2]} WHERE id = {rid}")
        rt.psql("UPDATE position SET status = 'CLOSED' WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC')")
        rt.psql("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'")
        rt.psql("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING' AND trading_mode = 'REAL'")


SCEN = {'S1': s1, 'S2': s2, 'S3': lambda: s3(False), 'S3b': lambda: s3(True),
        'S4': lambda: s45('AFTER_FILL_OBSERVED'), 'S5': lambda: s45('IN_FILL_ACCOUNTING'), 'S6': s6, 'S7': s7, 'S8': s8, 'S9': s9,
        'S10': s10, 'S11': s11, 'S12': s12, 'S13': s13,
        'S10b': s10b, 'S11b': s11b, 'S11c': s11c, 'S13b': s13b, 'S14': s14, 'S15': s15}

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
                if kind == 'DYNAMIC' and n not in ('S3', 'S3b', 'S6', 'S7', 'S8', 'S9', 'S12', 'S14', 'S15'):
                    continue   # DYNAMIC 은 접수 후 종료·부분 청산 중 종료·종료 중 체결만 (의도·claim·회계 지점은 공통 엔진)
                try:
                    rt.ctl('POST', '/_ctl/plan', {'bid': None, 'ask': None})   # 앞 시나리오의 1회용 계획이 새지 않게
                    SCEN[n]()
                except Exception as e:
                    check(f'{n} 실행 오류', False, repr(e))
                finally:
                    rt.kill_all()
    finally:
        rt.stop_double()
        try:
            rt.dump_db('after-run')     # 🔴 실행 직후 상태를 남긴다 — 다음 --fresh 가 지우기 전에도 한 번 더 뜬다
        except Exception as e:
            rt.log(f'  DB 덤프 실패: {e!r}')
    rt.log('==== 요약 ====')
    for name, ok, detail in RESULTS:
        rt.log(f'{"PASS" if ok else "FAIL"} {name} {detail}')
    rt.log(f'{sum(1 for r in RESULTS if r[1])}/{len(RESULTS)} 통과')
