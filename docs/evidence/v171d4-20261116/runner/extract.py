# V171D4 실행 틀 추출기(총괄 v1.171 §4). 사용: python -I extract.py <출력 폴더> <고정 출처 커밋 40자>
# V168D4 추출기(v168-run-20261116/runner/extract.py)에서 출처를 인자로 받게 바꿨다 — 이 파일은 그 출처 커밋 안에 함께 들어가므로 SHA 를 미리 적을 수 없다.
# 대신 꺼낸 12블록의 sha256 을 아래 고정값(EXP)과 대조한다. 출처 커밋의 blocks-d4.sha256 도 EXP 와 같아야 한다. 하나라도 다르면 종료 1.
#   F: docs/LIMITED_LIVE_TEST_RUNBOOK.md 의 첫 ```bash 블록(b7e3fc2c…)
#   G: docs/LIMITED_LIVE_TEST_PLAN_ARCHIVE_20261112.md 의 ```bash 블록 중 G 변수 한 블록(4d7ff02a…)
#   U 블록: docs/evidence/v171d4-20261116/d4/blocks-d4/*.sh 커밋 블롭
# 출력 폴더가 이미 있으면 멈춘다.
import hashlib, json, pathlib, re, subprocess, sys
out, SRC = pathlib.Path(sys.argv[1]), sys.argv[2]
if not re.fullmatch(r'[0-9a-f]{40}', SRC):
    print('출처는 40자 커밋 SHA'); sys.exit(1)
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
D4 = 'docs/evidence/v171d4-20261116/d4/'
EXP = {  # F·G 는 V168D4 와 같은 원문, U 블록 10개는 V171D4(mkdraft10.py 출력)
    'F': 'b7e3fc2c1753c2ac8f2c8f99aee56dc05b013cbce2045ca25c30d95dbe0353b6',
    'G': '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822',
    'U변수': 'ede1ac55cc49c4b2eef49686e9915132dfa2a25f1c4dc904f4a129fda965a7bc',
    'U0': '6fd5b7f4fc0614c5b8ae979d3cfc80fbf00976bbac76141d08b8313a3db87ef1',
    'U1': 'd9f038555445b410c45b104c1eec61bda1fcf62d69f90e60315bfba8dd33973e',
    'U1되돌림': 'e5ff25c7e97298b3736bdd06a0aac873a853a6c614f904dfc42f319c5eb3d7da',
    'U2': 'ae0b2d774ec09c1059df5955fdc169d54cc9409cf100e2e3656c43e1ca1ba905',
    'U3': 'e50069bd22aecfb1b8a510067afba49dec82e10b18bc4e0590df4f9b14028093',
    'UF2': '18cfcd27e805aa0ea3c6861228bbf48576e0a1de656cbe6d1646ea73dd74a8ce',
    'U4': '7a4b4cf26478e2931ec92918941612537b3413c07365478d08bd0de738a2e30a',
    'U되돌림': '84ecc373a8a3cb29bf44785f95ce46526570caa4d26ccb8be10f7f6baffe766c',
    'U뒤관측': '9afe1f91358a07bd54ec7701ef0fc817bb62c158b6926c5e82fc586db05b98b0',
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
