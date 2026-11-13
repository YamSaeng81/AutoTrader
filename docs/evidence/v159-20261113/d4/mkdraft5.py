# SMA20 PAPER 후보 초안 D4(UB=SMA20PD4, sma20p-d4-af7ffa58/d4/blocks-d4) → v1.159 통합 후보 초안 D4(UB=V159D4). 총괄 v1.159(398차) §5.
# 🔴 미승인·서버 실행 금지 초안. 원본 D4 는 덮어쓰지 않는다(출력 폴더는 없어야 한다).
# 복구 기준 = 마지막 실제 운영 읽기: HEAD b5cd1d0, backend 이미지 52e12428…(#27). frontend 실행 이미지는 미정(U0 직전 읽기 전용 확인값을 총괄 판에 기입).
# 바꾸는 것: 판 표지·기록 폴더(~/maint-v159)·NEW·DIFFN(100경로)·jar 기대(RARX 62·881→895)·U0 diff 문구·U1 jar 문구,
#           Flyway 확인(U2 뒤 V96 한 줄·체크섬, U3·U4 는 96), U3 의 '마이그레이션 없어야 함'(배포는 V96 적용, 되돌림은 적용 없음).
# 더하는 것: U0 — V95 없음·V96 원문 블롭·frontend 실행 이미지=FEIMGOLD·배포 전 손실 조회 v2(고정 커밋 GS 에서 꺼내 해시 확인, '진행 가능' 아니면 중단)
#           U1 — frontend 기존 이미지 보존 태그·새 이미지 빌드(재기동 없음), U1되돌림 — frontend latest 원복
#           UF2(새 블록) — U3(deploy) 뒤 frontend 만 교체, U되돌림 — 상태 기반 복구 관문 3판(GS 에서 꺼내 해시 확인) 통과 뒤에만 frontend → backend 순 복구
# 그대로: jar 비교기 v2 내장 본문·P0 선행 검사·단계 표지·출력 덮어쓰기 방지·종료값 확인·관측 함수·감시 스크립트 확인.
# 바꾸는 곳은 모두 지정한 횟수만큼 정확히 맞아야 한다(아니면 멈춘다). 남은 원본 표기가 있으면 멈춘다.
# 사용: python -I -X utf8 mkdraft5.py <blocks-d4> <출력 폴더> <NEW 40자리> <DIFFN 파일> <jar-diff 파일> <gate.sql> <rollback_gate.sh> <preflight.sql>
import hashlib, pathlib, re, sys

SRC, DST = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
NEW = sys.argv[3]
DIFF = [l for l in pathlib.Path(sys.argv[4]).read_text(encoding='utf-8').splitlines() if l]
JD = [l for l in pathlib.Path(sys.argv[5]).read_text(encoding='utf-8').splitlines() if l]
H = {k: hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for k, p in (('gate', sys.argv[6]), ('gatesh', sys.argv[7]), ('pf', sys.argv[8]))}
assert re.fullmatch(r'[0-9a-f]{40}', NEW) and len(DIFF) == 100, (NEW, len(DIFF))
END = [l for l in JD if l.startswith('(끝) ')]
RAR = [l for l in JD if re.match(r'^(변경|추가|삭제) ', l)]
assert len(END) == 1 and len(RAR) + 1 == len(JD) and len(RAR) == 62 and END[0] == '(끝) 881 895', (len(RAR), END)
OLDNEW = 'af7ffa58960fa1392dee00397924827b4e4e6cd5'
n7 = NEW[:7]
BS, NL = chr(92), chr(10)
assert not DST.exists(), DST
DST.mkdir(parents=True)


def rep(s, x, y, n=1):
    c = s.count(x)
    if c != n:
        raise SystemExit('바꿀 곳 수 다름: %r (기대 %d, 실제 %d)' % (x[:80], n, c))
    return s.replace(x, y)


def q(s):
    return "'" + s.replace("'", "'" + BS + "''") + "'"


def common(s):
    s = s.replace('SMA20 PAPER 후보 초안 D4', 'v1.159 통합 후보 초안 D4')
    s = s.replace('SMA20PD4', 'V159D4').replace('$HOME/maint-sma20p', '$HOME/maint-v159').replace('~/maint-sma20p', '~/maint-v159')
    s = s.replace(OLDNEW, NEW).replace('af7ffa5', n7)
    return s


