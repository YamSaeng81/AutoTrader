"""D81D4R pf3ok 파일 입력 시험(총괄 v1.199 §3). 합성 텍스트만 — SQL·DB·제품 시험 없음.
사용: python -I -X utf8 pf3_cases.py <U변수.sh> <새 작업 폴더> <결과 json>"""
import json, pathlib, re, subprocess, sys
uv, work, outp = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
work.mkdir(parents=True, exist_ok=False)
code = re.search(r"pf3ok\(\) \{ python3 - \"\$1\" <<'PYEOF'\n(.*?)\nPYEOF", uv.read_text(encoding='utf-8'), re.S)[1]
(work / 'pf3.py').write_text(code, encoding='utf-8', newline='\n')
P = ['LIVE | 0', 'DYNAMIC | 0', 'LIVE | 0', 'DYNAMIC | 0']
V = ['10', '1', '0', '100', '1', '1', '99', '1', '0', '0', 'LIVE', '1', '2026-11-01 00:00:00+00', '-1', 'STOP_LOSS', '1']
NT = ['11', '0', '0', '', '', '0', '', '', '0', '1', 'DYNAMIC', '93', '', '', 'ENTRY_REJECTED', '1']
def row(base, **kv):
    r = base.copy()
    for k, v in kv.items():
        r[int(k[1:])] = v
    return ' | '.join(r)
C = [  # (이름, 내용 bytes 또는 None=파일 없음, 기대 종료, 출력에 없어야 할 원문)
    ('정상-R3없음', P, 0, None), ('정상-확정', P + [row(V)], 0, None), ('정상-무거래', P + [row(NT)], 0, None),
    ('정상-확정+무거래', P + [row(V), row(NT)], 0, None),
    ('판정불가-매도수량<매수', P + [row(V, c7='0.5')], 1, None), ('판정불가-원가NULL', P + [row(V, c3='')], 1, None),
    ('판정불가-매도순대금NULL(총괄반례4)', P + [row(V, c6='')], 1, None), ('판정불가-R1', ['LIVE | 1'] + P[1:], 1, None),
    ('판정불가-무거래0+체결0', P + [row(NT, c9='0')], 1, None),
    ('반례-원가not-a-number', P + [row(V, c3='not-a-number')], 3, 'not-a-number'),
    ('반례-매도수량Infinity', P + [row(V, c7='Infinity')], 3, 'Infinity'),
    ('반례-무거래플래그2', P + [row(V, c9='2')], 3, None),
    ('원가NaN', P + [row(V, c3='NaN')], 3, 'NaN'), ('매수수량음수', P + [row(V, c4='-1')], 3, None),
    ('매도수량음수', P + [row(V, c7='-0.5')], 3, None), ('지수표기', P + [row(V, c3='1e3')], 3, '1e3'),
    ('플래그빈칸', P + [row(V, c9='')], 3, None), ('플래그01', P + [row(V, c9='01')], 3, None),
    ('플래그-1', P + [row(V, c9='-1')], 3, None),
    ('무거래1+원가형식이상', P + [row(NT, c3='abc')], 3, 'abc'), ('무거래1+개수0인데합계', P + [row(NT, c4='1')], 3, None),
    ('원가미상수>체결수', P + [row(V, c2='2')], 3, None), ('R2-Infinity', P[:2] + ['LIVE | Infinity'] + P[3:], 3, 'Infinity'),
    ('CRLF줄끝', None, 3, None), ('UTF-8아님', b'LIVE | 0\n\xff\xfe\n', 3, None), ('파일없음', 'MISSING', 3, None),
]
res, ok = [], 0
for name, body, want, leak in C:
    f = work / (re.sub(r'[^0-9A-Za-z가-힣-]', '_', name) + '.txt')
    if body == 'MISSING':
        pass
    elif isinstance(body, bytes):
        f.write_bytes(body)
    elif body is None:
        f.write_bytes(('\r\n'.join(P + [row(V)]) + '\r\n').encode())
    else:
        f.write_bytes(('\n'.join(body) + '\n').encode('utf-8'))
    r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(work / 'pf3.py'), str(f)], capture_output=True)
    out = r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')
    good = r.returncode == want and (leak is None or leak not in out) and not r.stderr
    ok += good
    res.append({'case': name, 'want': want, 'rc': r.returncode, 'pass': good, 'out': out})
    print('%s %s — 종료 %d(기대 %d)%s' % ('PASS' if good else 'FAIL', name, r.returncode, want, '' if good else ' ' + out[-200:]))
outp.write_text(json.dumps({'u_var': str(uv.name), 'cases': res, 'pass': ok, 'fail': len(C) - ok}, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
print('== pf3ok PASS %d · FAIL %d' % (ok, len(C) - ok)); sys.exit(0 if ok == len(C) else 1)
