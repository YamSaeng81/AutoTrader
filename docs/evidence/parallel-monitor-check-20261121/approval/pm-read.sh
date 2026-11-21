#!/usr/bin/env bash
# 병행 감시 실제 읽기 1회 승인안 pm-read-1 (총괄 v1.215 §3-3). **미실행 초안 — 별도 승인 전 실행 금지.**
# 개발 PC 에서 실행한다. 서버에는 아무것도 쓰지 않는다(SSH 표준 출력을 로컬 보존 폴더로만 받는다). DB 는 READ ONLY 트랜잭션 + ROLLBACK.
# 두 단계: read(원격 읽기 1회, 반복 금지) → post(로컬 변환만, 서버 접근 없음).
#   bash pm-read.sh read <보존폴더> <P0 구간 시작 SINCE, UTC 예 2026-11-22T01:00:00Z>
#   bash pm-read.sh post <보존폴더> [n03-values.json]
# 보존폴더: docs/evidence/pm-read-<YYYYMMDD-HHMM>/ (없어야 함). n03-values.json 이 없으면 N03 항목은 null → 수집 불통과(정상으로 채우지 않음).
# 고정 출처: $DC·접속은 F.sh(d81d4r-20261120/runner/extract-cd8bd76f/F.sh 26d39269)와 read.sh(41e4f337)의 형식, P0 패턴은 U변수.sh(efb96108) holdobs.
set -u
EVI="$(git rev-parse --show-toplevel)/docs/evidence"
CHK="$EVI/parallel-monitor-check-20261121"
SQL="$CHK/sql/state-2.sql";             SQL_SHA=4c04efff32b096b420341b5fe10cc84475b0dc421147ae5a27eccd0f086aab94
P0T="$CHK/candidate/p0_input.py";       P0T_SHA=3fdd580a2008af692e9448ed0766f1a1a058ae3aaf17128352911f8d403d1f66
CONV="$CHK/candidate/convert_state_3.py"; CONV_SHA=a131c531c54bcab354249edcfa1fe862de78ee801dacc0cb03da18083483b2d3
UV="$EVI/d81d4r-20261120/d4/blocks-d81/U변수.sh"; UV_SHA=efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd
AP="cd ~/crypto_trader/AutoTrader && docker compose -f docker-compose.prod.yml"
stop() { echo "🔴 멈춤: $*"; exit 2; }
hashok() { [ "$(sha256sum < "$1" | cut -d' ' -f1)" = "$2" ] || stop "해시 불일치 $1"; }
hashok "$SQL" "$SQL_SHA"; hashok "$P0T" "$P0T_SHA"; hashok "$CONV" "$CONV_SHA"; hashok "$UV" "$UV_SHA"   # 경로에 D: 같은 콜론이 있어 쌍 문자열로 나누지 않는다
MODE=${1:-}; L=${2:-}
[ -n "$L" ] || stop "보존폴더 인자 없음"
case "$L" in pm-read-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) stop "보존폴더 이름 형식(pm-read-YYYYMMDD-HHMM)";; esac
L="$EVI/$L"