blocks = {p.name: p.read_text(encoding='utf-8') for p in SRC.glob('*.sh')}
assert sorted(blocks) == sorted(['U0.sh', 'U1.sh', 'U1되돌림.sh', 'U2.sh', 'U3.sh', 'U4.sh', 'U되돌림.sh', 'U뒤관측.sh', 'U변수.sh']), sorted(blocks)
out = {}

# ── U변수 ──
v = blocks['U변수.sh']
lines = v.split(NL)
assert lines[0].startswith('M=~/maint-sma20p; mkdir -p "$M"; OPS=~/ops-scripts; UB=SMA20PD4')
lines[0] = ('M=~/maint-v159; mkdir -p "$M"; OPS=~/ops-scripts; UB=V159D4   # v1.159 통합 후보(integration/v158 %s) 배포 초안 D4(미승인·실행 금지) 판 표지 — '
            'U 블록 전부가 확인한다. 원본 구조 SMA20PD4(sma20p-d4-af7ffa58), 생성기 mkdraft5.py. backend+frontend 같은 릴리스, DDL V96(새 backend 기동 때 Flyway)' % n7)
v = NL.join(lines)
i0 = v.index('DIFFN="$(printf ')
i1 = v.index('JARN="(끝) 881 883"') + len('JARN="(끝) 881 883"')
sep = ' ' + BS + NL + '  '
newvars = ('DIFFN="$(printf ' + q('%s' + BS + 'n') + ' ' + sep.join(DIFF) + ')"   # git diff --name-only OLD NEW — 100경로(시험 70·제품 Java 20·V96 1·build.gradle 1·프런트 8)' + NL
           + 'RARX="$(printf ' + q('%s' + BS + 'n') + ' ' + sep.join(q(r) for r in RAR) + ')"   # 보존 b5cd1d0.jar(a7ef759f…) 대 로컬 bootJar 4a4a04a3=802b691a 입력(0834382a…), 비교기 v2 — 변경 48·추가 14' + NL
           + 'JARN="(끝) 881 895"' + NL
           + 'V96F=web-api/src/main/resources/db/migration/V96__paper_aligned_runtime_state.sql; V96B=5b92eef03548c6bd1feaef2b35451563d5327246; V96SUM=-424836241   '
           + '# V96 원문 블롭·Flyway 체크섬(로컬 실제 java -jar 기동 두 번에서 같은 값, oldbin-v96-r1·r2)' + NL
           + 'v96() { [ "$($PSQL -At -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" = "96" ] ; }' + NL
           + 'FEIMGOLD=미정   # 실행 중 frontend 이미지 ID — 추정하지 않는다. U0 직전 읽기 전용 확인(docker inspect frontend)값을 총괄 승인 판에 기입. 미정이면 U0 가 멈춘다' + NL
           + 'GS=미정   # 관문 3판·손실 조회문 v2 를 담은 main 고정 커밋 — 근거 커밋 뒤 총괄 승인 판에 기입. 미정이면 U0·U되돌림이 멈춘다' + NL
           + 'GTP=docs/evidence/v159-20261113/rollback-gate-v3; GATESQL=%s; GATESH=%s   # 상태 기반 복구 관문 3판 원문 해시' % (H['gate'], H['gatesh']) + NL
           + 'PFP=docs/evidence/v159-20261113/d4-draft/preflight-real-loss-readonly.v2.sql; PFSUM=%s   # 배포 전 손실 조회 v2 원문 해시' % H['pf'] + NL
           + 'gsok() { case "$GS" in 미정*|"") return 1 ;; esac; git cat-file -e "$GS^{commit}" 2>/dev/null ; }')
v = v[:i0] + newvars + v[i1:]
out['U변수.sh'] = common(v)

# ── U0 ──
u = blocks['U0.sh']
u = rep(u, '"diff 가 정확히 제품 Java 5·시험 Java 7, 12개 파일이 아님(마이그레이션·설정 포함 금지)"',
        '"diff 가 정확히 기대 100경로(시험 70·제품 Java 20·V96 1·build.gradle 1·프런트 8)가 아님"')
