#!/usr/bin/env python3
# 실제 collect_pins 출력의 형식 확인(총괄 v1.251 §3-2). 11-27 n03check 전체는 돌리지 않는다 — 수집 rc·stderr·필수 필드 존재/중복/타입·설치 핀·로그 집계만.
# 사용: pinscheck.py <출력 폴더(pins.txt·pins.txt.err·collect-rc.txt)>  종료: 0 형식·핀 일치 · 1 불일치 · 2 읽기 실패
import importlib.util, pathlib, re, sys
H = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('n03check', H / 'n03check.py'); n3 = importlib.util.module_from_spec(spec); spec.loader.exec_module(n3)
TYPE = {'HEAD': r'[0-9a-f]{40}', 'DIRTY': r'[0-9]+', 'INSTALLED_SHA': r'[0-9a-f]{40}', 'SID_LEDGER_SHA': r'[0-9a-f]{40}', 'past_ledger': r'[0-9]+',
        'crontab': r'[0-9a-f]{64}', 'daily_check.sh': r'[0-9a-f]{64}', 'daily_checks.sql': r'[0-9a-f]{64}', 'sid_ledger.sql': r'[0-9a-f]{64}',
        'backend': r'Image=sha256:[0-9a-f]{64} StartedAt=\S+ RestartCount=[0-9]+ Status=[a-z]+', 'frontend': r'Image=sha256:[0-9a-f]{64} StartedAt=\S+ RestartCount=[0-9]+ Status=[a-z]+'}
def main(d):
    P = pathlib.Path(d); out = []; bad = 0
    def ck(n, ok, det=''):
        nonlocal bad; bad += 0 if ok else 1; out.append('%s %s%s' % ('PASS' if ok else 'FAIL', n, (' — ' + det) if det else ''))
    rc = (P / 'collect-rc.txt').read_text(encoding='utf-8').strip()
    ck('수집 rc0·stderr 0B', rc == 'collect_pins rc=0 stderr=0B' and (P / 'pins.txt.err').stat().st_size == 0, rc)
    seen = {}; extra = []
    for ln in (P / 'pins.txt').read_text(encoding='utf-8').splitlines():
        k, _, v = ln.partition(' ')
        if k == 'logcount':
            c, _, key = v.partition(' '); k, v = 'logcount:' + key, c
        if k not in n3.PIN and k[9:] not in n3.LOGKEYS:
            extra.append(k)
        seen.setdefault(k, []).append(v)
    need = list(n3.PIN) + ['logcount:' + k for k in n3.LOGKEYS]
    ck('필수 필드 %d개 모두 1회' % len(need), all(len(seen.get(k, [])) == 1 for k in need), ' '.join(k for k in need if len(seen.get(k, [])) != 1))
    ck('알 수 없는 필드 없음', not extra, ' '.join(extra))
    for k in n3.PIN:
        v = (seen.get(k) or [''])[0]
        ck('타입 ' + k, re.fullmatch(TYPE[k], v) is not None, v)
    for k in n3.LOGKEYS:
        v = (seen.get('logcount:' + k) or [''])[0]
        ck('타입 logcount ' + k, re.fullmatch(r'[0-9]+', v) is not None, v)
    for k, e in n3.PIN.items():
        v = (seen.get(k) or [''])[0]
        ck('설치 핀 ' + k, v == e, '' if v == e else v)
    out.append('로그 집계(보고값, 0 으로 바꾸지 않음): ' + ' · '.join('%s=%s' % (k, (seen.get('logcount:' + k) or ['?'])[0]) for k in n3.LOGKEYS))
    out.append('FORMAT %s 항목 %d 실패 %d' % ('OK' if bad == 0 else 'NG', sum(1 for x in out if x[:4] in ('PASS', 'FAIL')), bad))
    print('\n'.join(out)); return 0 if bad == 0 else 1
if __name__ == '__main__':
    try: sys.exit(main(sys.argv[1]))
    except Exception as x: print('READ 실패 %s' % type(x).__name__); sys.exit(2)
