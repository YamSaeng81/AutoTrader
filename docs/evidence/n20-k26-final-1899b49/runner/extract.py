# 사용: python -I extract.py <출처: 문서 파일 경로 또는 git 커밋 SHA> <출력 폴더>
# LIMITED_LIVE_TEST_PLAN.md 의 ```bash 블록 중 F(§5-8)·G(§5-9)와 §98 여덟 U 블록을 꺼내 파일로 쓰고 blocks.json 에 위치·해시를 남긴다.
# 출력 폴더가 이미 있으면 멈춘다. F 는 v1.129 새 해시(n06-blocks.json)와, U 블록은 blocks-k26 생성본과 같아야 한다.
import hashlib, json, pathlib, re, subprocess, sys
src, out = sys.argv[1], pathlib.Path(sys.argv[2])
REPO = 'D:/Claude Code/projects/crypto-auto-trader'
if re.fullmatch(r'[0-9a-f]{40}', src):
    t = subprocess.run(['git', '-C', REPO, 'show', src + ':docs/LIMITED_LIVE_TEST_PLAN.md'], capture_output=True, check=True).stdout.decode('utf-8')
else:
    t = pathlib.Path(src).read_bytes().decode('utf-8')
t = t.replace('\r\n', '\n')
B = [(t.count('\n', 0, m.start()) + 1, m.start(), m.group(1)) for m in re.finditer(r'^```bash\n(.*?)^```\s*$', t, re.M | re.S)]
sec = t.index('\n**98. ')
end = t.index('\n## 6. 시작·중단·종료 조건')
u = [i for i, b in enumerate(B) if sec < b[1] < end]
names = ['U변수', 'U0', 'U1', 'U2', 'U3', 'U4', 'U뒤관측', 'U되돌림']
if len(u) != 8:
    sys.exit('§98 블록 수 %d ≠ 8' % len(u))
pick = {'F': 12, 'G': 27}
pick.update(dict(zip(names, u)))
out.mkdir(parents=True, exist_ok=False)
res, bad = {'source': src}, 0
H = lambda s: hashlib.sha256(s.encode('utf-8')).hexdigest()
FNEW = '0975f7c0b42d8ebe32c3392e3b03ddacafd31a261ea37f9d9b9ee0d1cb82e99c'   # 총괄 v1.129 n06-blocks.json F 변수 new_sha256
GOLD = '4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822'   # 342f461 G(v1.129 에서 바뀌지 않음)
for k, i in pick.items():
    ln, _, body = B[i]
    (out / (k + '.sh')).write_bytes(body.encode('utf-8'))
    h = H(body)
    exp = FNEW if k == 'F' else GOLD if k == 'G' else hashlib.sha256(pathlib.Path('D:/tmp/n20c/blocks-k26/%s.sh' % k).read_bytes()).hexdigest()
    ok = h == exp
    bad += not ok
    res[k] = {'index': i, 'line': ln + 1, 'sha256': h, 'expected_match': ok}
    print('%-6s 색인 %3d 줄 %5d %s %s' % (k, i, ln + 1, h[:16], '같음' if ok else '다름 ✗'))
(out / 'blocks.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
sys.exit(1 if bad else 0)