u = rep(u, 'touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"',
        'echo "== v1.159 추가 확인 — V95 없음·V96 원문·frontend 실행 이미지·배포 전 손실 관문(읽기 전용)"' + NL
        + '[ "$(git ls-tree --name-only $NEW web-api/src/main/resources/db/migration/ | grep -cE \'/V9[5-9]__\')" = 1 ] && [ "$(git rev-parse "$NEW:$V96F")" = "$V96B" ] '
        + '|| stop "후보의 V95 이후 마이그레이션이 V96 하나·원문 5b92eef0… 가 아님(V95 포함 금지)"' + NL
        + 'case "$FEIMGOLD" in sha256:*) ;; *) stop "FEIMGOLD 가 미정 — 승인된 U 변수 원문이 아님(frontend 실행 이미지는 추정하지 않는다)" ;; esac' + NL
        + 'FE0=$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)"); echo "$FE0" | tee "$M/image-fe-before.txt"' + NL
        + '[ "$FE0" = "$FEIMGOLD" ] || stop "실행 중 frontend 이미지가 FEIMGOLD 와 다름 — 보고"' + NL
        + 'docker inspect --format \'{{.Config.Image}}\' "$($DC ps -q frontend)" | tee "$M/fe-ref-before.txt"' + NL
        + '[ -s "$M/fe-ref-before.txt" ] || stop "frontend 이미지 이름을 읽지 못함"' + NL
        + 'gsok || stop "GS(관문·조회문 고정 커밋)가 미정이거나 없음"' + NL
        + 'git show "$GS:$PFP" > "$M/preflight-loss.sql" && [ "$(h256 "$M/preflight-loss.sql")" = "$PFSUM" ] || stop "배포 전 손실 조회문 원문·해시가 다름"' + NL
        + '$PSQL -At -F \' | \' < "$M/preflight-loss.sql" > "$M/preflight-loss.txt" 2>&1 || { cat "$M/preflight-loss.txt"; stop "🔴 배포 전 손실 조회 실패 — 배포 전 중단·보고(빌드·체크아웃·재기동으로 넘어가지 않는다)"; }' + NL
        + 'cat "$M/preflight-loss.txt"' + NL
        + '[ "$(grep -c \'^판정 | \' "$M/preflight-loss.txt")" = 1 ] && grep -qE \'^판정 \\| - \\| .*진행 가능\\(손실 세 보호식 모두 허용\\)$\' "$M/preflight-loss.txt" '
        + '|| stop "🔴 배포 전 손실 관문이 진행 가능이 아님(거절·판정 불가·필수값 누락) — 배포 전 중단·보고. 한도·자본·세션·손실 행을 바꾸지 않는다"' + NL
        + 'touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"')
out['U0.sh'] = common(u)

# ── U1 ──
u = blocks['U1.sh']
u = rep(u, '"jar 차이가 기대 7줄(PAPER 상한·LIVE_ALIGNED)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"',
        '"jar 차이가 기대 62줄(변경 48·추가 14, V96 SQL 포함)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"')
u = rep(u, '"항목 수가 881 → 883 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"', '"항목 수가 881 → 895 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"')
u = rep(u, 'touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(감시 스크립트는 바뀌지 않았다)"',
        'echo "== frontend(같은 릴리스) — 기존 이미지 보존 태그·새 이미지 빌드(재기동 없음)"' + NL
        + 'FE0=$(cat "$M/image-fe-before.txt"); FEREF=$(cat "$M/fe-ref-before.txt"); FER=${FEREF%%:*}' + NL
        + 'if docker image inspect $FER:b5cd1d0 > /dev/null 2>&1; then [ "$(docker image inspect --format \'{{.Id}}\' $FER:b5cd1d0)" = "$FE0" ] || stop "$FER:b5cd1d0 태그가 이미 다른 이미지를 가리킨다 — U1-되돌림"; fi' + NL
        + 'docker tag "$FE0" $FER:b5cd1d0' + NL
        + '[ "$(docker image inspect --format \'{{.Id}}\' $FER:b5cd1d0)" = "$FE0" ] || stop "frontend 기존 이미지 보존 태그 실패 — U1-되돌림"' + NL
        + '$DC build frontend > "$M/build-fe.log" 2>&1 || stop "frontend 빌드 실패 — build-fe.log 를 보고. U1-되돌림"' + NL
        + 'FE1=$(docker image inspect --format \'{{.Id}}\' "$FEREF"); echo "$FE1" | tee "$M/image-fe-new.txt"' + NL
        + '[ "$FE1" != "$FE0" ] || stop "새 frontend 이미지가 만들어지지 않음 — U1-되돌림"' + NL
        + '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "🔴 실행 중 frontend 가 바뀌었다 — 보고"' + NL
        + 'touch "$M/ok-U1"; echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 는 U3 뒤 UF2)"')
