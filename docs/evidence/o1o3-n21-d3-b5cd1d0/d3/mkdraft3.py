# O1·O3 배포 초안 D2(UB=O13D2, v1135-h2-d2-1ec9104/d2/blocks-d2) → O1·O3+N21 통합 초안 D3(UB=O13N21D3). 총괄 v1.139 §107.
# 🔴 미승인·서버 실행 금지 초안. D2 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 바꾸는 것: 판 표지·기록 폴더(~/maint-o13n21)·후보 SHA·diff 경로 목록(8개)·N21 감시 스크립트 경계(U0 사전 확인, U1 체크아웃 직전·직후,
#           U되돌림 방어), 새 블록 U1되돌림(D2 의 계획서 한 줄 대신)·U5(N21 뒤 확인). jar 비교기 v2·P0·단계 표지·덮어쓰기 방지·종료값 확인은 D2 그대로.
# N21 공통 함수(fresh_idle·n21mark·n21after)는 N21 단독 v2 vars.sh 의 표지 사이 원문을 그대로 넣는다.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다).
# 사용: python -I -X utf8 mkdraft3.py <blocks-d2> <출력 폴더> <통합 NEW 40자리> <diff 경로 파일> <N21 v2 vars.sh>
import pathlib, re, sys
SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
NEW = sys.argv[3]
DIFF = [l for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').splitlines() if l]
V2 = pathlib.Path(sys.argv[5]).read_text(encoding='utf-8')
assert re.fullmatch(r'[0-9a-f]{40}', NEW) and len(DIFF) == 8
D2NEW = '1ec91046bf2bf0b7af0e6f05c04267a6c78efc21'
n7 = NEW[:7]
a = V2.index('# ── N21 공통 함수 시작')
b = V2.index('# ── N21 공통 함수 끝 ──\n') + len('# ── N21 공통 함수 끝 ──\n')
LIB = V2[a:b]


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        sys.exit('맞는 수 %d ≠ %d: %r' % (c, n, x[:90]))
    return s.replace(x, y)


UBOLD = '[ "${UB:-}" = O13D2 ] || stop "U 변수가 O1·O3 초안 D2 판이 아님 — F → G → O1·O3 초안 D2 U 변수(초안은 실행하지 않는다)"'
UBNEW = '[ "${UB:-}" = O13N21D3 ] || stop "U 변수가 O1·O3+N21 초안 D3 판이 아님 — F → G → O1·O3+N21 초안 D3 U 변수(초안은 실행하지 않는다)"'
FIDLE = ('FI=0; fresh_idle {tag} || FI=$?\n'
         '[ "$FI" = 0 ] || {{ [ "$FI" = 1 ] && stop "freshness 회차가 돌고 있다{note} — 끝난 뒤 {again}(끊지 않는다)"; '
         'stop "프로세스 조회 실패{note} — 회차 없음으로 보지 않는다. pgrep-{tag}.* 를 보고"; }}\n')
out = {}

# ── U변수
s = (SRC / 'U변수.sh').read_text(encoding='utf-8')
s = rep(s, 'M=~/maint-o13; mkdir -p "$M"; OPS=~/ops-scripts; UB=O13D2   # O1·O3 다음 배포 초안 D2(미승인·실행 금지, D1 대체 — jar 비교기 v2·최종 SHA) 판 표지 — U 블록 전부가 확인한다. 후보 1ec9104(출처 candidate/o1o3-log-1899b49).',
        'M=~/maint-o13n21; mkdir -p "$M"; OPS=~/ops-scripts; UB=O13N21D3   # O1·O3+N21 통합 배포 초안 D3(미승인·실행 금지, D2 대체 — 통합 SHA·N21 경계) 판 표지 — U 블록 전부가 확인한다. 후보 %s(출처 candidate/o1o3-n21-1899b49). D2 기록 폴더 ~/maint-o13 은 쓰지 않는다.' % n7)
s = rep(s, 'NEW=' + D2NEW, 'NEW=' + NEW)
m = re.search(r'DIFFN="\$\(printf \'%s\\n\' (.*?)\)"\n', s, re.S)
assert m, 'D2 DIFFN 없음'
s = s[:m.start()] + 'DIFFN="$(printf \'%s\\n\' ' + ' \\\n  '.join(DIFF) + ')"\n' + s[m.end():]
s = rep(s, '# 실제 로컬 bootJar 대조(O1·O3 최종 1ec9104) — 1899b49 보존 jar·1ec9104 로컬 bootJar 에서 jar 비교기 v2 로 새로 셈($PriceSnapshot 은 줄 번호만 다름)',
        '# 실제 로컬 bootJar 대조(O1·O3+N21 통합 %s) — 1899b49 보존 jar·%s 로컬 bootJar 에서 jar 비교기 v2 로 새로 셈($PriceSnapshot 은 줄 번호만 다름). 1ec9104 jar 와 항목 차이 0' % (n7, n7))
s = s.rstrip('\n') + '\n' + '''# ── N21(감시 스크립트) 고정값 — 통합 후보의 start_66.py 는 6379ee0b 와 같은 블롭이다
NF=scripts/prospective/start_66.py; NT=scripts/prospective/test_freshness_recheck_fail.py   # NT 는 새로 추적되는 시험 파일 — 서버에서 실행하지 않는다
NBOLD=460031d30ce488f4604be6eb6aea78c10db50caf; NBNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d   # start_66.py 블롭(1899b49 · 통합 후보)
NHOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b   # 파일 sha256
''' + LIB
out['U변수.sh'] = s

# ── U0: 표지·폴더·SHA·8경로 + N21 사전 확인
s = (SRC / 'U0.sh').read_text(encoding='utf-8')
s = rep(s, '[ "$M" = "$HOME/maint-o13" ] && [ "${UB:-}" = O13D2 ] && [ "$NEW" = ' + D2NEW + ' ]',
        '[ "$M" = "$HOME/maint-o13n21" ] && [ "${UB:-}" = O13N21D3 ] && [ "$NEW" = ' + NEW + ' ] && type fresh_idle > /dev/null 2>&1 && type n21mark > /dev/null 2>&1 && type n21after > /dev/null 2>&1')
s = rep(s, 'stop "변수 순서 — F 변수 → G 변수 → O1·O3 초안 D2 U 변수"', 'stop "변수 순서 — F 변수 → G 변수 → O1·O3+N21 초안 D3 U 변수"')
s = rep(s, 'stop "IMGOLD 가 sha256: 이미지 ID 가 아님 — §5-9 98 U 변수 원문이 아님(초안 D1·D2 는 실행하지 않는다)"',
        'stop "IMGOLD 가 sha256: 이미지 ID 가 아님 — 승인된 U 변수 원문이 아님(초안 D1·D2·D3 는 실행하지 않는다)"')
s = rep(s, 'stop "후보 1ec9104 없음"', 'stop "후보 %s 없음"' % n7)
s = rep(s, 'stop "diff 가 정확히 O1·O3 6개 파일이 아님(마이그레이션·설정 포함 금지)"',
        'stop "diff 가 정확히 O1·O3 6개·N21 2개, 8개 파일이 아님(마이그레이션·설정 포함 금지)"')
s = rep(s, 'touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1"\n', '''echo "== N21 감시 스크립트 사전 확인"
[ "$(git rev-parse "HEAD:$NF")" = "$NBOLD" ] && [ "$(h256 "$NF")" = "$NHOLD" ] || stop "start_66.py 가 1899b49 원문(460031d3…)과 다름"
[ ! -e "$NT" ] || stop "$NT 가 이미 있다(추적 안 된 파일) — 체크아웃이 덮어쓰지 않도록 보고"
[ "$(git rev-parse "$NEW:$NF")" = "$NBNEW" ] || stop "후보 start_66.py 블롭이 6305e512… 가 아님"
''' + FIDLE.format(tag='u0', note='', again='U0 부터') + '''n21mark u0 || stop "N21 뒤 확인에 쓸 기록(시스템 기록·실행 기록·관측 행)을 읽을 수 없다 — 배포하지 않고 보고"
cp -p "$NF" "$M/start_66.py.before"
for s in freshness_streak.json freshness_snap.json; do [ ! -f "scripts/prospective/$s" ] || cp -p "scripts/prospective/$s" "$M/$s.before"; done
touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"
''')
out['U0.sh'] = s

# ── U1: 체크아웃 직전 시각 창·작업 트리·회차 조회·기준 위치, 직후 start_66.py 원문·문법
s = rep((SRC / 'U1.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, 'stop "U0 가 30분보다 오래됨 — U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"\n',
        'stop "U0 가 30분보다 오래됨 — U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"\n'
        'MIN=$((10#$(date +%M)))\n'
        '[ "$MIN" -ge 25 ] || [ "$MIN" -lt 14 ] || stop "매시 :14~:24 는 freshness 회차 직전·직후다 — :25 이후 U0 부터(아직 바꾼 것 없음, U1-되돌림 하지 않는다)"\n'
        '[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "HEAD·작업 트리가 U0 때와 다름(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
        + FIDLE.format(tag='u1', note='(아직 바꾼 것 없음, U1-되돌림 하지 않는다)', again='U0 부터')
        + 'n21mark apply || stop "N21 기준 위치를 기록하지 못했다(아직 바꾼 것 없음, U1-되돌림 하지 않는다) — 보고"\n'
        'date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-apply.utc"\n')
s = rep(s, 'stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — U1-되돌림"\n',
        'stop "체크아웃 결과가 NEW 가 아니거나 변경 있음 — U1-되돌림"\n'
        '[ "$(h256 "$NF")" = "$NHNEW" ] && [ -e "$NT" ] || stop "start_66.py 가 통합 후보 원문(68d441ce…)과 다르거나 새 시험 파일 없음 — U1-되돌림"\n'
        'python3 -c \'import ast,sys; ast.parse(open(sys.argv[1], encoding="utf-8").read())\' "$NF" > "$M/syntax.txt" 2>&1 || { cat "$M/syntax.txt"; stop "start_66.py 문법 확인 실패 — U1-되돌림"; }   # 캐시 파일을 쓰지 않는 문법 확인\n')
s = rep(s, 'touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2"',
        'touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2. 감시 스크립트는 이미 새 판이다: 다음 :19 회차가 끝난 뒤 U5"')
out['U1.sh'] = s

for n in ('U2', 'U3', 'U4', 'U뒤관측'):
    out[n + '.sh'] = rep((SRC / (n + '.sh')).read_text(encoding='utf-8'), UBOLD, UBNEW)

# ── U되돌림: 바꾸기 전 HEAD·작업 트리·실행 이미지·cron·회차 조회 방어, 뒤에 start_66.py 원문 대조
s = rep((SRC / 'U되돌림.sh').read_text(encoding='utf-8'), UBOLD, UBNEW)
s = rep(s, 'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #26 이미지(IMGOLD)가 아님"\n',
        'IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #26 이미지(IMGOLD)가 아님"\n'
        '[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"\n'
        '{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-rollback.txt"\n'
        'H=$(head -1 "$M/git-pre-rollback.txt")\n'
        '[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 통합 후보·1899b49 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"\n'
        '[ "$(sed -n 2p "$M/git-pre-rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"\n'
        'IMGR=$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)") || stop "실행 이미지 조회 실패 — 바꾸지 않고 보고"\n'
        '[ "$IMGR" = "$IMG0" ] || { [ -s "$M/image-new.txt" ] && [ "$IMGR" = "$(cat "$M/image-new.txt")" ]; } || stop "실행 이미지가 image-before·image-new 어느 것도 아님 — 다른 작업이 있었을 수 있다. 바꾸지 않고 보고"\n'
        'crontab -l > "$M/crontab-rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"\n'
        '[ "$(h256 "$M/crontab-rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"\n'
        + FIDLE.format(tag='rb', note='(아직 바꾼 것 없음)', again='다시'))
s = rep(s, 'git checkout --quiet $OLD; [ "$(git rev-parse HEAD)" = "$OLD" ] || stop "체크아웃 실패"\n',
        'if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 1899b49 — 체크아웃하지 않는다"; fi\n'
        '[ "$(git rev-parse HEAD)" = "$OLD" ] && [ -z "$(git status --porcelain --untracked-files=no)" ] || stop "체크아웃 실패"\n'
        '[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ ! -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 새 시험 파일이 남음 — 보고"\n')
out['U되돌림.sh'] = s

# ── U1되돌림(새 블록): D2 의 계획서 한 줄(검사 없이 checkout·tag) 대신. 재기동 전에만. 재기동하지 않는다
out['U1되돌림.sh'] = '''# U1-되돌림(D3) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.
# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 latest·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 이미지/cron 불일치·프로세스 조회 실패는 바꾸지 않고 멈춘다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
''' + UBNEW + '''
[ ! -e "$M/ok-U2" ] && [ ! -e "$M/t-deploy.utc" ] || stop "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
IMG0=$(cat "$M/image-before.txt"); [ "$IMG0" = "$IMGOLD" ] || stop "image-before 가 #26 이미지(IMGOLD)가 아님"
[ -e "$M/start_66.py.before" ] || stop "U0 보존본이 없다 — 보고"
date '+%F %T %Z' | tee "$M/t-u1rollback.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-pre-u1rollback.txt"
H=$(head -1 "$M/git-pre-u1rollback.txt")
[ "$H" = "$NEW" ] || [ "$H" = "$OLD" ] || stop "HEAD 가 통합 후보·1899b49 어느 것도 아님($H) — 다른 작업 상태일 수 있다. 바꾸지 않고 보고"
[ "$(sed -n 2p "$M/git-pre-u1rollback.txt")" = "(끝)" ] || stop "추적 파일 변경이 있다 — 덮어쓰지 않고 보고"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "실행 중 backend 가 image-before 가 아님 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"
crontab -l > "$M/crontab-u1rollback.txt" || stop "crontab 조회 실패 — 바꾸지 않고 보고"
[ "$(h256 "$M/crontab-u1rollback.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고"
''' + FIDLE.format(tag='u1rb', note='(아직 바꾼 것 없음)', again='다시') + '''if [ "$H" = "$NEW" ]; then git checkout --quiet $OLD; else echo "HEAD 가 이미 1899b49 — 체크아웃하지 않는다"; fi
docker tag "$IMG0" $REPO:latest
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; docker image inspect --format '{{.Id}}' $REPO:latest; } | tee "$M/git-u1rollback.txt"
[ "$(head -1 "$M/git-u1rollback.txt")" = "$OLD" ] && [ "$(sed -n 2p "$M/git-u1rollback.txt")" = "(끝)" ] && [ "$(sed -n 3p "$M/git-u1rollback.txt")" = "$IMG0" ] || stop "되돌림 결과가 1899b49·latest=image-before 가 아님 — 보고"
[ "$(h256 "$NF")" = "$NHOLD" ] && cmp -s "$NF" "$M/start_66.py.before" && [ ! -e "$NT" ] || stop "start_66.py 가 U0 보존본과 다르거나 새 시험 파일이 남음 — 보고"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG0" ] || stop "실행 중 backend 가 바뀌었다 — 보고"
rm -f "$M/ok-U1"; echo "↩ U1-되돌림 끝 — HEAD 1899b49·latest = image-before, 재기동 없음. 상태 파일(streak·snap·log)은 되감지 않았다"
)
'''

# ── U5(새 블록): N21 뒤 확인 — 읽기 전용, U1 체크아웃 뒤 첫 :19 회차가 끝난 다음
out['U5.sh'] = '''# U5. N21 감시 스크립트 뒤 확인 — 읽기 전용. U1 체크아웃 뒤 첫 :19 회차가 끝난 다음 쓴다(U2~U4 와 따로, 회차가 길면 끝날 때까지).
# 회차를 손으로 돌리거나 실패를 유도하지 않는다. 통과 조건(구간 = U1 이 체크아웃 직전에 기록한 위치 뒤): cron 시작 줄 ≥ 1, 새 관측 행 ≥ 1,
# 새 Traceback 0, JSON 칸 같음, N21 상태 0. 기록을 못 읽거나 근거가 하나라도 없으면 '미확인'으로 멈춘다. 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다.
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
''' + UBNEW + '''
[ -e "$M/ok-U1" ] && [ -e "$M/mark-apply.txt" ] || stop "U1 미통과(또는 U1-되돌림 함)"
[ ! -e "$M/t-rollback.utc" ] || stop "U되돌림을 했다 — U5 는 하지 않는다"
''' + FIDLE.format(tag='u5', note='', again='다시') + '''date '+%F %T %Z' | tee "$M/t-u5.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-u5.txt"
[ "$(head -1 "$M/git-u5.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-u5.txt")" = "(끝)" ] || stop "HEAD 가 통합 후보가 아니거나 변경 있음"
[ "$(h256 "$NF")" = "$NHNEW" ] || stop "start_66.py 가 통합 후보 원문과 다름"
NA=0; n21after apply > "$M/n21after-u5.txt" 2>&1 || NA=$?
cat "$M/n21after-u5.txt"
[ "$NA" = 0 ] || stop "N21 적용 뒤 근거 미확인 또는 이상(종료 $NA) — n21after-u5.txt 를 보고(회차를 손으로 돌리지 않는다)"
crontab -l > "$M/crontab-u5.txt" || stop "crontab 조회 실패"
[ "$(h256 "$M/crontab-u5.txt")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름 — 보고"
if [ -e "$M/ok-U2" ]; then EXP=$(cat "$M/image-new.txt"); else EXP=$(cat "$M/image-before.txt"); fi
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$EXP" ] || stop "실행 이미지가 이 단계의 기대(image-before 또는 image-new)와 다름 — 보고"
echo "✅ U5 — 적용 뒤 cron 시작 줄·새 관측 행·새 Traceback 0 확인(시작 줄과 행의 1:1 짝은 미확정). 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다"
)
'''

DST.mkdir(exist_ok=False)
for k, v in out.items():
    for bad in ('O13D2', D2NEW, 'maint-o13"', 'maint-o13;', '초안 D2 판', 'O1·O3 6개 파일이 아님'):
        if bad in v:
            sys.exit('남은 D2 표기 %r: %s' % (bad, k))
    (DST / k).write_bytes(v.encode('utf-8'))
print('완료', len(out), '블록 →', DST)
