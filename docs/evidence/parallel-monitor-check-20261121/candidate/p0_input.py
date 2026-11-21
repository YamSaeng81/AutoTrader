"""P0 입력 후보 pm-p0-1 (총괄 v1.215 §3-2). 로컬 파일 입력 전용 — SSH·docker·psql·DB·HTTP 호출 없음, U변수 전체를 실행하지 않는다.

기준: 고정 D81D4R U변수.sh(efb96108, d81d4r-20261120/d4/blocks-d81)의 holdobs 원문을 해시 확인 뒤 그 함수의 python 본문만 꺼내
      제공된 backend 로그 한 파일에 실행한다. 패턴은 원문 그대로 `Connection is not available, request timed out` 이며 넓히지 않는다.
      holdobs 종료 1 = 연결 획득 시간 초과 줄 있음, 0 = 없음, 3 = 장기 대여 경고(따로 남김), 그 밖 = 판정 실패.
입력
  --log          보존된 backend 로그 파일(수집 명령의 표준 출력)
  --collect-rc   그 로그 수집 명령의 종료값 파일(정확히 0 이어야 수집 성공)
  --range        대상 구간 근거 JSON {since, until, axis, basis}(파일이 없거나 형식이 다르면 범위 근거 미제공)
  --u-vars / --u-vars-sha256   고정 U변수.sh 와 해시
  --label / --synthetic / --out-dir
판정(status)
  P0_PRESENT                  holdobs 종료 1. 수집·범위 근거가 불완전해도 발생 줄은 숨기지 않는다(불완전 사유를 함께 적는다).
  P0_ABSENT_IN_PROVIDED_LOG   수집 rc 0·비어 있지 않은 로그·범위 근거 있음·holdobs 종료 0/3. **제공 로그 안에서 패턴 없음**일 뿐 운영 전체 안전이 아니다.
  P0_CHECK_INCOMPLETE         그 밖(로그 수집 실패·빈 로그·로그/rc 미제공·범위 근거 미제공·holdobs 판정 실패). p0_events 는 null — 빈 목록·0·정상으로 바꾸지 않는다.
뉴스 Connection reset·캔들 HTTP 오류·외부 API timeout 은 이 도구가 분류하지 않는다(뉴스/66 은 N03 원천). 장기 대여 경고는 P0 와 별도 필드로 남긴다.
로그 줄 시각이 범위 안인지는 판정하지 않는다(holdobs 는 로그 머리 시각만 본다) — 범위는 제공된 수집 근거로만 기록한다.
종료값: P0_ABSENT_IN_PROVIDED_LOG 0 · P0_PRESENT 10 · P0_CHECK_INCOMPLETE 20 · ERROR 40
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile
from datetime import datetime

sys.dont_write_bytecode = True
TOOL, VERSION = 'p0_input', 'pm-p0-1'
U_VARS_SHA = 'efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd'
EXIT = {'P0_ABSENT_IN_PROVIDED_LOG': 0, 'P0_PRESENT': 10, 'P0_CHECK_INCOMPLETE': 20, 'ERROR': 40}
START = "holdobs() { python3 - \"$@\" <<'PYEOF'\n"
RANGE_KEYS = {'since', 'until', 'axis', 'basis'}
SUMMARY = re.compile(r'^HOLD 기준선 질의 장기 대여 경고 (\d+)건\(반환 (\d+)·반환 미관측 (\d+)\) · 그 밖의 장기 대여 경고 (\d+)건 · 연결 획득 시간 초과 줄 (\d+)건$')
EVENT = re.compile(r'^  · 🔴 연결 획득 시간 초과 (\S+) (\S+)$')


def sha_b(b):
    return hashlib.sha256(b).hexdigest()


def extract_holdobs(u_vars, want):
    raw = open(u_vars, 'rb').read()
    if sha_b(raw) != want or want != U_VARS_SHA:
        raise RuntimeError('U변수.sh 해시가 고정값과 다름')
    text = raw.decode('utf-8')
    if text.count(START) != 1:
        raise RuntimeError('holdobs 시작 줄이 정확히 1개가 아님')
    body = text.split(START, 1)[1]
    end = body.index('\nPYEOF\n')
    return body[:end + 1]


def ts(v):
    if not isinstance(v, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})', v):
        return None
    return datetime.fromisoformat(v.replace('Z', '+00:00'))


def check_range(p):
    if not os.path.isfile(p):
        return None, 'RANGE_NOT_PROVIDED'
    try:
        r = json.loads(open(p, 'rb').read().decode('utf-8'))
    except (UnicodeDecodeError, ValueError):
        return None, 'RANGE_INVALID'
    if not isinstance(r, dict) or set(r) != RANGE_KEYS:
        return None, 'RANGE_NOT_PROVIDED' if r is None else 'RANGE_INVALID'
    a, b = ts(r['since']), ts(r['until'])
    if a is None or b is None or not a < b or not isinstance(r['axis'], str) or not r['axis'] \
            or not isinstance(r['basis'], str) or not r['basis'].strip():
        return None, 'RANGE_INVALID'
    return r, None


def run(a):
    out = {'tool': TOOL, 'version': VERSION, 'label': a.label, 'synthetic': a.synthetic == 'true', 'status': None,
           'p0_events': None, 'long_lease_warnings': None, 'incomplete_reasons': [],
           'scope_note': '제공 로그 안의 고정 패턴 검사일 뿐 운영 전체 안전·뉴스/외부 API 상태가 아니다. 뉴스/66 은 N03 원천을 따로 본다.',
           'pattern': 'Connection is not available, request timed out'}
    script = extract_holdobs(a.u_vars, a.u_vars_sha256)
    out['holdobs_source'] = {'u_vars': 'd81d4r-20261120/d4/blocks-d81/U변수.sh', 'u_vars_sha256': U_VARS_SHA,
                             'extracted_sha256': sha_b(script.encode('utf-8'))}
    inc = out['incomplete_reasons']
    # 수집 종료값
    if not os.path.isfile(a.collect_rc):
        inc.append('COLLECT_RC_NOT_PROVIDED')
        rc_txt = None
    else:
        rc_txt = open(a.collect_rc, 'rb').read().decode('utf-8', 'replace').strip()
        if rc_txt != '0':
            inc.append('LOG_COLLECTION_FAILED')
    # 로그
    log_ok = os.path.isfile(a.log)
    logb = open(a.log, 'rb').read() if log_ok else None
    out['input'] = {'collect_rc': rc_txt if rc_txt is None or re.fullmatch(r'-?\d{1,6}', rc_txt) else '<형식 아님>',
                    'log_sha256': sha_b(logb) if logb is not None else None, 'log_bytes': len(logb) if logb is not None else None,
                    'log_lines': logb.count(b'\n') if logb is not None else None}
    if logb is None:
        inc.append('LOG_NOT_PROVIDED')
    elif not logb:
        inc.append('LOG_EMPTY')
    rng, why = check_range(a.range)
    out['range'] = rng
    if why:
        inc.append(why)
    # holdobs: 비어 있지 않은 로그가 있으면 수집이 불완전해도 실행한다(발생 줄은 숨기지 않는다)
    if logb:
        with tempfile.TemporaryDirectory() as td:
            sp = os.path.join(td, 'holdobs.py')
            open(sp, 'w', encoding='utf-8', newline='\n').write(script)
            p = subprocess.run([sys.executable, '-I', '-X', 'utf8', sp, os.path.abspath(a.log)], capture_output=True)
        open(os.path.join(a.out_dir, 'holdobs.stdout.txt'), 'wb').write(p.stdout)
        open(os.path.join(a.out_dir, 'holdobs.stderr.txt'), 'wb').write(p.stderr)
        lines = p.stdout.decode('utf-8', 'replace').splitlines()
        m = SUMMARY.match(lines[0]) if lines else None
        events = [EVENT.match(x) for x in lines if EVENT.match(x)]
        out['holdobs'] = {'exit': p.returncode, 'stdout_sha256': sha_b(p.stdout), 'stderr_bytes': len(p.stderr)}
        sound = m is not None and p.stderr == b'' and int(m.group(5)) == len(events) and \
            ((p.returncode == 1 and events) or (p.returncode in (0, 3) and not events)) and \
            (p.returncode != 3 or int(m.group(1)) + int(m.group(4)) > 0)
        if not sound:
            inc.append('HOLDOBS_FAILED')
        elif p.returncode == 1:
            out['status'] = 'P0_PRESENT'
            out['p0_events'] = [{'code': 'DB_CONNECTION_TIMEOUT', 'label': f'{e.group(1)} {e.group(2)}'} for e in events]
        if sound:
            out['long_lease_warnings'] = {'hold': int(m.group(1)), 'other': int(m.group(4)),
                                          'note': '장기 대여 경고(holdobs 3) — P0 아님, 별도 보고'}
    if out['status'] is None:
        if inc:
            out['status'] = 'P0_CHECK_INCOMPLETE'
        else:
            out['status'] = 'P0_ABSENT_IN_PROVIDED_LOG'
            out['p0_events'] = []
    out['consumable_as_empty_p0'] = out['status'] == 'P0_ABSENT_IN_PROVIDED_LOG'
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    for k in ('log', 'collect-rc', 'range', 'u-vars', 'u-vars-sha256', 'label', 'out-dir'):
        ap.add_argument(f'--{k}', required=True)
    ap.add_argument('--synthetic', required=True, choices=('true', 'false'))
    a = ap.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=False)
    try:
        out = run(a)
    except Exception as e:  # noqa: BLE001
        out = {'tool': TOOL, 'version': VERSION, 'status': 'ERROR', 'p0_events': None, 'consumable_as_empty_p0': False,
               'error': type(e).__name__, 'message': str(e)[:200]}
    out['exit'] = EXIT[out['status']]
    with open(os.path.join(a.out_dir, 'p0.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
