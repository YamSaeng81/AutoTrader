"""r1-diag4-read.sh 생성기: 고정 v1.236 실행기(6af7ee65)에서 아래 치환만 정확히 1회씩 적용한다(각 치환이 1회가 아니면 실패).
사용: python -I make_runner.py   (같은 폴더에 r1-diag4-read.sh 를 쓴다. 이미 있으면 내용이 같을 때만 통과)
"""
import hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', '..', 'r1-diag2-20261124', 'approval', 'r1-diag-read-v1236.sh')
SRC_SHA = '6af7ee65cd5d0b5496b7e813bd4ba48a62576f89eee89f5d3941f97a739d24a4'
OUT = os.path.join(HERE, 'r1-diag4-read.sh')
BS = chr(92)

raw = open(SRC, 'rb').read()
if hashlib.sha256(raw).hexdigest() != SRC_SHA:
    sys.exit('v1.236 실행기 해시 불일치')
src = raw.decode('utf-8')

REP = [
    ("# R1 판정 불가 원인 분리 1회 실행기 r1-diag-read (총괄 v1.234 §3-3). **미실행 — 승인 값 3칸이 빈칸인 동안 실행되지 않는다(종료 2).**\n"
     "# 바탕: a1-fake-boundary-20261123/prod/approval/a1-read.sh v2(b1e9e1f0). SQL·식별자·형태 검사·출력 폴더 규칙·승인 창만 바꿨다(diff/a1-read-v2-vs-r1-diag-read.diff).",
     "# R1 만료·전체 R1·실자금 실행 상태 최소 진단 1회 실행기 r1-diag4-read (총괄 v1.239 §3-1). **미실행 — 승인 값 3칸이 빈칸인 동안 실행되지 않는다(종료 2).**\n"
     "# 바탕: 고정 v1.236 r1-diag-read-v1236.sh(6af7ee65). SQL·검사기·META 식별자·결과 폴더 이름·직전 결과 확인·승인 값만 바꿨다(diff/v1236-vs-r1-diag4-read.diff)."),
    ("(2) r1-diag-2.sql 1회", "(2) r1-diag-4.sql 1회"),
    ("#   bash r1-diag-read.sh <보존폴더 경로(이름 r1-diag-read-YYYYMMDD-HHMM,",
     "#   bash r1-diag4-read.sh <보존폴더 경로(이름 r1-diag4-read-YYYYMMDD-HHMM,"),
    ('APPROVED_DATE="20261124"   #', 'APPROVED_DATE=""   #'),
    ('WIN_START="2026-11-24T04:39:00Z"       #', 'WIN_START=""       #'),
    ('WIN_END="2026-11-24T08:00:00Z"         #', 'WIN_END=""         #'),
    ("STOP_SQL_SHAPE(shape_check2.py 실제 종료값", "STOP_SQL_SHAPE(shape_check4.py 실제 종료값"),
    ("# 1회 범위: 보존폴더는 없어야 하고, 같은 docs/evidence 아래 다른 r1-diag-read-* 폴더가 있으면 거절한다(같은 승인으로 두 번 읽지 않음).",
     "# 1회 범위: 보존폴더는 없어야 하고, 같은 docs/evidence 아래 다른 r1-diag4-read-* 폴더가 있으면 거절한다(같은 승인으로 두 번 읽지 않음).\n"
     "# 직전 결과: SQL 의 PRIOR 상수가 r1-diag-read-20261124-1339 의 META 에서 왔으므로 그 기록이 완료·READ_OK·r1.tsv 해시 그대로일 때만 진행한다."),
    ("관문 완화의 근거로 자동 소비하지 않는다(v1.234 §4).", "관문 완화·U0 재시도의 근거로 자동 소비하지 않는다(v1.234 §4, v1.239 §3-2)."),
    ('SQL="$R1/sql/r1-diag-2.sql"; SQL_SHA=4134c631879ff8fc062c161c2724c9597c814e2c51ce0c0313684e14ddf8e269',
     'SQL="$R1/sql/r1-diag-4.sql"; SQL_SHA=276a14fe13d654d17bb59268de15790b5bede7a55fd38a39bf543f67804194dd'),
    ('CHK="$R1/check/shape_check2.py"; CHK_SHA=7f56f64043b46fa7c65bba79a1bd174557b678a8af65fd9289e3eb5dd287b469',
     'CHK="$R1/check/shape_check4.py"; CHK_SHA=2790217864ec66ee96425601b006608dc5332175b0bca9377a47f1916817a96a'),
    ('deny "고정 SQL 해시 불일치(r1-diag-2.sql)"', 'deny "고정 SQL 해시 불일치(r1-diag-4.sql)"'),
    ('deny "고정 검사기 해시 불일치(shape_check2.py)"', 'deny "고정 검사기 해시 불일치(shape_check4.py)"'),
    ('in r1-diag-read-[0-9]', 'in r1-diag4-read-[0-9]'),
    ('deny "보존폴더 이름 형식(r1-diag-read-YYYYMMDD-HHMM)"', 'deny "보존폴더 이름 형식(r1-diag4-read-YYYYMMDD-HHMM)"'),
    ('LD=${LB#r1-diag-read-}', 'LD=${LB#r1-diag4-read-}'),
    ('[ "$LB" = r1-diag-read-20261124-1339 ] || deny "v1.236 result folder mismatch"\n'
     'PREV="$EVI/r1-diag-read-20261124-1300"\n'
     '[ -d "$PREV" ] && [ ! -e "$PREV/INCOMPLETE.txt" ] || deny "v1.235 record missing/incomplete"\n'
     "[ \"$(cat \"$PREV/read-status.txt\")\" = 'STOP_SQL_FAILED exit=255' ] || deny \"v1.235 status mismatch\"\n"
     'hashok "$PREV/r1.stderr" 2b28639672f8178b24455a0b45d92c9df406b93e62b51faa54246bb70ed15bb4 || deny "v1.235 stderr hash mismatch"\n'
     '[ -f "$PREV/r1.tsv" ] && [ ! -s "$PREV/r1.tsv" ] || deny "v1.235 stdout mismatch"\n'
     "[ -z \"$(find \"$EVI\" -maxdepth 1 -name 'r1-diag-read-*' ! -name 'r1-diag-read-20261124-1300' -print -quit)\" ] || deny \"다른 r1-diag-read-* 폴더가 이미 있음 — 1회 범위 초과\"",
     'PREV="$EVI/r1-diag-read-20261124-1339"\n'
     '[ -d "$PREV" ] && [ ! -e "$PREV/INCOMPLETE.txt" ] || deny "직전 진단 기록(1339) 없음/미완료"\n'
     "[ \"$(cat \"$PREV/read-status.txt\")\" = 'READ_OK exit=0' ] || deny \"직전 진단 상태가 READ_OK 아님\"\n"
     'hashok "$PREV/r1.tsv" 78580b939c156f8a37d1d25f5a054dcfb4d137c1ca3658ac131e5401739ee449 || deny "직전 진단 r1.tsv 해시 불일치(PRIOR 상수 출처)"\n'
     "[ -z \"$(find \"$EVI\" -maxdepth 1 -name 'r1-diag4-read-*' -print -quit)\" ] || deny \"다른 r1-diag4-read-* 폴더가 이미 있음 — 1회 범위 초과\""),
    ('"미완료 — r1-diag-read 시작', '"미완료 — r1-diag4-read 시작'),
    ('{ cp "$SQL" "$L/r1-diag-2.sql" && cp "$CHK" "$L/shape_check2.py" && hashok "$L/r1-diag-2.sql" "$SQL_SHA" && hashok "$L/shape_check2.py" "$CHK_SHA" ',
     '{ cp "$SQL" "$L/r1-diag-4.sql" && cp "$CHK" "$L/shape_check4.py" && hashok "$L/r1-diag-4.sql" "$SQL_SHA" && hashok "$L/shape_check4.py" "$CHK_SHA" '),
    ('hashok "$L/r1-diag-2.sql" "$SQL_SHA" || finish STOP_SQL_COPY_HASH 7', 'hashok "$L/r1-diag-4.sql" "$SQL_SHA" || finish STOP_SQL_COPY_HASH 7'),
    ('remote "$L/r1.tsv" "$L/r1.stderr" "$L/r1-diag-2.sql"', 'remote "$L/r1.tsv" "$L/r1.stderr" "$L/r1-diag-4.sql"'),
    ('$2=="r1-diag-2" && NF==10', '$2=="r1-diag-4" && NF==10'),
    ('hashok "$L/shape_check2.py" "$CHK_SHA" || finish STOP_CHECKER_COPY_HASH 7', 'hashok "$L/shape_check4.py" "$CHK_SHA" || finish STOP_CHECKER_COPY_HASH 7'),
    ('python -I "$L/shape_check2.py" "$T"', 'python -I "$L/shape_check4.py" "$T"'),
    ('echo "r1-diag-read 끝 — $st', 'echo "r1-diag4-read 끝 — $st'),
]
out = src
for a, b in REP:
    n = out.count(a)
    if n != 1:
        sys.exit(f'치환 대상 {n}회: {a[:70]!r}')
    out = out.replace(a, b)
left = [ln for ln in out.split('\n') if 'r1-diag-2' in ln or 'shape_check2' in ln]
if left:
    sys.exit(f'옛 이름이 남음: {left}')
data = out.encode('utf-8')
if os.path.exists(OUT):
    if open(OUT, 'rb').read() != data:
        sys.exit('기존 r1-diag4-read.sh 와 다름 — 덮어쓰지 않는다')
else:
    open(OUT, 'wb').write(data)
print(len(REP), hashlib.sha256(data).hexdigest())
