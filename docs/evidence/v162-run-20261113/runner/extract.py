# V161D4 실행 틀 추출기(총괄 v1.162 §2·§4-1). 사용: python -I extract.py <출력 폴더>
# 고정 출처 c8520c55 에서만 꺼낸다:
#   F: docs/LIMITED_LIVE_TEST_RUNBOOK.md 의 첫 ```bash 블록(b7e3fc2c…)
#   G: docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md 의 ```bash 블록 중 G 변수 한 블록(4d7ff02a…)
#   U 블록: docs/evidence/v161-20261113/d4/blocks-d4/*.sh 커밋 블롭 — 같은 커밋 blocks-d4.sha256 과 같아야 한다
# 출력 폴더가 이미 있으면 멈춘다.
import hashlib, json, pathlib, re, subprocess, sys
SRC = 'c8520c55902563bf32b3578bb8156cc2fe4e80f1'
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
D4 = 'docs/evidence/v161-20261113/d4/'
out = pathlib.Path(sys.argv[1])
show = lambda p: subprocess.run(['git', '-C', REPO, 'show', SRC + ':' + p], capture_output=True, check=True).stdout
H = lambda b: hashlib.sha256(b).hexdigest()
def bashblocks(p):
    t = show(p).decode('utf-8').replace('\r\n', '\n')
    return [(t.count('\n', 0, m.start()) + 1, m.group(1)) for m in re.finditer(r'^```bash\n(.*?)^```\s*$', t, re.M | re.S)]
exp = {'F': 'b7e3fc2c1753c2ac8f2c8f99aee56dc05b013cbce2045ca25c30d95dbe0353b6',
       'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822'}
res, bad = {'source': SRC}, 0
rb = bashblocks('docs/LIMITED_LIVE_TEST_RUNBOOK.md')
ga = bashblocks('docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md')
gi = [i for i, (ln, b) in enumerate(ga) if H(b.encode('utf-8')) == exp['G']]
if len(gi) != 1:
    print('G 변수 블록 수 %d ≠ 1' % len(gi)); sys.exit(1)
pick = {'F': ('LIMITED_LIVE_TEST_RUNBOOK.md', 0, rb[0]), 'G': ('LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md', gi[0], ga[gi[0]])}
sums = {}
for l in show(D4 + 'blocks-d4.sha256').decode('utf-8').splitlines():
    h, n = l.split(None, 1); sums[n.lstrip('*').strip()] = h
names = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'UF2', 'U4', 'U되돌림', 'U뒤관측']
out.mkdir(parents=True, exist_ok=False)
for k, (f, i, (ln, body)) in pick.items():
    b = body.encode('utf-8'); (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == exp[k]; bad += not ok
    res[k] = {'from': f, 'index': i, 'line': ln + 1, 'sha256': H(b), 'expected_match': ok}
for k in names:
    b = show(D4 + 'blocks-d4/' + k + '.sh'); (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == sums.get(k + '.sh'); bad += not ok
    res[k] = {'from': D4 + 'blocks-d4/' + k + '.sh', 'sha256': H(b), 'expected_match': ok}
if len(sums) != 10:
    bad += 1; print('blocks-d4.sha256 항목 수 %d ≠ 10' % len(sums))
for k, v in res.items():
    if isinstance(v, dict):
        print('%-8s %s %s' % (k, v['sha256'][:16], '같음' if v['expected_match'] else '다름 ✗'))
(out / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
sys.exit(1 if bad else 0)
