# O1·O3+N21 통합 초안 D3(UB=O13N21D3, o1o3-n21-d3-b5cd1d0/d3/blocks-d3 — 운영에 적용·수용된 판) → SMA20 PAPER 후보 초안 D4(UB=SMA20PD4).
# 총괄 v1.144 H. 🔴 미승인·서버 실행 금지 초안. D3 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 복구 기준 = 지금 운영: HEAD b5cd1d0, 이미지 52e12428…(~/maint-o13n21/image-new.txt). 새 보존 태그 autotrader-backend:b5cd1d0.
# 바꾸는 것: 판 표지·기록 폴더(~/maint-sma20p)·OLD/NEW·IMGOLD·이전 복구 태그 사슬(+1899b49 → 426a09a2…)·diff 경로 목록·jar 기대(RARX·항목 수)·
#           감시 스크립트 확인(D4 는 start_66.py·시험 파일을 바꾸지 않는다 — 전후 같은 블롭·파일을 확인), 문구의 1899b49/#26 → b5cd1d0/#27.
# 빼는 것: U5(N21 뒤 확인)와 n21mark 호출 — 감시 스크립트가 바뀌지 않으므로 대상이 없다. fresh_idle·:14~:24 창은 그대로 둔다(보수적).
# 그대로: jar 비교기 v2 내장 본문·P0 선행 검사·단계 표지·출력 덮어쓰기 방지·종료값 확인·관측 함수·U2~U4·U뒤관측 본문.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다). 남은 D3 표기가 있으면 멈춘다.
# 사용: python -I -X utf8 mkdraft4.py <blocks-d3> <출력 폴더> <NEW 40자리> <diff 경로 파일> <jar-diff 파일(변경/추가/삭제 줄 + (끝) a b)>
import pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
NEW = sys.argv[3]
DIFF = [l for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').splitlines() if l]
JD = [l for l in pathlib.Path(sys.argv[5]).read_text(encoding='utf-8').splitlines() if l]
assert re.fullmatch(r'[0-9a-f]{40}', NEW) and len(DIFF) == 12
END = [l for l in JD if l.startswith('(끝) ')]
RAR = [l for l in JD if re.match(r'^(변경|추가|삭제) ', l)]
assert len(END) == 1 and len(RAR) + 1 == len(JD), JD
NA, NB = END[0].split()[1:3]
n7 = NEW[:7]
D3NEW = 'b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88'
D3OLD = '1899b49f633f8aefdc66b15c42debdaba8b3852c'
IMG27 = 'sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055'
IMG26 = 'sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1'
NPROD, NTEST = sum('/src/main/' in d for d in DIFF), sum('/src/test/' in d for d in DIFF)


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, x[:100]))
    return s.replace(x, y)


UBOLD = '[ "${UB:-}" = O13N21D3 ] || stop "U 변수가 O1·O3+N21 초안 D3 판이 아님 — F → G → O1·O3+N21 초안 D3 U 변수(초안은 실행하지 않는다)"'
UBNEW = '[ "${UB:-}" = SMA20PD4 ] || stop "U 변수가 SMA20 PAPER 후보 초안 D4 판이 아님 — F → G → SMA20 PAPER 후보 초안 D4 U 변수(초안은 실행하지 않는다)"'
NTCHK_OLD = '[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ ! -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 새 시험 파일이 남음 — 보고"'
NTCHK_NEW = '[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 시험 파일이 없다 — 보고"'
out = {}

# ── U변수
s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
first, rest = s.split('\n', 1)
assert first.startswith('M=~/maint-o13n21; mkdir -p "$M"; OPS=~/ops-scripts; UB=O13N21D3   #')
s = ('M=~/maint-sma20p; mkdir -p "$M"; OPS=~/ops-scripts; UB=SMA20PD4   # SMA20 PAPER 1회 상한·LIVE_ALIGNED 선택 모드 후보 배포 초안 D4'
     '(미승인·실행 금지) 판 표지 — U 블록 전부가 확인한다. 후보 %s(출처 candidate/sma20-paper-fwd-b5cd1d0). 복구 기준은 지금 운영 b5cd1d0·#27 이미지. '
     'D3 기록 폴더 ~/maint-o13n21·#26 ~/maint-n20 등 이전 폴더는 읽기만 하고 덮어쓰지 않는다\n' % n7) + rest
s = rep(s, 'OLD=%s; NEW=%s' % (D3OLD, D3NEW), 'OLD=%s; NEW=%s' % (D3NEW, NEW))
m = re.search(r'^IMGOLD=sha256:426a09a2[^\n]*\n', s, re.M)
assert m
s = s[:m.start()] + (
    'IMGOLD=%s   # #27 O1·O3+N21 통합 배포 이미지(~/maint-o13n21/image-new.txt, v1.140 수용 값) — U0 가 실행 이미지·기록 파일과 같은지 확인한다. sha256: 로 시작하지 않으면 U0 가 멈춘다\n'
    'IMGPREV8=%s   # #26 이미지 — 이전 복구 태그 autotrader-backend:1899b49 가 가리킨다(D3 U1 이 만듦, 보존, 바꾸지 않는다)\n' % (IMG27, IMG26)) + s[m.end():]
