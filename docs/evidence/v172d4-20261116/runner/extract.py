# V172D4 실행 틀 추출기(총괄 v1.172 §4-3, V171D4 추출기에서 경로·고정 해시만 바꿈). 사용: python -I extract.py <출력 폴더> <고정 출처 커밋 40자>
# V168D4 추출기(v168-run-20261116/runner/extract.py)에서 출처를 인자로 받게 바꿨다 — 이 파일은 그 출처 커밋 안에 함께 들어가므로 SHA 를 미리 적을 수 없다.
# 대신 꺼낸 12블록의 sha256 을 아래 고정값(EXP)과 대조한다. 출처 커밋의 blocks-d4.sha256 도 EXP 와 같아야 한다. 하나라도 다르면 종료 1.
#   F: docs/LIMITED_LIVE_TEST_RUNBOOK.md 의 첫 ```bash 블록(b7e3fc2c…)
#   G: docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md 의 ```bash 블록 중 G 변수 한 블록(4d7ff02a…)
#   U 블록: docs/evidence/v172d4-20261116/d4/blocks-d4/*.sh 커밋 블롭
# 출력 폴더가 이미 있으면 멈춘다.
import hashlib, json, pathlib, re, subprocess, sys
out, SRC = pathlib.Path(sys.argv[1]), sys.argv[2]
if not re.fullmatch(r'[0-9a-f]{40}', SRC):
    print('출처는 40자 커밋 SHA'); sys.exit(1)
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
D4 = 'docs/evidence/v172d4-20261116/d4/'
EXP = {  # F·G 는 V168D4 와 같은 원문, U 블록 10개는 V172D4(mkdraft11.py 출력)
    'F': 'b7e3fc2c1753c2ac8f2c8f99aee56dc05b013cbce2045ca25c30d95dbe0353b6',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': 'bc96a3a6fda61f8889b5f48a693f665dd6b893ea6a383fa07901dbe5e3f70889',
    'U0': '4f1e8151a4f7bf28ced3cb4910ef378eb184bb9d22961f7954e49c627ec41375',
    'U1': '68d06a12bde2117f074599a1268982bb9e19b1751b4d6e5debb5751def62edd1',
    'U1되돌림': '98e963b96b1fd84a28c3011c983ee8a76125bce1349bec5bc79f93e2db987332',
    'U2': '2feef9e129d07d2e9a1607c16f5cccb17f51eb3c53325dd313e5e7009a2b77e5',
    'U3': '6583c32e4d7ceef7e4575c25496eb25c2db05e43d07544855be8bd5386fd2c46',
    'UF2': 'eff1e389c5441d8b5db6d3cf2bfa1040fec9240bb961040c8b8a495c4f52ecbc',
    'U4': '8661620c7ebbfc99a660cc18590594f5dbf4ae41de93d0e2e4f0f36a1b6dd4c8',
    'U되돌림': '2003195a90c7c3c83f10dfbb1c04330ab7c4587fa1eda46b16812f3cb217b49e',
    'U뒤관측': '9e6e7f94526f11ff6d0598c505e00e39c1408f8f88bfb04c4c6ed962590adc9d',
}
show = lambda p: subprocess.run(['git', '-C', REPO, 'show', SRC + ':' + p], capture_output=True, check=True).stdout
H = lambda b: hashlib.sha256(b).hexdigest()
def bashblocks(p):
    t = show(p).decode('utf-8').replace('\r\n', '\n')
    return [(t.count('\n', 0, m.start()) + 1, m.group(1)) for m in re.finditer(r'^```bash\n(.*?)^```\s*$', t, re.M | re.S)]
res, bad = {'source': SRC}, 0
rb = bashblocks('docs/LIMITED_LIVE_TEST_RUNBOOK.md')
ga = bashblocks('docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md')
gi = [i for i, (ln, b) in enumerate(ga) if H(b.encode('utf-8')) == EXP['G']]
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
    ok = H(b) == EXP[k]; bad += not ok
    res[k] = {'from': f, 'index': i, 'line': ln + 1, 'sha256': H(b), 'expected_match': ok}
for k in names:
    b = show(D4 + 'blocks-d4/' + k + '.sh'); (out / (k + '.sh')).write_bytes(b)
    ok = H(b) == EXP[k] == sums.get(k + '.sh'); bad += not ok
    res[k] = {'from': D4 + 'blocks-d4/' + k + '.sh', 'sha256': H(b), 'expected_match': ok}
if len(sums) != 10 or len(EXP) != 12:
    bad += 1; print('blocks-d4.sha256 항목 수 %d ≠ 10 또는 고정값 %d ≠ 12' % (len(sums), len(EXP)))
for k, v in res.items():
    if isinstance(v, dict):
        print('%-8s %s %s' % (k, v['sha256'][:16], '같음' if v['expected_match'] else '다름 ✗'))
(out / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
sys.exit(1 if bad else 0)
