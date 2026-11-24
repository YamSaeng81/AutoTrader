"""U0-maint.sh 생성기(초안, 총괄 v1.239 §3-2). 고정 U0 블록(91ea9342, D81A2 65a384e5 추출본)의 106~107행만 바꾼다.
사용: python -I make_u0_maint.py <U0.sh 경로>   → 같은 폴더에 U0-maint.sh 를 쓴다(이미 있으면 같을 때만 통과).
바뀌는 것: pf3ok 종료 1 을 곧바로 멈춤으로 보내지 않고, MAINT4(고정 커밋) 칸이 채워졌을 때만 r1-diag-4 읽기 1회와 maint_gate4 판정으로 가른다.
  MAINT4 가 빈칸이면 원래 U0 와 같은 문구로 멈춘다. pf3ok 종료 3(형식 이상·읽기 실패)과 그 밖은 원래대로 멈춘다. `|| true` 로 거절을 지우지 않는다.
그대로인 것: 1~105행 전부(핀·태그·이미지·Flyway·SID203·0 확인·BTC·뉴스·경보·감시 스크립트·배포 전 읽기 v3 원문·해시), 108~110행(ok-U0 표지).
"""
import hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
U0_SHA = '91ea93428ab4dc4f515d2497d388b4ccd75d4dcb48a1be1089468a3a433f1457'
OUT = os.path.join(HERE, 'U0-maint.sh')
raw = open(sys.argv[1], 'rb').read()
if hashlib.sha256(raw).hexdigest() != U0_SHA:
    sys.exit('U0.sh 해시 불일치')
L = raw.decode('utf-8').split('\n')
OLD106 = 'pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" && PV=0 || PV=$?; cat "$M/predeploy-verdict.txt"'
assert L[105] == OLD106, L[105]
assert L[106].startswith('[ "$PV" = 0 ] || stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 $PV')
OLD107 = L[106]
NEW = [
    OLD106,
    '# ── v1.239 §3-2 유지보수 배포 보정(초안·미승인). MAINT4 가 빈칸이면 아래 1) 은 원래 107행과 같은 멈춤이다 ──',
    'MAINT4=""   # 빈칸 — 총괄이 실행 출처(고정 커밋)를 확정한 뒤에만 채운다. 채워도 U0 만 통과할 수 있고 매매 시작 승인이 아니다',
    'M4P=docs/evidence/r1-diag4-20261124; M4SQL=276a14fe13d654d17bb59268de15790b5bede7a55fd38a39bf543f67804194dd; M4CHK=2790217864ec66ee96425601b006608dc5332175b0bca9377a47f1916817a96a',
    'M4GP=docs/evidence/u0-maint-draft-20261124/maint_gate4.py; M4GATE=""   # 판정기 해시도 수용 뒤 고정(빈칸이면 멈춤)',
    'case "$PV" in',
    '  0) echo "R1 해소 — 원래 U0 기준 통과(유지보수 예외 사용 안 함)" | tee "$M/r1-gate.txt" ;;',
    '  1) [ -n "$MAINT4" ] && [ -n "$M4GATE" ] || ' + OLD107.replace('[ "$PV" = 0 ] || ', '', 1),
    '     [ ! -e "$M/r1-diag-4.tsv" ] || stop "r1-diag-4 결과가 이미 있다 — 같은 U0 시도에서 두 번 읽지 않는다. ls -l $M 를 보고"',
    '     git cat-file -e "$MAINT4^{commit}" || stop "MAINT4 고정 커밋 없음"',
    '     git show "$MAINT4:$M4P/sql/r1-diag-4.sql" > "$M/r1-diag-4.sql" && [ "$(h256 "$M/r1-diag-4.sql")" = "$M4SQL" ] || stop "r1-diag-4 원문·해시가 다름"',
    '     git show "$MAINT4:$M4P/check/shape_check4.py" > "$M/shape_check4.py" && [ "$(h256 "$M/shape_check4.py")" = "$M4CHK" ] || stop "shape_check4 원문·해시가 다름"',
    '     git show "$MAINT4:$M4GP" > "$M/maint_gate4.py" && [ "$(h256 "$M/maint_gate4.py")" = "$M4GATE" ] || stop "maint_gate4 원문·해시가 다름"',
    '     M4R=0; $DC exec -T -e PGOPTIONS=\'-c statement_timeout=15s -c lock_timeout=2s\' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f - < "$M/r1-diag-4.sql" > "$M/r1-diag-4.tsv" 2> "$M/r1-diag-4.stderr" || M4R=$?',
    '     [ "$M4R" = 0 ] && [ ! -s "$M/r1-diag-4.stderr" ] || stop "🔴 r1-diag-4 조회 실패·표준오류(종료 $M4R) — 원문은 서버 $M 에만 둔다. 배포 전 중단·보고(조회 실패는 대상 0 이 아니다)"',
    '     M4S=0; python3 -I "$M/shape_check4.py" "$M/r1-diag-4.tsv" > "$M/r1-diag-4.shape.txt" 2>&1 || M4S=$?',
    '     [ "$M4S" = 0 ] && [ "$(cat "$M/r1-diag-4.shape.txt")" = SHAPE_OK ] || stop "🔴 r1-diag-4 형식 이상(shape_check4 종료 $M4S) — 배포 전 중단·보고"',
    '     M4V=0; python3 -I "$M/maint_gate4.py" "$M/r1-diag-4.tsv" "$M/predeploy-verdict.txt" > "$M/r1-gate.txt" || M4V=$?; cat "$M/r1-gate.txt"',
    '     case "$M4V" in',
    '       0) echo "⚠️ U0 는 유지보수 배포 준비로만 통과한다 — R1 미해소·LIVE/DYNAMIC 신규 실자금 진입은 제품 관문이 계속 거절한다. 세션 생성/시작 명령 없음. U2 직전 사용자 확인 유지" ;;',
    '       1) stop "🔴 유지보수 배포 조건 미충족(maint_gate4 종료 1) — 새/불명 R1 분기·실자금 실행 상태·소유 불명 중 하나. r1-gate.txt 를 보고. 한도·자본·세션·과거 행을 바꾸지 않는다" ;;',
    '       *) stop "🔴 유지보수 판정 형식 이상·읽기 실패(maint_gate4 종료 $M4V) — 배포 전 중단·보고" ;;',
    '     esac ;;',
    '  *) stop "🔴 배포 전 읽기 v3 형식 이상·읽기 실패(pf3ok 종료 $PV) — 배포 전 중단·보고. 원문 predeploy-read.txt 보존" ;;',
    'esac',
]
out = '\n'.join(L[:105] + NEW + L[107:])
data = out.encode('utf-8')
if os.path.exists(OUT):
    if open(OUT, 'rb').read() != data:
        sys.exit('기존 U0-maint.sh 와 다름 — 덮어쓰지 않는다')
else:
    open(OUT, 'wb').write(data)
print(hashlib.sha256(data).hexdigest())