m = re.search(r'DIFFN="\$\(printf \'%s\\n\' (.*?)\)"\n', s, re.S)
assert m
s = s[:m.start()] + 'DIFFN="$(printf \'%s\\n\' ' + ' \\\n  '.join(DIFF) + ')"\n' + s[m.end():]
m = re.search(r'RARX="\$\(printf \'%s\\n\' .*?\)"[^\n]*\n', s, re.S)
assert m
s = s[:m.start()] + ('RARX="$(printf \'%s\\n\' ' + ' \\\n  '.join("'" + r + "'" for r in RAR) + ')"'
                     '   # 실제 로컬 bootJar 대조(후보 %s) — 보존 b5cd1d0 jar(a7ef759f…) 대비 jar 비교기 v2 로 셈. 항목 %s → %s\n' % (n7, NA, NB)
                     + 'JARN="(끝) %s %s"\n' % (NA, NB)) + s[m.end():]
s = rep(s, '# ── N21(감시 스크립트) 고정값 — 통합 후보의 start_66.py 는 6379ee0b 와 같은 블롭이다',
        '# ── 감시 스크립트 고정값 — 운영 b5cd1d0 과 후보의 start_66.py·시험 파일은 같은 블롭이다(D4 는 감시 스크립트를 바꾸지 않는다)')
s = rep(s, 'NBOLD=460031d30ce488f4604be6eb6aea78c10db50caf; NBNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d   # start_66.py 블롭(1899b49 · 통합 후보)',
        'NBOLD=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d; NBNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d   # start_66.py 블롭(운영 b5cd1d0 · 후보 — 같다)')
s = rep(s, 'NHOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b',
        'NHOLD=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b')
out['U변수.sh'] = s

# ── U0
s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "$M" = "$HOME/maint-o13n21" ] && [ "${UB:-}" = O13N21D3 ] && [ "$NEW" = %s ]' % D3NEW,
        '[ "$M" = "$HOME/maint-sma20p" ] && [ "${UB:-}" = SMA20PD4 ] && [ "$NEW" = %s ] && [ -n "${JARN:-}" ]' % NEW)
s = rep(s, 'stop "변수 순서 — F 변수 → G 변수 → O1·O3+N21 초안 D3 U 변수"', 'stop "변수 순서 — F 변수 → G 변수 → SMA20 PAPER 후보 초안 D4 U 변수"')
s = rep(s, '(초안 D1·D2·D3 는 실행하지 않는다)', '(초안 D1~D4 는 실행하지 않는다)')
s = rep(s, 'stop "HEAD 가 1899b49 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 b5cd1d0 가 아니거나 추적 파일 변경 있음"')
s = rep(s, 'stop "후보 b5cd1d0 없음"', 'stop "후보 %s 없음"' % n7)
s = rep(s, 'stop "1899b49 가 후보의 조상이 아님"', 'stop "b5cd1d0 가 후보의 조상이 아님"')
s = rep(s, 'stop "diff 가 정확히 O1·O3 6개·N21 2개, 8개 파일이 아님(마이그레이션·설정 포함 금지)"',
        'stop "diff 가 정확히 제품 Java %d·시험 Java %d, %d개 파일이 아님(마이그레이션·설정 포함 금지)"' % (NPROD, NTEST, len(DIFF)))
s = rep(s, '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-n20/image-new.txt)" ] || stop "실행 이미지가 #26 배포 이미지(~/maint-n20/image-new.txt)와 다름"',
        '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-o13n21/image-new.txt)" ] || stop "실행 이미지가 #27 배포 이미지(~/maint-o13n21/image-new.txt)와 다름"')
s = rep(s, 'stop "이전 복구 태그 $REPO:ee509cf 이 #25 이미지(200f2f57…)가 아님 — 보고"\n',
        'stop "이전 복구 태그 $REPO:ee509cf 이 #25 이미지(200f2f57…)가 아님 — 보고"\n'
        '[ "$(docker image inspect --format \'{{.Id}}\' $REPO:1899b49)" = "$IMGPREV8" ] || stop "이전 복구 태그 $REPO:1899b49 이 #26 이미지(426a09a2…)가 아님 — 보고"\n')
