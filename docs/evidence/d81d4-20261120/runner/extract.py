# D81D4 실행 틀 추출기(총괄 v1.198 §3-6) — v172u2c-20261116/runner/extract.py 에서 출처·블록 목록만 바꿨다.
# 사용: python -I extract.py <출력 폴더(없는 경로)> <D81D4 고정 출처 커밋 40자>
#   F·G: 447차 고정 커밋 7c62a7db 의 RUNBOOK 첫 bash 블록(F, 현재 운영 NEW 핀)·보존본 G 블록(해시로 찾음).
#   U 9블록: 출처 커밋의 docs/evidence/d81d4-20261120/d4/blocks-d81/*.sh — 그 커밋의 blocks-d81.sha256 과 아래 고정값이 모두 같아야 한다.
#   UF2·U2확인 은 이 판에 없다. 옛 V172D4 블록 이름이 출처에 섞여 있으면 거절한다.
# 하나라도 다르면 종료 1. 출력 폴더가 이미 있으면 멈춘다.
import hashlib, json, pathlib, re, subprocess, sys
out, SRC = pathlib.Path(sys.argv[1]), sys.argv[2]
FGC = '7c62a7db73e236e1664745e1fb0037ba45516182'
if not re.fullmatch(r'[0-9a-f]{40}', SRC):
    print('출처는 40자 커밋 SHA'); sys.exit(1)
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
D = 'docs/evidence/d81d4-20261120/d4/'
NAMES = ['U변수', 'U0', 'U1', 'U1되돌림', 'U2', 'U3', 'U4', 'U되돌림', 'U뒤관측']
EXP = {  # F·G 는 7c62a7db, U 9블록은 mkd81.py 생성(blocks-d81.sha256)
    'F': '26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': '3d2c434b310a1f1ae3d1433540580234abf69df6f24178d1432720992a288fa4',
    'U0': 'c5206db76289d9463edaa00758d5d1eab5c5c536d041fbe79fc6230735635e5a',
    'U1': '8d0ce7906fd04124c2c9e251614867e0389ab7a96e8ccb5fae2efae3b79b2af3',
    'U1되돌림': 'a272ab5862a97539233f9425d4efda2da077e26fe6828a0211cd99c2c5827c8b',
    'U2': 'caac178a9b7cae104584b91107c19ee5594eb0437d01b74e16935fe29803644f',
    'U3': 'cc414408aa11133279ad491d6815ac0f9ed521ce0ea87cc46e7a4ec4b204e573',
    'U4': '979c5d85addf7f58c92cfe31aa1759438db790995d642fb530600e0676d3b740',
    'U되돌림': '65df00d5d52430077357d9207b188de5e063f96374c33fe0c32591dca95bfe83',
    'U뒤관측': 'c748a791e87e31ad557977ce3b8c3ab3c0a82436aa4d68ffee93c07bd3d9fd31',
}
show = lambda c, p: subprocess.run(['git', '-C', REPO, 'show', c + ':' + p], capture_output=True, check=True).stdout
H = lambda b: hashlib.sha256(b).hexdigest()
def bashblocks(c, p):
    t = show(c, p).decode('utf-8').replace('\r\n', '\n')
    return [(t.count('\n', 0, m.start()) + 1, m.group(1)) for m in re.finditer(r'^```bash\n(.*?)^```\s*$', t, re.M | re.S)]
res, bad = {'source': SRC, 'fg': FGC}, 0
rb = bashblocks(FGC, 'docs/LIMITED_LIVE_TEST_RUNBOOK.md')
ga = bashblocks(FGC, 'docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md')
gi = [i for i, (ln, b) in enumerate(ga) if H(b.encode('utf-8')) == EXP['G']]
if len(gi) != 1:
    print('G 변수 블록 수 %d ≠ 1' % len(gi)); sys.exit(1)
pick = {'F': ('LIMITED_LIVE_TEST_RUNBOOK.md', 0, rb[0]), 'G': ('LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md', gi[0], ga[gi[0]])}
sums = {}
for l in show(SRC, D + 'blocks-d81.sha256').decode('utf-8').splitlines():
    h, n = l.split(None, 1); sums[n.lstrip('*').strip()] = h
ls = subprocess.run(['git', '-C', REPO, '-c', 'core.quotepath=false', 'ls-tree', '--name-only', SRC, D + 'blocks-d81/'], capture_output=True, check=True).stdout.decode('utf-8').split()
if sorted(x.rsplit('/', 1)[1] for x in ls) != sorted(n + '.sh' for n in NAMES) or sorted(sums) != sorted(n + '.sh' for n in NAMES):
    print('출처의 블록 목록이 9블록(UF2·U2확인 없음)과 다름: %s' % ls); sys.exit(1)
out.mkdir(parents=True, exist_ok=False)
for k, (f, i, (ln, body)) in pick.items():
    b = body.encode('utf-8'); (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == EXP[k]; bad += not ok
    res[k] = {'from': FGC[:8] + ':' + f, 'index': i, 'line': ln + 1, 'sha256': H(b), 'expected_match': ok}
for k in NAMES:
    b = show(SRC, D + 'blocks-d81/' + k + '.sh'); (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == EXP.get(k) == sums.get(k + '.sh'); bad += not ok
    res[k] = {'from': SRC[:8] + ':' + D + 'blocks-d81/' + k + '.sh', 'sha256': H(b), 'expected_match': ok}
if len(EXP) != 11:
    bad += 1; print('고정값 %d ≠ 11' % len(EXP))
for k, v in res.items():
    if isinstance(v, dict):
        print('%-8s %s %s' % (k, v['sha256'][:16], '같음' if v['expected_match'] else '다름 ✗'))
(out / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
sys.exit(1 if bad else 0)