out['U1.sh'] = common(u)

# ── U1되돌림 ──
u = blocks['U1되돌림.sh']
u = rep(u, 'docker tag "$IMG0" $REPO:latest' + NL,
        'docker tag "$IMG0" $REPO:latest' + NL
        + 'if [ -s "$M/image-fe-before.txt" ] && [ -s "$M/fe-ref-before.txt" ]; then FEREF=$(cat "$M/fe-ref-before.txt"); docker tag "$(cat "$M/image-fe-before.txt")" "$FEREF"; '
        + '[ "$(docker image inspect --format \'{{.Id}}\' "$FEREF")" = "$(cat "$M/image-fe-before.txt")" ] || stop "frontend latest 원복 실패 — 보고"; '
        + '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$(cat "$M/image-fe-before.txt")" ] || stop "실행 중 frontend 가 바뀌었다 — 보고"; fi' + NL)
out['U1되돌림.sh'] = common(u)

# ── U2 ──
u = blocks['U2.sh']
u = rep(u, 'echo "== 여기부터 재기동 — 이 뒤의 멈춤은 \'되돌림 필요\'"',
        'v94 || stop "재기동 전 Flyway 최고 버전이 94 가 아님 — 보고(아직 바꾼 것 없음)"' + NL
        + 'echo "== 여기부터 재기동 — 새 backend 기동 때 Flyway 가 V96 하나를 적용한다. 이 뒤의 멈춤은 \'되돌림 필요\'(V96 은 되돌리지 않는다)"')
u = rep(u, 'svc | tee "$M/svc-deploy.txt"',
        'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"' + NL
        + '[ "$($PSQL -At -c "SELECT string_agg(version || \':\' || success || \':\' || checksum, \',\' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 95")" = "96:t:$V96SUM" ] '
        + '|| stop "되돌림 필요: V95 이후 이력이 V96 한 줄(성공·체크섬 $V96SUM)이 아님"' + NL
        + 'svc | tee "$M/svc-deploy.txt"')
u = rep(u, 'touch "$M/ok-U2"; echo "✅ U2 통과 — 5~10분 뒤 U3"', 'touch "$M/ok-U2"; echo "✅ U2 통과 — 5~10분 뒤 U3(frontend 는 아직 구판 — 새 모의 세션을 만들지 않는다)"')
out['U2.sh'] = common(u)

# ── U3 ──
u = blocks['U3.sh']
u = rep(u, '! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "되돌림 필요: 마이그레이션이 돌았다(이번 배포에는 없어야 함)"' + NL + 'v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"',
        'if [ "$T" = rollback ]; then ! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "되돌림 뒤 마이그레이션이 돌았다 — 보고"; '
        + 'else grep -qE "Migrating schema .*96|Successfully applied 1 migration" "$M/app-$T.log" || echo "⚪ V96 적용 줄이 로그에 없음 — U2 의 Flyway 이력 확인으로 갈음(기록만)"; fi' + NL
        + 'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"')
out['U3.sh'] = common(u)