if [ "$MODE" = read ]; then
  SINCE=${3:-}
  [[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || stop "SINCE 형식(UTC …Z)"
  [ ! -e "$L" ] || stop "보존폴더가 이미 있음 — 같은 승인으로 다시 읽지 않는다"
  mkdir "$L" || stop "보존폴더 생성 실패"
  cp "$SQL" "$L/state-2.sql"
  date -u '+pc %FT%TZ' > "$L/run-time.txt"
  ssh -o BatchMode=yes cat-prod 'date -u "+server %FT%TZ"' </dev/null >> "$L/run-time.txt" 2>&1 || stop "SSH 실패 — 읽기 전(아무것도 읽지 않음)"
  # 0) 설치 상태(읽기만): HEAD·backend 이미지/기동 시각. 기대와 달라도 여기서 판정하지 않고 post 에서 멈춤 조건으로 본다
  ssh -o BatchMode=yes cat-prod "cd ~/crypto_trader/AutoTrader && echo \"HEAD \$(git rev-parse HEAD)\"; c=\$(docker compose -f docker-compose.prod.yml ps -q backend); docker inspect --format 'backend Image={{.Image}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}' \$c" </dev/null > "$L/install_state.txt" 2>&1
  echo $? > "$L/install_state.rc"
  # 1) 상태 SQL 1회 — 격리 PG 에서 검증한 것과 같은 psql 옵션(-X -q -v ON_ERROR_STOP=1), 입력만 표준 입력(-f -)
  ssh -o BatchMode=yes cat-prod "$AP exec -T db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -" < "$L/state-2.sql" > "$L/state.tsv" 2> "$L/state.stderr"
  echo $? > "$L/state.rc"
  # 2) P0 원천: 같은 구간 backend 로그(U3 와 같은 docker compose logs --no-color), 끝 시각은 지금
  UNTIL=$(date -u +%FT%TZ)
  ssh -o BatchMode=yes cat-prod "$AP logs --no-color --since $SINCE --until $UNTIL backend" </dev/null > "$L/backend.log" 2> "$L/backend.stderr"
  echo $? > "$L/collect.rc"
  printf '%s\n%s\n' "$SINCE" "$UNTIL" > "$L/range-args.txt"
  date -u '+pc-end %FT%TZ' >> "$L/run-time.txt"
  (cd "$L" && sha256sum run-time.txt install_state.txt install_state.rc state-2.sql state.tsv state.stderr state.rc backend.log backend.stderr collect.rc range-args.txt > read-SHA256SUMS)
  echo "read 끝 — state.rc=$(cat "$L/state.rc") state.stderr=$(wc -c < "$L/state.stderr")B collect.rc=$(cat "$L/collect.rc") log=$(wc -c < "$L/backend.log")B. 다시 읽지 말고 post 로."
  exit 0
fi

if [ "$MODE" = post ]; then
  [ -d "$L" ] && [ ! -e "$L/post" ] || stop "보존폴더 없음 또는 post 이미 실행"
  (cd "$L" && sha256sum --quiet -c read-SHA256SUMS) || stop "read 원문이 바뀜"
  mkdir "$L/post"
  # 구간 근거: backend 가 SINCE 이후 재기동했으면 SINCE~기동 사이 로그가 없다 → range.json 을 만들지 않는다(P0 검사 미완료)
  SINCE=$(sed -n 1p "$L/range-args.txt"); UNTIL=$(sed -n 2p "$L/range-args.txt")
  ST=$(grep -oE 'StartedAt=[^ ]+' "$L/install_state.txt" | cut -d= -f2)
  if [ "$(cat "$L/install_state.rc")" = 0 ] && [ -n "$ST" ] && [ "$(date -u -d "$ST" +%s)" -le "$(date -u -d "$SINCE" +%s)" ]; then
    printf '{"since": "%s", "until": "%s", "axis": "server_utc", "basis": "pm-read.sh read 인자 SINCE·실행 시각 UNTIL, docker compose logs --since/--until; backend StartedAt %s ≤ SINCE"}\n' \
      "$SINCE" "$UNTIL" "$ST" > "$L/post/range.json"
  else
    echo "backend StartedAt($ST)이 SINCE 뒤이거나 설치 상태 읽기 실패 — 구간 근거 미제공으로 둔다" > "$L/post/range-not-provided.txt"
  fi
  python -I "$P0T" --log "$L/backend.log" --collect-rc "$L/collect.rc" --range "$L/post/range.json" --u-vars "$UV" --u-vars-sha256 "$UV_SHA" \
    --label "pm-read P0" --synthetic false --out-dir "$L/post/p0"; echo $? > "$L/post/p0.rc"
  cp "$L/post/p0/p0.json" "$L/post/p0-result.json"
  N3=${3:-}
  python -I - "$L/post" "$N3" "$EVI" <<'PYEOF' || stop "aux 작성 실패 — 변환하지 않는다"
import hashlib, json, os, sys
d, n3, evi = sys.argv[1], sys.argv[2], sys.argv[3]
h = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
if n3:   # n03-values.json: {"source_file": docs/evidence 기준 보존 N03 보고 원문 상대 경로, "error_logs": [...], "paper_522_587": {...}} — 승인 전 전사·검토
    v = json.load(open(n3, encoding='utf-8'))
    src = os.path.join(d, 'n03-source' + os.path.splitext(v['source_file'])[1])
    open(src, 'wb').write(open(os.path.join(evi, v['source_file']), 'rb').read())
    n03 = {'source': {'file': os.path.basename(src), 'sha256': h(src), 'rc': 0}, 'error_logs': v['error_logs'], 'paper_522_587': v['paper_522_587']}
else:
    src = os.path.join(d, 'n03-source.txt')
    open(src, 'w', encoding='utf-8').write('N03 값 미제공 — 이 읽기에서 N03 항목은 null(수집 불통과)\n')
    n03 = {'source': {'file': 'n03-source.txt', 'sha256': h(src), 'rc': 0}, 'error_logs': None, 'paper_522_587': None}
aux = {'aux_version': 'pm-aux-2', 'synthetic': False, 'n03': n03,
       'p0': {'source': {'file': 'p0-result.json', 'sha256': h(os.path.join(d, 'p0-result.json'))}}}
json.dump(aux, open(os.path.join(d, 'n03-p0-aux.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
PYEOF
  python -I "$CONV" --sql "$L/state-2.sql" --sql-sha256 "$SQL_SHA" --tsv "$L/state.tsv" --rc "$L/state.rc" --stderr "$L/state.stderr" \
    --aux "$L/post/n03-p0-aux.json" --label "pm-read state" --synthetic false --out-dir "$L/post/conv"; echo $? > "$L/post/conv.rc"
  (cd "$L" && find post -type f | sort | xargs sha256sum > post-SHA256SUMS)
  echo "post 끝 — P0 $(python -I -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['status'])" "$L/post/p0/p0.json")(rc $(cat "$L/post/p0.rc")) · 변환 rc $(cat "$L/post/conv.rc"). W1/W2 는 승인된 실제 등록표 전에는 실행하지 않는다."
  exit 0
fi
stop "첫 인자는 read 또는 post"
