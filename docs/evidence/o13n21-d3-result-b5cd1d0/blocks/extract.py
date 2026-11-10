# D3 실행 틀 추출기(총괄 v1.140 §108). 사용: python -I extract.py <출력 폴더>
# 고정 출처 9aa617d 에서만 꺼낸다:
#   F(§5-8, 색인 12)·G(§5-9, 색인 27)는 docs/LIMITED_LIVE_TEST_PLAN.md 의 ```bash 블록
#   D3 열 블록은 docs/evidence/o1o3-n21-d3-b5cd1d0/d3/blocks-d3/*.sh 커밋 블롭
# F 는 v1.132 핀(eeab6c10…), G 는 342f461 이후 고정값(4d7ff02a…), D3 블록은 같은 커밋의 blocks-d3.sha256 과 같아야 한다.
# 출력 폴더가 이미 있으면 멈춘다.
import hashlib, json, pathlib, re, subprocess, sys
SRC = '9aa617d134e7bd51c2f97d16d0815cbdc6623a4e'
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
D3 = 'docs/evidence/o1o3-n21-d3-b5cd1d0/d3/'
out = pathlib.Path(sys.argv[1])
show = lambda p: subprocess.run(['git', '-C', REPO, 'show', SRC + ':' + p], capture_output=True, check=True).stdout
H = lambda b: hashlib.sha256(b).hexdigest()
t = show('docs/LIMITED_LIVE_TEST_PLAN.md').decode('utf-8').replace('\r\n', '\n')
B = [(t.count('\n', 0, m.start()) + 1, m.group(1)) for m in re.finditer(r'^```bash\n(.*?)^```\s*$', t, re.M | re.S)]
exp = {'F': 'eeab6c104eb7368298b87fe834a60534f36f2fd12bc2acc98ef7dedbd91bc76e',
       'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822'}
sums = {}
for ln in show(D3 + 'blocks-d3.sha256').decode('utf-8').splitlines():
    h, n = ln.split(None, 1)
    sums[n.lstrip('*').strip()] = h
names = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U5', 'U되돌림', 'U뒤관측']
out.mkdir(parents=True, exist_ok=False)
res, bad = {'source': SRC}, 0
for k, i in (('F', 12), ('G', 27)):
    ln, body = B[i]
    b = body.encode('utf-8')
    (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == exp[k]; bad += not ok
    res[k] = {'from': 'LIMITED_LIVE_TEST_PLAN.md', 'index': i, 'line': ln + 1, 'sha256': H(b), 'expected_match': ok}
for k in names:
    b = show(D3 + 'blocks-d3/' + k + '.sh')
    (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == sums.get(k + '.sh'); bad += not ok
    res[k] = {'from': D3 + 'blocks-d3/' + k + '.sh', 'sha256': H(b), 'expected_match': ok}
if len(sums) != 10:
    bad += 1; print('blocks-d3.sha256 항목 수 %d ≠ 10' % len(sums))
for k, v in res.items():
    if isinstance(v, dict):
        print('%-8s %s %s' % (k, v['sha256'][:16], '같음' if v['expected_match'] else '다름 ✗'))
(out / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
sys.exit(1 if bad else 0)
