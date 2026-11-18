"""초안(dec-dynamic-path-20261118) 인용 시험 이름 → 417차 고정 회귀 원문 XML 결과 연결.
- 초안은 Git 고정 커밋의 블롭에서 읽는다(작업 파일 아님).
- 중괄호 묶음 Prefix{A,B}Suffix 는 클래스별로 펼친다. 펼친 뒤 조각(예: SessionCreationTest)은 따로 세지 않는다.
- 와일드카드 Prefix*(N) 은 고정 회귀 원문 클래스 목록으로 펼치고 표기 개수 N 과 대조한다.
- 이름은 중복 없이 한 번만 센다. PG 건너뜀은 성공으로 바꾸지 않는다.
사용: python -I link_tests.py <repo_root> <draft_commit>
"""
import re, subprocess, sys, glob, os, hashlib
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8', newline='\n')
repo, commit = sys.argv[1], sys.argv[2]
path = 'docs/evidence/dec-dynamic-path-20261118/README.md'
blob = subprocess.check_output(['git', '-C', repo, 'rev-parse', f'{commit}:{path}']).decode().strip()
text = subprocess.check_output(['git', '-C', repo, 'cat-file', '-p', blob]).decode('utf-8')

run = os.path.join(repo, 'docs/evidence/v168-final-20261115/runs/full-0e1d2a02/xml')
idx = {}
for f in glob.glob(os.path.join(run, '*', 'TEST-*.xml')):
    cls = os.path.basename(f)[5:-4].rsplit('.', 1)[-1]
    idx.setdefault(cls, []).append(f)

names = []
brace = re.compile(r'\b([A-Z][A-Za-z0-9]*)\{([A-Za-z0-9,]+)\}([A-Za-z0-9]*Test)\b')
for m in brace.finditer(text):
    for part in m.group(2).split(','):
        names.append((m.group(1) + part + m.group(3), 'brace:' + m.group(0)))
stripped = brace.sub(' ', text)

wild = re.compile(r'\b([A-Z][A-Za-z0-9]*)\*\((\d+)\)')
wild_notes = []
for m in wild.finditer(stripped):
    hits = sorted(c for c in idx if c.startswith(m.group(1)) and c.endswith('Test'))
    ok = '일치' if len(hits) == int(m.group(2)) else '불일치'
    wild_notes.append(f'{m.group(0)} → 원문 {len(hits)}개(표기 {m.group(2)}개, {ok}): {", ".join(hits)}')
    for c in hits:
        names.append((c, 'wild:' + m.group(0)))
stripped = wild.sub(' ', stripped)

for m in re.finditer(r'\b([A-Z][A-Za-z0-9]*Test)\b', stripped):
    names.append((m.group(1), 'plain'))

seen, uniq = set(), []
for n, src in names:
    if n not in seen:
        seen.add(n)
        uniq.append((n, src))

print(f'draft {path} @ {commit} blob {blob}')
print(f'run {os.path.relpath(run, repo).replace(os.sep, "/")} (0e1d2a02, H2 기본·PG 환경 변수 끔)')
for w in wild_notes:
    print('와일드카드', w)
print('이름 | 출처 | tests/fail/err/skip | 판정 | 원문 XML sha256(앞12)')
agg = {}
tot = {'tests': 0, 'skipped': 0, 'failures': 0, 'errors': 0}
for n, src in uniq:
    fs = idx.get(n, [])
    if len(fs) != 1:
        verdict, cnt, h = ('원문 없음' if not fs else f'중복 {len(fs)}'), '-', '-'
    else:
        r = ET.parse(fs[0]).getroot()
        t, fa, e, s = (int(r.get(k)) for k in ('tests', 'failures', 'errors', 'skipped'))
        for k, v in zip(('tests', 'failures', 'errors', 'skipped'), (t, fa, e, s)):
            tot[k] += v
        cnt = f'{t}/{fa}/{e}/{s}'
        h = hashlib.sha256(open(fs[0], 'rb').read()).hexdigest()[:12]
        if fa or e:
            verdict = '실패'
        elif s == t:
            verdict = '전부 건너뜀'
        elif s:
            verdict = '일부 건너뜀'
        else:
            verdict = '성공'
    agg[verdict] = agg.get(verdict, 0) + 1
    print(f'{n} | {src} | {cnt} | {verdict} | {h}')
print('집계(클래스)', agg, '고유 클래스', len(uniq))
print('집계(시험 수)', tot, '성공', tot['tests'] - tot['skipped'] - tot['failures'] - tot['errors'])
