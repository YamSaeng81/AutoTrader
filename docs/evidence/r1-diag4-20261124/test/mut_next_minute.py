"""pg-r1d4 회차 mutations/bad_next_minute 보충(PG 재실행 없음).
원 회차의 변형값 2026-12-01T04:50:00 은 실제 정상값과 같아 입력이 바뀌지 않았다(changed=false, 시험기 결함 — 원문 보존).
여기서는 보존된 runs/pg-r1d4/normal/out.tsv 를 읽어 EXP DYNAMIC KNOWN 의 next_minute 만 1분 뒤로 바꾼 파일을 검사기에 넣는다.
사용: python -I mut_next_minute.py --out <새 폴더(없어야 함)>   종료값: 기대대로(변경됨·rc7·SHAPE_MISMATCH·stderr 없음) 0, 아니면 1.
"""
import argparse, hashlib, json, os, subprocess, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'runs', 'pg-r1d4', 'normal', 'out.tsv')
CHK = os.path.join(ROOT, 'check', 'shape_check4.py')
ap = argparse.ArgumentParser()
ap.add_argument('--out', required=True)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=False)
base = open(SRC, 'rb').read().decode('utf-8')
out, hit = [], 0
for ln in base.split('\n'):
    f = ln.split('\t')
    if f[:3] == ['EXP', 'DYNAMIC', 'KNOWN']:
        old = f[6]
        f[6] = old[:14] + '%02d' % (int(old[14:16]) + 1) + old[16:]   # 분 +1 (정상값 04:50 → 04:51)
        hit += 1
    out.append('\t'.join(f))
txt = '\n'.join(out)
p = os.path.join(a.out, 'bad_next_minute.tsv')
open(p, 'wb').write(txt.encode('utf-8'))
c = subprocess.run([sys.executable, '-I', CHK, p], capture_output=True)
for n, b in (('stdout', c.stdout), ('stderr', c.stderr), ('rc', f'{c.returncode}\n'.encode())):
    open(os.path.join(a.out, f'bad_next_minute.{n}'), 'wb').write(b)
r = {'source_sha256': hashlib.sha256(base.encode('utf-8')).hexdigest(), 'rows_changed': hit, 'changed': txt != base,
     'rc7': c.returncode == 7, 'shape_mismatch_next_minute': c.stdout.decode('utf-8').startswith('SHAPE_MISMATCH') and 'next_minute' in c.stdout.decode('utf-8'),
     'no_traceback': c.stderr == b''}
r['ok'] = hit == 1 and all(v for k, v in r.items() if k not in ('source_sha256', 'rows_changed'))
open(os.path.join(a.out, 'summary.json'), 'wb').write((json.dumps(r, ensure_ascii=False, indent=1) + '\n').encode('utf-8'))
sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))
print(json.dumps(r, ensure_ascii=False, indent=1))
sys.exit(0 if r['ok'] else 1)
