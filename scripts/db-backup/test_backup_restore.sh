#!/bin/bash
# =============================================================================
# 백업·보존·복구 스크립트 실패 경로 시험 (리뷰 23차, 2026-10-28)
#
# 가짜 명령(PATH 앞에 둔 pg_dump·mv·cp·docker 대역)으로 실패를 만들고, 실패가 성공으로 끝나지 않는지 본다.
#   B1~B4 backup-loop.sh : 정상 / mv 실패 / 성공 기록 실패 / pg_dump 실패
#   K1~K5 keep-latest.sh : 20 바이트 빈 덤프 / 끝 표시 없음 / 정상 / 복사 실패 / 이미 보존됨
#   D1~D2 db-restore-drill.sh (Docker 필요) : 가짜 66세션 덤프로 마지막 성공까지 / 행 수 조회 실패
# 사용법: bash scripts/db-backup/test_backup_restore.sh [이미지] [TimescaleDB 버전]
#         (기본 timescale/timescaledb:latest-pg15, 버전은 이미지에서 읽는다). D 를 건너뛰려면 SKIP_DOCKER=1.
# 운영 DB·운영 파일은 건드리지 않는다. 모든 파일은 임시 폴더에 만든다.
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
IMAGE="${1:-timescale/timescaledb:latest-pg15}"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
PASS=0; FAILN=0
check() { if eval "$2"; then echo "  ✓ $1"; PASS=$((PASS+1)); else echo "  ✗ $1  ($2)"; FAILN=$((FAILN+1)); fi; }