# ── U4 ──
u = blocks['U4.sh']
u = rep(u, 'v94 || stop "되돌림 필요: Flyway 최고 버전이 94 가 아님"', 'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"')
# 대역 r2 에서 찾은 결함: UF2 로 frontend 를 바꾼 뒤에는 'backend 외 서비스가 바뀜' 으로 항상 멈췄다 — frontend 는 UF2 기록과 따로 대조한다.
u = rep(u, """diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-after30.txt") || stop "되돌림 필요: backend 외 서비스가 바뀜\"""",
        """[ -e "$M/ok-UF2" ] || stop "UF2 미통과 — 같은 릴리스의 frontend 교체·확인 뒤 U4\"""" + NL
        + """diff <(grep -vE '^(backend|frontend) ' "$M/svc-before.txt") <(grep -vE '^(backend|frontend) ' "$M/svc-after30.txt") || stop "되돌림 필요: backend·frontend 외 서비스가 바뀜\"""" + NL
        + """[ "$(grep '^frontend ' "$M/svc-fe.txt")" = "$(grep '^frontend ' "$M/svc-after30.txt")" ] || stop "frontend 되돌림 필요: UF2 뒤 frontend 재시작·이미지 변경 있음\"""")
out['U4.sh'] = common(u)

# ── U되돌림: 관문 3판 → frontend → backend ──
u = blocks['U되돌림.sh']
u = rep(u, 'date \'+%F %T %Z\' | tee "$M/t-rollback.txt"; date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-rollback.utc"',
        'echo "== 상태 기반 복구 관문 3판 — 판정만(자동 되돌림 없음). 실계좌 보유·RUNNING 이 있으면 EXCH_RECON 에 승인된 읽기 블록의 대조 출력 파일을 준다"' + NL
        + 'gsok || stop "GS(관문 고정 커밋)가 미정이거나 없음 — 되돌리지 않고 보고"' + NL
        + 'git show "$GS:$GTP/gate.sql" > "$M/gate.sql" && git show "$GS:$GTP/rollback_gate.sh" > "$M/rollback_gate.sh" || stop "관문 원문을 꺼내지 못함 — 되돌리지 않고 보고"' + NL
        + '[ "$(h256 "$M/gate.sql")" = "$GATESQL" ] && [ "$(h256 "$M/rollback_gate.sh")" = "$GATESH" ] || stop "관문 원문 해시가 다름 — 되돌리지 않고 보고"' + NL
        + 'GR=0; EXCH_RECON="${EXCH_RECON:-}" PSQL="$PSQL -At" bash "$M/rollback_gate.sh" "$M/rollback-ledger" > "$M/gate-out.txt" 2>&1 || GR=$?' + NL
        + 'cat "$M/gate-out.txt"' + NL
        + '[ "$GR" = 0 ] && [ "$(grep \'^VERDICT \' "$M/gate-out.txt" | tail -1 | cut -d\' \' -f2)" = ROLLBACK_GATE_OK ] '
        + '|| stop "관문이 ROLLBACK_GATE_OK 가 아님(종료 $GR) — 구판으로 되돌리지 않는다. 현재 바이너리로 정지·유효 가격 대기·정산/정리를 유지하고 gate-out.txt 를 보고(0원 정산·행 삭제·마이그레이션 삭제 금지)"' + NL
        + 'date \'+%F %T %Z\' | tee "$M/t-rollback.txt"; date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-rollback.utc"' + NL
        + 'if [ -s "$M/image-fe-new.txt" ] && [ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$(cat "$M/image-fe-new.txt")" ]; then' + NL
        + '  FE0=$(cat "$M/image-fe-before.txt"); docker tag "$FE0" "$(cat "$M/fe-ref-before.txt")"' + NL
        + '  $DC up -d --no-build --no-deps --force-recreate frontend' + NL
        + '  sleep 10' + NL
        + '  [ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "frontend 이미지가 image-fe-before 와 다름"' + NL
        + 'else echo "frontend 는 구판 그대로 — 바꾸지 않는다"; fi')
out['U되돌림.sh'] = common(u)

out['U뒤관측.sh'] = common(blocks['U뒤관측.sh'])