s = rep(s, '1899b49 실행 중 로그', 'b5cd1d0 실행 중 로그', 4)
a = s.index('echo "== N21 감시 스크립트 사전 확인"\n')
b = s.index('cp -p "$NF" "$M/start_66.py.before"\n')
old_n21 = s[a:b]
assert 'n21mark u0' in old_n21 and 'fresh_idle u0' in old_n21
s = s[:a] + ('echo "== 감시 스크립트 불변 확인(D4 는 start_66.py·시험 파일을 바꾸지 않는다)"\n'
             '[ "$(git rev-parse "HEAD:$NF")" = "$NBOLD" ] && [ "$(h256 "$NF")" = "$NHOLD" ] || stop "start_66.py 가 b5cd1d0 원문(68d441ce…)과 다름"\n'
             '[ -e "$NT" ] && [ "$(git rev-parse "HEAD:$NT")" = "$(git rev-parse "$NEW:$NT")" ] && [ "$(git rev-parse "$NEW:$NF")" = "$NBNEW" ] || stop "후보의 start_66.py·시험 파일 블롭이 운영과 다름"\n'
             'FI=0; fresh_idle u0 || FI=$?\n'
             '[ "$FI" = 0 ] || { [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다 — 끝난 뒤 U0 부터(끊지 않는다)"; stop "프로세스 조회 실패 — 회차 없음으로 보지 않는다. pgrep-u0.* 를 보고"; }\n') + s[b:]
out['U0.sh'] = s

# ── U1
s = rep((SRC / 'U1.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, 'n21mark apply || stop "N21 기준 위치를 기록하지 못했다(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n', '')
s = rep(s, '$REPO:1899b49', '$REPO:b5cd1d0', 5)
s = rep(s, 'stop "start_66.py 가 통합 후보 원문(68d441ce…)과 다르거나 새 시험 파일 없음 — U1-되돌림"',
        'stop "start_66.py 가 운영·후보 같은 원문(68d441ce…)과 다르거나 시험 파일 없음 — U1-되돌림"')
s = rep(s, 'stop "jar 차이가 O1·O3 3줄이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"',
        'stop "jar 차이가 기대 %d줄(PAPER 상한·LIVE_ALIGNED)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"' % len(RAR))
s = rep(s, "grep -qx '(끝) 881 881' \"$M/jar-diff.txt\" || stop \"항목 수가 881 → 881 가 아님",
        "grep -qxF \"$JARN\" \"$M/jar-diff.txt\" || stop \"항목 수가 %s → %s 가 아님" % (NA, NB))
s = rep(s, '✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2. 감시 스크립트는 이미 새 판이다: 다음 :19 회차가 끝난 뒤 U5', '✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(감시 스크립트는 바뀌지 않았다)')
out['U1.sh'] = s

for n in ('U2', 'U4', 'U뒤관측'):
    out[n + '.sh'] = rep((SRC / (n + '.sh')).read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep((SRC / 'U3.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
out['U3.sh'] = rep(s, '1899b49 복귀 확인', 'b5cd1d0 복귀 확인')

# ── U되돌림
s = rep((SRC / 'U되돌림.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, 'stop "image-before 가 #26 이미지(IMGOLD)가 아님"', 'stop "image-before 가 #27 이미지(IMGOLD)가 아님"')
s = rep(s, 'HEAD 가 통합 후보·1899b49 어느 것도 아님', 'HEAD 가 후보·b5cd1d0 어느 것도 아님')
s = rep(s, 'HEAD 가 이미 1899b49 — 체크아웃하지 않는다', 'HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다')
s = rep(s, NTCHK_OLD, NTCHK_NEW)
s = rep(s, '✅ 1899b49 (#26 이미지) 로 복귀', '✅ b5cd1d0 (#27 이미지) 로 복귀')
out['U되돌림.sh'] = s

# ── U1되돌림
s = rep((SRC / 'U1되돌림.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, '# U1-되돌림(D3)', '# U1-되돌림(D4)')
s = rep(s, 'stop "image-before 가 #26 이미지(IMGOLD)가 아님"', 'stop "image-before 가 #27 이미지(IMGOLD)가 아님"')
s = rep(s, 'HEAD 가 통합 후보·1899b49 어느 것도 아님', 'HEAD 가 후보·b5cd1d0 어느 것도 아님')
s = rep(s, 'HEAD 가 이미 1899b49 — 체크아웃하지 않는다', 'HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다')
s = rep(s, 'stop "되돌림 결과가 1899b49·latest=image-before 가 아님 — 보고"', 'stop "되돌림 결과가 b5cd1d0·latest=image-before 가 아님 — 보고"')
s = rep(s, NTCHK_OLD, NTCHK_NEW)
s = rep(s, '↩ U1-되돌림 끝 — HEAD 1899b49·latest', '↩ U1-되돌림 끝 — HEAD b5cd1d0·latest')
out['U1되돌림.sh'] = s

DST.mkdir(exist_ok=False)
LEFT = ('O13N21D3', 'maint-n20/image-new', 'n21mark u0', 'n21mark apply', "'(끝) 881 881'", 'NEW=' + D3NEW, D3OLD, '초안 D3 판', '1899b49 실행 중', '통합 후보·1899b49', '#26 이미지(IMGOLD)')
for k, v in out.items():
    for bad in LEFT:
        if bad in v:
            sys.exit('남은 D3 표기 %r: %s' % (bad, k))
    if k != 'U0.sh' and k != 'U변수.sh' and '1899b49' in v:
        sys.exit('1899b49 가 남음: %s' % k)
    (DST / k).write_bytes(v.encode('utf-8'))
print('완료', len(out), '블록 →', DST, '· U5 없음 · RARX', len(RAR), '줄 ·', END[0])