# ── 가짜 명령 ────────────────────────────────────────────────────────────────
mkdir -p "$W/fake_ok" "$W/fake_mv" "$W/fake_dumpfail" "$W/fake_cp"
cat > "$W/fake_ok/pg_dump" <<'EOF'
#!/bin/sh
echo "-- PostgreSQL database dump"
i=0; while [ $i -lt 200 ]; do echo "INSERT INTO t VALUES ($i);"; i=$((i+1)); done
echo "-- PostgreSQL database dump complete"
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/fake_ok/pg_isready"
cp "$W/fake_ok/pg_dump" "$W/fake_ok/pg_isready" "$W/fake_mv/"
printf '#!/bin/sh\necho "mv: 가짜 실패" >&2\nexit 1\n' > "$W/fake_mv/mv"
printf '#!/bin/sh\necho "pg_dump: 가짜 실패" >&2\nexit 1\n' > "$W/fake_dumpfail/pg_dump"
cp "$W/fake_ok/pg_isready" "$W/fake_dumpfail/"
printf '#!/bin/sh\necho "cp: 가짜 실패" >&2\nexit 1\n' > "$W/fake_cp/cp"
chmod +x "$W"/fake_*/*

# backup-loop 한 회차. $1=가짜 폴더, $2=백업 폴더. 종료 코드를 돌려준다.
loop() {
    PATH="$1:$PATH" BACKUP_ONCE=1 BACKUP_DIR="$2" BACKUP_MIN_BYTES=10 BACKUP_DB_HOST=x PGPASSWORD=x \
        sh "${HERE}/backup-loop.sh" > "$2.out" 2>&1
}
prep() { rm -rf "$1"; mkdir -p "$1"; touch -d '10 days ago' "$1/backup_20261001_000000.sql.gz"; }
finals() { ls "$1"/backup_2026*.sql.gz 2>/dev/null | grep -v 20261001_000000 | wc -l | tr -d ' '; }

echo "== B1 backup-loop 정상"
prep "$W/b1"; loop "$W/fake_ok" "$W/b1"; rc=$?
check "종료 0" "[ $rc -eq 0 ]"
check "확정 파일 1개" "[ $(finals "$W/b1") -eq 1 ]"
check "성공 기록 있음" "[ -s '$W/b1/.last_success' ]"
check "오래된 파일 회전 삭제" "[ ! -e '$W/b1/backup_20261001_000000.sql.gz' ]"

echo "== B2 backup-loop mv 실패"
prep "$W/b2"; loop "$W/fake_mv" "$W/b2"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "확정 파일 0개" "[ $(finals "$W/b2") -eq 0 ]"
check "성공 기록 없음" "[ ! -e '$W/b2/.last_success' ]"
check "오래된 파일 보존(회전 삭제 생략)" "[ -e '$W/b2/backup_20261001_000000.sql.gz' ]"
check "임시 파일 없음" "[ -z \"\$(ls '$W/b2'/*.part 2>/dev/null)\" ]"
check "로그에 '백업 실패: 파일 확정'" "grep -q '백업 실패: 파일 확정' '$W/b2.out'"
check "로그에 '백업 완료' 없음" "! grep -q '백업 완료' '$W/b2.out'"

echo "== B3 backup-loop 성공 기록 저장 실패"
prep "$W/b3"; mkdir "$W/b3/.last_success"; loop "$W/fake_ok" "$W/b3"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "오래된 파일 보존(회전 삭제 생략)" "[ -e '$W/b3/backup_20261001_000000.sql.gz' ]"
check "로그에 '성공 기록' 실패" "grep -q '성공 기록(.last_success) 저장 실패' '$W/b3.out'"
check "로그에 '백업 완료' 없음" "! grep -q '백업 완료' '$W/b3.out'"

echo "== B4 backup-loop pg_dump 실패"
prep "$W/b4"; loop "$W/fake_dumpfail" "$W/b4"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "확정 파일 0개" "[ $(finals "$W/b4") -eq 0 ]"
check "오래된 파일 보존" "[ -e '$W/b4/backup_20261001_000000.sql.gz' ]"

# ── keep-latest ──────────────────────────────────────────────────────────────
keep() { PATH="$1:$PATH" BACKUP_SRC_DIR="$W/src" KEEP_DIR="$W/keep" bash "${HERE}/keep-latest.sh" "${@:2}" > "$W/k.out" 2>&1; }
mkdir -p "$W/src"
printf '' | gzip > "$W/src/backup_20261028_000001.sql.gz"                                  # 20 바이트 빈 덤프
"$W/fake_ok/pg_dump" | head -n 50 | gzip > "$W/src/backup_20261028_000002.sql.gz"          # 끝 표시 없음
"$W/fake_ok/pg_dump" | gzip > "$W/src/backup_20261028_000003.sql.gz"                       # 정상
touch -d '3 minutes ago' "$W/src/backup_20261028_000003.sql.gz"
touch -d '2 minutes ago' "$W/src/backup_20261028_000002.sql.gz"
touch -d '1 minutes ago' "$W/src/backup_20261028_000001.sql.gz"

echo "== K1 keep-latest 20 바이트 빈 덤프(최신 파일)"
keep "$W/fake_ok"; rc=$?
check "빈 덤프 크기 20" "[ $(wc -c < "$W/src/backup_20261028_000001.sql.gz") -eq 20 ]"
check "종료 1" "[ $rc -eq 1 ]"
check "보존 폴더에 파일 없음" "[ -z \"\$(ls '$W/keep' 2>/dev/null)\" ]"
check "'덤프 끝 표시 없음'으로 중단" "grep -q '\[중단\] 덤프 끝 표시 없음' '$W/k.out'"

echo "== K2 keep-latest 끝 표시 없는 덤프"
keep "$W/fake_ok" "$W/src/backup_20261028_000002.sql.gz"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "보존 폴더에 파일 없음" "[ -z \"\$(ls '$W/keep' 2>/dev/null)\" ]"

echo "== K4 keep-latest 복사 실패"
keep "$W/fake_cp" "$W/src/backup_20261028_000003.sql.gz"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "사본·SHA256SUMS 없음" "[ ! -e '$W/keep/backup_20261028_000003.sql.gz' ] && [ ! -e '$W/keep/SHA256SUMS' ]"
check "임시 사본 없음" "[ -z \"\$(ls -A '$W/keep' 2>/dev/null)\" ]"

echo "== K3 keep-latest 정상"
keep "$W/fake_ok" "$W/src/backup_20261028_000003.sql.gz"; rc=$?
SRC_SHA=$(sha256sum "$W/src/backup_20261028_000003.sql.gz" | cut -d' ' -f1)
check "종료 0" "[ $rc -eq 0 ]"
check "사본 해시 = 원본" "[ \"\$(sha256sum '$W/keep/backup_20261028_000003.sql.gz' | cut -d' ' -f1)\" = '$SRC_SHA' ]"
check "SHA256SUMS 1줄" "[ \"\$(cat '$W/keep/SHA256SUMS')\" = '$SRC_SHA  backup_20261028_000003.sql.gz' ]"

echo "== K5 keep-latest 이미 보존됨"
keep "$W/fake_ok" "$W/src/backup_20261028_000003.sql.gz"; rc=$?
check "종료 1" "[ $rc -eq 1 ]"
check "SHA256SUMS 여전히 1줄" "[ $(wc -l < "$W/keep/SHA256SUMS") -eq 1 ]"

# ── 복구 확인 (Docker) ───────────────────────────────────────────────────────
if [ "${SKIP_DOCKER:-0}" != "1" ]; then
    echo "== 가짜 66세션 덤프 만들기 ($IMAGE)"
    C="fake66-$$"
    docker run -d --name "$C" --network none -e POSTGRES_PASSWORD=x -e POSTGRES_DB=crypto_auto_trader "$IMAGE" >/dev/null
    for i in $(seq 1 120); do
        docker logs "$C" 2>&1 | grep -q "PostgreSQL init process complete" && docker exec "$C" pg_isready -U postgres -q 2>/dev/null && break
        sleep 1
    done
    TSDB=$(docker exec "$C" psql -X -U postgres -d crypto_auto_trader -tAc "SELECT extversion FROM pg_extension WHERE extname='timescaledb'" | tr -d '\r')
    SESSIONS_SQL=$(python -c "import json,sys; c=json.load(open(sys.argv[1],encoding='utf-8')); print(','.join(f\"({s['id']},'{s['coin']}')\" for s in c['sessions']))" "${ROOT}/scripts/forward_aggregate/forward_config.json")
    docker exec -i "$C" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d crypto_auto_trader > /dev/null <<EOF
CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), success boolean NOT NULL);
INSERT INTO flyway_schema_history VALUES (1,'1',true),(2,'94',true);
CREATE SCHEMA paper_trading;
CREATE TABLE paper_trading.virtual_balance (id bigint PRIMARY KEY, coin_pair varchar(20) NOT NULL);
INSERT INTO paper_trading.virtual_balance VALUES (1,'KRW-BTC'),${SESSIONS_SQL};
CREATE TABLE paper_trading.position (id bigserial PRIMARY KEY, session_id bigint NOT NULL);
INSERT INTO paper_trading.position (session_id) SELECT 522 + g % 66 FROM generate_series(1,130) g;
CREATE TABLE paper_trading."order" (id bigserial PRIMARY KEY, session_id bigint NOT NULL);
INSERT INTO paper_trading."order" (session_id) SELECT 522 + g % 66 FROM generate_series(1,260) g;
CREATE TABLE market_data_cache (coin_pair varchar(20), timeframe varchar(10), time timestamptz NOT NULL, close numeric);
SELECT create_hypertable('market_data_cache','time');
INSERT INTO market_data_cache SELECT 'KRW-BTC','H1', timestamptz '2026-10-01' + g * interval '1 hour', 1 FROM generate_series(1,500) g;
EOF
    docker exec "$C" pg_dump -U postgres crypto_auto_trader 2>/dev/null | gzip > "$W/fake66.sql.gz"
    docker rm -f "$C" >/dev/null
    SHA=$(sha256sum "$W/fake66.sql.gz" | cut -d' ' -f1)

    echo "== D1 복구 확인 — 가짜 66세션 덤프로 마지막 성공까지"
    bash "${ROOT}/scripts/db-restore-drill.sh" "$W/fake66.sql.gz" "$SHA" "$IMAGE" "$TSDB" > "$W/d1.out" 2>&1; rc=$?
    sed 's/^/    /' "$W/d1.out"
    check "종료 0" "[ $rc -eq 0 ]"
    check "66세션 식별자 일치" "grep -q '66세션 식별자 일치' '$W/d1.out'"
    check "행 수 (66 · 130 · 260 · 67 · 500)" "grep -q '(66세션): 66$' '$W/d1.out' && grep -q 'position (66세션): *130$' '$W/d1.out' && grep -q 'order (66세션): *260$' '$W/d1.out' && grep -q '(전체): *67$' '$W/d1.out' && grep -q 'market_data_cache (전체): *500$' '$W/d1.out'"
    check "'복구 확인 성공'" "grep -q '복구 확인 성공' '$W/d1.out'"

    echo "== D2 복구 확인 — 행 수 조회 실패"
    REAL_DOCKER=$(command -v docker)
    mkdir -p "$W/fake_docker"
    cat > "$W/fake_docker/docker" <<EOF
#!/bin/bash
for a in "\$@"; do [[ "\$a" == *"FROM public.market_data_cache"* ]] && { echo "가짜 조회 실패" >&2; exit 1; }; done
exec "$REAL_DOCKER" "\$@"
EOF
    chmod +x "$W/fake_docker/docker"
    PATH="$W/fake_docker:$PATH" bash "${ROOT}/scripts/db-restore-drill.sh" "$W/fake66.sql.gz" "$SHA" "$IMAGE" "$TSDB" > "$W/d2.out" 2>&1; rc=$?
    tail -n 2 "$W/d2.out" | sed 's/^/    /'
    check "종료 1" "[ $rc -eq 1 ]"
    check "'조회 실패: market_data_cache(전체)'" "grep -q '조회 실패: market_data_cache(전체)' '$W/d2.out'"
    check "'복구 확인 성공' 없음" "! grep -q '복구 확인 성공' '$W/d2.out'"
    check "남은 컨테이너 없음" "[ -z \"\$(docker ps -aq --filter name=restore-check-)\" ]"
fi

echo "== 결과: 통과 $PASS, 실패 $FAILN"
[ "$FAILN" -eq 0 ]