# ── UF2(새 블록) ──
out['UF2.sh'] = NL.join([
    '# UF2(V159D4 새 블록) — U3(deploy) 통과 뒤 frontend 만 새 이미지로 교체한다. backend 먼저·frontend 나중이 기본안이다.',
    '# 사이 구간(구 frontend + 새 API)은 키 없는 세션 표시·정지 이동이 같다(로컬 대역 fe-compat-old A·B). 새 모드 CLOSING 은 구 화면이 0원·보유 0 으로 보이므로',
    '# 양쪽 설치·관측이 끝나기 전에는 새 모의 세션을 만들지 않는다.',
    '(',
    'set -euo pipefail',
    'stop() { echo "🔴 멈춤: $*"; exit 1; }',
    '[ "${UB:-}" = V159D4 ] || stop "U 변수가 v1.159 통합 후보 초안 D4 판이 아님 — F → G → v1.159 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"',
    '[ -e "$M/ok-U3-deploy" ] || stop "U3(deploy) 미통과 — frontend 는 backend 관측 뒤"',
    '[ ! -e "$M/t-rollback.utc" ] || stop "되돌림을 시작했다 — frontend 를 바꾸지 않는다"',
    '[ ! -e "$M/ok-UF2" ] || stop "UF2 를 이미 했다 — U4"',
    'FE0=$(cat "$M/image-fe-before.txt"); FE1=$(cat "$M/image-fe-new.txt"); FEREF=$(cat "$M/fe-ref-before.txt")',
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE0" ] || stop "실행 중 frontend 가 image-fe-before 가 아님 — 보고"',
    '[ "$(docker image inspect --format \'{{.Id}}\' "$FEREF")" = "$FE1" ] || stop "frontend 이미지 이름이 image-fe-new 를 가리키지 않음 — 보고"',
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q backend)")" = "$(cat "$M/image-new.txt")" ] || stop "backend 가 새 이미지가 아님 — 보고"',
    'sid203 || stop "SID 203·기준 행·EG·0 확인 중 다른 것 있음"',
    '[ "$(sc)" = "66 | 63 | 3 | -" ] || stop "검증 66세션 상태 다름"',
    'svc | tee "$M/svc-pre-fe.txt"',
    'date \'+%F %T %Z\' | tee "$M/t-fe.txt"; date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-fe.utc"',
    'echo "== 여기부터 frontend 재생성 — 이 뒤의 멈춤은 \'frontend 되돌림 필요\'(U되돌림의 frontend 단계)"',
    '$DC up -d --no-build --no-deps frontend || stop "frontend 되돌림 필요: 재생성 실패"',
    'sleep 10',
    '[ "$(docker inspect --format \'{{.Image}}\' "$($DC ps -q frontend)")" = "$FE1" ] || stop "frontend 되돌림 필요: 새 컨테이너 이미지가 image-fe-new 와 다름"',
    'svc | tee "$M/svc-fe.txt"',
    'diff <(grep -v \'^frontend \' "$M/svc-pre-fe.txt") <(grep -v \'^frontend \' "$M/svc-fe.txt") || stop "frontend 외 서비스가 바뀜 — 보고"',
    'C=$(curl -s -o /dev/null -w \'%{http_code}\' http://localhost:3000/) || C=000; echo "frontend / 응답 $C" | tee "$M/fe-http.txt"',
    'case "$C" in 200|302|307|308) ;; *) stop "frontend 되돌림 필요: / 응답 $C" ;; esac',
    '$DC logs --no-color --since "$(cat "$M/t-fe.utc")" frontend > "$M/frontend-fe.log"',
    '! grep -qiE "error|unhandled" "$M/frontend-fe.log" || { grep -iE "error|unhandled" "$M/frontend-fe.log" | cut -c1-200; stop "frontend 로그에 오류 줄 — 바로 되돌리지 말고 위 줄을 보고"; }',
    'touch "$M/ok-UF2"; echo "✅ UF2 통과 — 화면 확인(목록·상세 표시) 뒤 U4. 새 모의 세션 생성은 별도 승인"',
    ')',
    ''])

for name, text in out.items():
    for bad in ('SMA20PD4', 'maint-sma20p', OLDNEW, 'af7ffa5', '881 883', '7줄(PAPER'):
        assert bad not in text, (name, bad)
    (DST / name).write_text(text, encoding='utf-8', newline='\n')
print(len(out), '블록', H)
