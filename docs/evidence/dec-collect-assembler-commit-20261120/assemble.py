"""DB 추출 부분 묶음 조립기 (총괄 v1.208 §3-2) — 파일 입력만. psql·SSH·네트워크를 부르지 않는다.

결과는 "DB 추출 부분 묶음"이다. P7 전체 관측·F3 통과 파일·계좌 일치·월말 확정이 아니다.
거래소·지문 전후(db_fp_pre/post)·앱 코드·자본 원천은 이 묶음에 없으며 기본값으로 채우지 않는다.

사용: python -I assemble.py --registry R --stdout S --stderr E --psql-rc F --clock C --sql Q --parser P --out 새폴더
  --clock 은 수집기 시각 기록 JSON(아래 CLOCK_LOG_KEYS). 파일이 없으면 "시각 기록 없음"으로 실패 처리한다.
종료값:
  0  파서 0(2단계까지 읽음) + 수집기 시계 검사 통과. 그래도 계좌·월말 확정이 아니다
  10 파서 10 게이트 STOP — 원문 보존, 금액 단계 없음
  13 파서 0이지만 시계 이동·역행 → 그 회차 축 관계 미확정(표본을 내보내지 않음)
  20 psql rc≠0 — 부분 출력도 원문 보존, 파서 결과 20, 금액 단계 없음
  30 파서 형식 결함·게이트 불일치
  40 출력 폴더가 이미 있음 — 아무것도 쓰지 않음
  41 필수 기록 실패(입력 원문 없음·시각 기록 없음/형식 오류·고정 출처 해시 불일치)
  42 보존 쓰기·해시 기록·확정 실패 — bundle.json 이 생기지 않고 bundle.json.uncommitted 가 남는다
확정 순서(v1.209 보완): bundle.json.uncommitted → SHA256SUMS → os.replace 로 bundle.json (마지막 단계).
소비: python -I assemble.py --verify 폴더 (0 소비 가능, 44 거절). 다음 단계는 종료값·bundle.json 만으로 판단하지 않는다.
0 이외는 성공이 아니다. 10/20/30/41에서는 downstream 을 'blocked'로 적고 금액 변환·성적표·계좌 판정 입력을 만들지 않는다.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys
from datetime import datetime, timedelta

SQL_SHA = '9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03'
PARSER_SHA = '0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908'
BUNDLE_KIND = 'db-extract-partial-1'
CLOCK_LOG_VERSION = 'collect-clock-1'
# 수집기 기록: 벽시계 ISO(시간대 포함) + 단조 시계 ns. c0/m0·c1/m1 은 psql 호출 직전·직후, c_start/c_end 는 회차 앞뒤
CLOCK_LOG_KEYS = {'version', 'c_start', 'm_start_ns', 'c0', 'm0_ns', 'c1', 'm1_ns', 'c_end', 'm_end_ns'}
MONO_TOL = timedelta(microseconds=500)   # P7 v8 MONO_TOL 과 같은 값
NOT_IN_BUNDLE = ['db_fp_pre', 'exchange_before', 'exchange_after', 'db_fp_post',
                 'app_code_sha', 'exchange_account_orders', 'registered_capital_cash_base', 'ruleset.confirmed']
BUNDLE, PENDING, SUMS = 'bundle.json', 'bundle.json.uncommitted', 'SHA256SUMS'
COMMIT_RULE = ('pending-sums-rename-1: bundle.json 은 SHA256SUMS 기록 뒤 마지막 os.replace 로만 생긴다. '
               '소비 조건 = verify_committed 통과(미확정 흔적 없음·목록=폴더·해시 일치·rc 0·db_part_only)')


def sha(b):
    return hashlib.sha256(b).hexdigest()


def wall(x):
    t = datetime.fromisoformat(x)
    if t.tzinfo is None:
        raise ValueError('시간대 없음')
    return t


def load_clock(raw):
    """수집기 기록 형식 검사. 문제 목록을 돌려준다(빈 목록 = 형식 정상)."""
    try:
        c = json.loads(raw)
    except ValueError as e:
        return None, [f'JSON 아님: {e}']
    if not isinstance(c, dict) or set(c) != CLOCK_LOG_KEYS or c.get('version') != CLOCK_LOG_VERSION:
        return None, [f'키 {sorted(CLOCK_LOG_KEYS)}·version {CLOCK_LOG_VERSION} 아님']
    probs = []
    for k in ('c_start', 'c0', 'c1', 'c_end'):
        try:
            if not isinstance(c[k], str):
                raise ValueError('문자열 아님')
            wall(c[k])
        except ValueError as e:
            probs.append(f'{k}: {e}')
    for k in ('m_start_ns', 'm0_ns', 'm1_ns', 'm_end_ns'):
        if not isinstance(c[k], int) or isinstance(c[k], bool):
            probs.append(f'{k}: 정수 아님')
    return (c if not probs else None), probs


def clock_check(c, d):
    """단조 시계와 벽시계 일치 검사. 통과일 때만 P7 형식 표본과 db_extract 구간을 낸다.
    표본은 d 시각(META clock_timestamp)을 수집기 구간으로 옮기는 근거일 뿐이다. META now()·과거 생성·월 경계로 외삽하지 않는다."""
    W = {k: wall(c[k]) for k in ('c_start', 'c0', 'c1', 'c_end')}
    M = {k: c[k] for k in ('m_start_ns', 'm0_ns', 'm1_ns', 'm_end_ns')}
    probs = []
    if not (M['m_start_ns'] <= M['m0_ns'] <= M['m1_ns'] <= M['m_end_ns']):
        probs.append('단조 시계 순서 아님')
    if not (W['c_start'] <= W['c0'] <= W['c1'] <= W['c_end']):
        probs.append('벽시계 역행')
    for a, b, ma, mb in (('c_start', 'c0', 'm_start_ns', 'm0_ns'), ('c0', 'c1', 'm0_ns', 'm1_ns'),
                         ('c1', 'c_end', 'm1_ns', 'm_end_ns')):
        diff = abs((W[b] - W[a]) - timedelta(microseconds=(c[mb] - c[ma]) / 1000))
        if diff > MONO_TOL:
            probs.append(f'{a}→{b} 벽시계 경과와 단조 경과 차이 {diff} > {MONO_TOL}(시계 이동)')
    out = {'mono_tol_us': MONO_TOL // timedelta(microseconds=1), 'problems': probs}
    if probs:
        out.update(status='unconfirmed', note='그 회차의 db↔collector 축 관계 미확정. 값은 보존, 표본·구간은 내보내지 않음')
        return out
    out['status'] = 'confirmed_interval'
    out['db_extract_step'] = {'step': 'db_extract', 'start': c['c0'], 'end': c['c1']}
    if d is not None:
        out['sample'] = {'pair': ['db', 'collector'], 'step': 'db_extract', 'c0': c['c0'], 'd': d, 'c1': c['c1'],
                         'mono_rtt_ms': str((c['m1_ns'] - c['m0_ns']) / 1_000_000)}
    out['note'] = ('스냅숏은 psql 호출 [c0,c1] 사이(단조 일치 전제). 정확한 스냅숏·월말 T 시각이 아니다. '
                   'DB [now(),END] 는 벽시계 무이동 근거 없이 보장 경계로 쓰지 않는다')
    return out


def verify_committed(out):
    """다음 단계의 유일한 소비 조건. (True, bundle) 또는 (False, 사유).
    bundle.json 을 직접 읽는 쪽도 이 검사를 통과한 묶음만 쓴다. 종료값만 믿거나 bundle.json 만 열지 않는다."""
    try:
        names = set(os.listdir(out))
    except OSError as e:
        return False, f'폴더 읽기 실패: {e}'
    if PENDING in names:
        return False, f'미확정 흔적 {PENDING} 있음'
    if BUNDLE not in names:
        return False, f'{BUNDLE} 없음(확정되지 않음)'
    if SUMS not in names:
        return False, f'{SUMS} 없음'
    try:
        with open(os.path.join(out, SUMS), 'rb') as f:
            text = f.read().decode('ascii')
    except (OSError, UnicodeDecodeError) as e:
        return False, f'{SUMS} 읽기 실패: {e}'
    listed = {}
    for line in text.splitlines(keepends=True):
        m = re.fullmatch(r'([0-9a-f]{64})  ([^/\\\r\n]+)\n', line)
        if not m or m.group(2) in listed:
            return False, f'{SUMS} 형식 오류: {line!r}'
        listed[m.group(2)] = m.group(1)
    if set(listed) != names - {SUMS}:
        return False, f'{SUMS} 목록과 폴더 파일 불일치'
    for n, h in listed.items():
        try:
            with open(os.path.join(out, n), 'rb') as f:
                if sha(f.read()) != h:
                    return False, f'{n} 해시 불일치'
        except OSError as e:
            return False, f'{n} 읽기 실패: {e}'
    try:
        with open(os.path.join(out, BUNDLE), encoding='utf-8') as f:
            b = json.load(f)
    except (OSError, ValueError) as e:
        return False, f'{BUNDLE} 읽기 실패: {e}'
    if not (isinstance(b, dict) and b.get('kind') == BUNDLE_KIND and b.get('commit') == COMMIT_RULE
            and b.get('rc') == 0 and b.get('status') == 'db_extract_partial' and b.get('downstream') == 'db_part_only'
            and b.get('is_p7_observation') is False and b.get('is_f3_input') is False
            and b.get('account_or_month_end_confirmed') is False):
        return False, f'{BUNDLE} 성공 확정 묶음 아님(rc={b.get("rc") if isinstance(b, dict) else None})'
    return True, b


def main(argv=None):
    ap = argparse.ArgumentParser()
    for k in ('registry', 'stdout', 'stderr', 'psql-rc', 'clock', 'sql', 'parser', 'out'):
        ap.add_argument('--' + k, required=True)
    a = ap.parse_args(argv)
    # 40: 기존 경로는 아무것도 쓰지 않고 멈춘다(mkdir 이 원자적으로 거절)
    try:
        os.mkdir(a.out)
    except FileExistsError:
        print(json.dumps({'rc': 40, 'error': '출력 폴더가 이미 있음 — 쓰지 않음'}))
        return 40
    man = {'kind': BUNDLE_KIND, 'is_p7_observation': False, 'is_f3_input': False,
           'account_or_month_end_confirmed': False, 'not_in_bundle': NOT_IN_BUNDLE,
           'downstream': 'blocked', 'inputs': {}, 'fixed_sources': {}, 'problems': []}

    def finish(rc):
        # 기록 확정 순서(총괄 v1.209 F1): 묶음은 미확정 이름으로 쓰고, 체크섬까지 확정한 뒤
        # 마지막 한 번의 이름 바꾸기(os.replace)로만 bundle.json 이 생긴다. 그 전 어느 단계가 실패해도
        # bundle.json 은 없고 미확정 흔적(bundle.json.uncommitted)이 남으며 종료값은 42다.
        man['rc'] = rc
        man['commit'] = COMMIT_RULE
        pending = os.path.join(a.out, PENDING)
        try:
            with open(pending, 'w', encoding='utf-8', newline='\n') as f:
                json.dump(man, f, ensure_ascii=False, indent=1)
            entries = {}
            for n in os.listdir(a.out):
                if n != SUMS:
                    with open(os.path.join(a.out, n), 'rb') as f:
                        entries[BUNDLE if n == PENDING else n] = sha(f.read())
            with open(os.path.join(a.out, SUMS), 'w', encoding='ascii', newline='\n') as f:
                f.writelines(f'{entries[n]}  {n}\n' for n in sorted(entries))
            os.replace(pending, os.path.join(a.out, BUNDLE))   # 마지막 확정 단계
        except OSError as e:
            print(json.dumps({'rc': 42, 'error': f'보존 쓰기 실패(미확정): {e}'}))
            return 42
        print(json.dumps({'rc': rc, 'status': man.get('status'), 'downstream': man['downstream']}))
        return rc

    # 원문 보존: 있는 것은 모두 바이트 그대로 복사한다(실패 원문도 보존)
    names = {'registry': 'registry.json', 'stdout': 'stdout.tsv', 'stderr': 'stderr.txt',
             'psql-rc': 'psql_rc.txt', 'clock': 'clock.json'}
    raw = {}
    for k, n in names.items():
        src = getattr(a, k.replace('-', '_'))
        try:
            with open(src, 'rb') as f:
                raw[k] = f.read()
        except OSError:
            man['inputs'][n] = None
            continue
        try:
            with open(os.path.join(a.out, n), 'wb') as f:
                f.write(raw[k])
        except OSError as e:
            man['problems'].append(f'{n} 보존 실패: {e}')
            return finish(42)
        man['inputs'][n] = sha(raw[k])
    for k, path, want in (('sql', a.sql, SQL_SHA), ('parser', a.parser, PARSER_SHA)):
        try:
            with open(path, 'rb') as f:
                got = sha(f.read())
        except OSError:
            got = None
        man['fixed_sources'][k] = {'sha256': got, 'expected': want, 'match': got == want}
    if not all(x['match'] for x in man['fixed_sources'].values()):
        man['status'] = 'fixed_source_mismatch'
        man['problems'].append('고정 출처 해시 불일치 — 파서를 부르지 않음')
        return finish(41)
    missing = [names[k] for k in ('registry', 'stdout', 'stderr', 'psql-rc') if k not in raw]
    if missing:
        man['status'] = 'input_missing'
        man['problems'].append(f'필수 원문 없음: {missing} — 파서를 부르지 않음')
        return finish(41)
    try:
        psql_rc = int(raw['psql-rc'].decode('ascii').strip())
    except (UnicodeDecodeError, ValueError):
        man['status'] = 'input_missing'
        man['problems'].append('psql_rc.txt 정수 아님')
        return finish(41)
    man['psql_rc'] = psql_rc

    # 파서 v3 (파일 입력만). 결과·종료값·stderr 를 그대로 보존
    pj = os.path.join(a.out, 'parsed.json')
    p = subprocess.run([sys.executable, '-I', a.parser, '--raw', os.path.join(a.out, 'stdout.tsv'),
                        '--psql-rc', str(psql_rc), '--registry', os.path.join(a.out, 'registry.json'), '--out', pj],
                       capture_output=True)
    try:
        for n, b in (('parse_rc.txt', f'{p.returncode}\n'.encode()), ('parse_stdout.txt', p.stdout),
                     ('parse_stderr.txt', p.stderr)):
            with open(os.path.join(a.out, n), 'wb') as f:
                f.write(b)
    except OSError as e:
        man['problems'].append(f'파서 결과 보존 실패: {e}')
        return finish(42)
    man['parse_rc'] = p.returncode
    parsed = None
    if os.path.exists(pj):
        with open(pj, encoding='utf-8') as f:
            parsed = json.load(f)

    # 수집기 시각 기록: 없으면 필수 기록 실패(C1). 파서 결과가 무엇이든 성공으로 끝내지 않는다
    if 'clock' not in raw:
        man['clock'] = {'status': 'absent'}
        man['status'] = 'clock_record_absent'
        man['problems'].append('수집기 시각 기록 없음 — 축 관계 미확정, 성공 아님')
        return finish(41)
    c, cprobs = load_clock(raw['clock'])
    if c is None:
        man['clock'] = {'status': 'malformed', 'problems': cprobs}
        man['status'] = 'clock_record_malformed'
        man['problems'].append('수집기 시각 기록 형식 오류 — 성공 아님')
        return finish(41)
    d = parsed.get('clock_read_at') if parsed and p.returncode in (0, 10) else None
    if d is not None:
        d = d.replace(' ', 'T', 1)
        d = d + ':00' if d[-3] in '+-' else d   # '+00' → '+00:00'
    man['clock'] = clock_check(c, d)

    if p.returncode == 20:
        man['status'] = 'psql_failed'
        return finish(20)
    if p.returncode == 10:
        man['status'] = 'gate_stop'
        return finish(10)
    if p.returncode != 0:
        man['status'] = 'parse_failed'
        return finish(30)
    if man['clock']['status'] != 'confirmed_interval':
        man['status'] = 'parsed_clock_unconfirmed'
        return finish(13)
    man['status'] = 'db_extract_partial'
    man['downstream'] = 'db_part_only'   # 거래소·지문·코드·자본 원천 없이는 P7/F3 로 넘기지 않음
    return finish(0)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--verify':
        ok, r = verify_committed(sys.argv[2])
        print(json.dumps({'consumable': ok, 'reason': None if ok else r}))
        sys.exit(0 if ok else 44)
    sys.exit(main())
