"""U0-maint2.sh 생성기(초안 2판, 총괄 v1.240 §3-1). 고정 U0 블록(91ea9342)의 106~107행과 109행만 바꾼다.
사용: python -I make_u0_maint2.py <U0.sh 경로>   → 같은 폴더에 U0-maint2.sh(이미 있으면 같을 때만 통과).
1판(U0-maint.sh 07535435)과 다른 점 — 필수 기록 실패를 명시적으로 멈추고 비용 미확정(조건 미충족)과 섞지 않는다:
  · predeploy-verdict.txt: pf3ok 종료 0/1 인데 그에 맞는 '판정 |' 줄이 파일에 없으면 기록 실패로 멈춘다(리디렉션 실패가 종료 1 로 보여 '기준 미충족'으로 읽히던 틈)
  · r1-gate.txt: pf3ok 0 경로·유지보수 경로 모두 쓴 뒤 내용을 다시 읽어 확인한다. 실패 → 멈춤(ok-U0 없음 → U1 금지)
  · r1-diag-4.tsv/.stderr: 출력 파일이 만들어지지 않았으면 조회 실패·대상 0 이 아니라 기록 실패로 멈춘다
  · shape 출력·판정기 출력: 파일이 비었거나 첫 낱말이 종료값과 맞지 않으면 기록 실패로 멈춘다
  · ok-U0 표지: 만든 뒤 존재를 확인한다. 화면 표시(cat) 실패도 멈춘다
그대로: 1~105행·108행, MAINT4/M4GATE 빈칸이면 원래 107행 문구로 멈춤, pf3ok 3·그 밖은 형식 이상으로 멈춤, `|| true` 없음.
"""
import hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
U0_SHA = '91ea93428ab4dc4f515d2497d388b4ccd75d4dcb48a1be1089468a3a433f1457'
OUT = os.path.join(HERE, 'U0-maint2.sh')
raw = open(sys.argv[1], 'rb').read()
if hashlib.sha256(raw).hexdigest() != U0_SHA:
    sys.exit('U0.sh 해시 불일치')
L = raw.decode('utf-8').split('\n')
assert L[105].startswith('pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" && PV=0 || PV=$?')
OLD107 = L[106]
assert OLD107.startswith('[ "$PV" = 0 ] || stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 $PV')
assert L[107].startswith('rm -f "$M/image-new.txt"')
OLD109 = L[108]
assert OLD109 == 'touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"', OLD109
REC = '— 비용 미확정·조건 미충족으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지). 배포 전 중단·보고'
G0 = 'R1 해소 — 원래 U0 기준 통과(유지보수 예외 사용 안 함)'
NEW = [
    '# ── v1.240 §3-1 유지보수 배포 보정 2판(초안·미승인). MAINT4·M4GATE 가 빈칸이면 pf3ok 1 은 원래 107행과 같은 멈춤이다 ──',
    '# 필수 기록(predeploy-verdict·r1-gate·r1-diag-4 출력·형태/판정 출력·ok-U0)의 실패는 모두 기록 실패로 멈춘다 — 비용 미확정과 섞지 않는다',
    'PV=0; pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" || PV=$?',
    "case \"$PV\" in 0) VL='판정 | 진행 가능' ;; 1) VL='판정 | 🔴 보수적 사전 기준 미충족' ;; *) VL= ;; esac",
    'if [ -n "$VL" ]; then grep -q "^$VL" "$M/predeploy-verdict.txt" 2>/dev/null || stop "🔴 배포 전 판정 기록 실패(pf3ok 종료 $PV 인데 predeploy-verdict.txt 에 맞는 판정 줄 없음) ' + REC + '"; fi',
    'cat "$M/predeploy-verdict.txt" || stop "🔴 배포 전 판정 표시 실패 ' + REC + '"',
    'MAINT4=""   # 빈칸 — 총괄이 실행 출처(고정 커밋)를 확정한 뒤에만 채운다. 채워도 U0 만 통과할 수 있고 매매 시작 승인이 아니다',
    'M4P=docs/evidence/r1-diag4-20261124; M4SQL=276a14fe13d654d17bb59268de15790b5bede7a55fd38a39bf543f67804194dd; M4CHK=2790217864ec66ee96425601b006608dc5332175b0bca9377a47f1916817a96a',
    'M4GP=docs/evidence/u0-maint-draft-20261124/maint_gate4.py; M4GATE=""   # 판정기 해시도 수용 뒤 고정(빈칸이면 멈춤)',
    'case "$PV" in',
    '  0) G0="' + G0 + '"',
    '     { printf \'%s\\n\' "$G0" > "$M/r1-gate.txt" && [ "$(cat "$M/r1-gate.txt")" = "$G0" ]; } 2>/dev/null || stop "🔴 r1-gate 필수 기록 실패(pf3ok 0 경로) ' + REC + '"',
    '     cat "$M/r1-gate.txt" || stop "🔴 r1-gate 표시 실패 ' + REC + '" ;;',
    '  1) [ -n "$MAINT4" ] && [ -n "$M4GATE" ] || ' + OLD107.replace('[ "$PV" = 0 ] || ', '', 1),
    '     [ ! -e "$M/r1-diag-4.tsv" ] && [ ! -e "$M/r1-diag-4.stderr" ] && [ ! -e "$M/r1-gate.txt" ] || stop "r1-diag-4 결과·r1-gate 가 이미 있다 — 같은 U0 시도에서 두 번 읽지 않는다. ls -l $M 를 보고"',
    '     git cat-file -e "$MAINT4^{commit}" || stop "MAINT4 고정 커밋 없음"',
    '     git show "$MAINT4:$M4P/sql/r1-diag-4.sql" > "$M/r1-diag-4.sql" && [ "$(h256 "$M/r1-diag-4.sql")" = "$M4SQL" ] || stop "r1-diag-4 원문·해시가 다름"',
    '     git show "$MAINT4:$M4P/check/shape_check4.py" > "$M/shape_check4.py" && [ "$(h256 "$M/shape_check4.py")" = "$M4CHK" ] || stop "shape_check4 원문·해시가 다름"',
    '     git show "$MAINT4:$M4GP" > "$M/maint_gate4.py" && [ "$(h256 "$M/maint_gate4.py")" = "$M4GATE" ] || stop "maint_gate4 원문·해시가 다름"',
    '     M4R=0; $DC exec -T -e PGOPTIONS=\'-c statement_timeout=15s -c lock_timeout=2s\' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f - < "$M/r1-diag-4.sql" > "$M/r1-diag-4.tsv" 2> "$M/r1-diag-4.stderr" || M4R=$?',
    '     [ -f "$M/r1-diag-4.tsv" ] && [ -f "$M/r1-diag-4.stderr" ] || stop "🔴 r1-diag-4 출력 기록 실패(종료 $M4R, 출력 파일 없음) — 조회 실패·대상 0 으로 보지 않는다. ok-U0 를 만들지 않는다(U1 금지)"',
    '     [ "$M4R" = 0 ] && [ ! -s "$M/r1-diag-4.stderr" ] || stop "🔴 r1-diag-4 조회 실패·표준오류(종료 $M4R) — 원문은 서버 $M 에만 둔다. 배포 전 중단·보고(조회 실패는 대상 0 이 아니다)"',
    '     M4S=0; python3 -I "$M/shape_check4.py" "$M/r1-diag-4.tsv" > "$M/r1-diag-4.shape.txt" 2>&1 || M4S=$?',
    '     [ -f "$M/r1-diag-4.shape.txt" ] && [ -s "$M/r1-diag-4.shape.txt" ] || stop "🔴 r1-diag-4 형태 검사 출력 기록 실패(종료 $M4S) ' + REC + '"',
    '     [ "$M4S" = 0 ] && [ "$(cat "$M/r1-diag-4.shape.txt")" = SHAPE_OK ] || stop "🔴 r1-diag-4 형식 이상(shape_check4 종료 $M4S) — 배포 전 중단·보고"',
    '     M4V=0; python3 -I "$M/maint_gate4.py" "$M/r1-diag-4.tsv" "$M/predeploy-verdict.txt" > "$M/r1-gate.txt" || M4V=$?',
    "     case \"$M4V\" in 0) GW='MAINT_DEPLOY_ONLY ' ;; 1) GW='MAINT_STOP ' ;; 3) GW='MAINT_FORMAT ' ;; *) GW= ;; esac",
    '     [ -n "$GW" ] && grep -q "^$GW" "$M/r1-gate.txt" 2>/dev/null || stop "🔴 r1-gate 필수 기록 실패(유지보수 판정 종료 $M4V 인데 r1-gate.txt 에 맞는 판정 줄 없음) ' + REC + '"',
    '     cat "$M/r1-gate.txt" || stop "🔴 r1-gate 표시 실패 ' + REC + '"',
    '     case "$M4V" in',
    '       0) echo "⚠️ U0 는 유지보수 배포 준비로만 통과한다 — R1 미해소·LIVE/DYNAMIC 신규 실자금 진입은 제품 관문이 계속 거절한다. 세션 생성/시작 명령 없음. U2 직전 사용자 확인 유지" ;;',
    '       1) stop "🔴 유지보수 배포 조건 미충족(maint_gate4 종료 1) — 새/불명 R1 분기·실자금 실행 상태·소유 불명 중 하나. r1-gate.txt 를 보고. 한도·자본·세션·과거 행을 바꾸지 않는다" ;;',
    '       *) stop "🔴 유지보수 판정 형식 이상·읽기 실패(maint_gate4 종료 $M4V) — 배포 전 중단·보고" ;;',
    '     esac ;;',
    '  *) stop "🔴 배포 전 읽기 v3 형식 이상·읽기 실패(pf3ok 종료 $PV) — 배포 전 중단·보고. 원문 predeploy-read.txt 보존" ;;',
    'esac',
    L[107],
    'touch "$M/ok-U0" && [ -e "$M/ok-U0" ] || stop "🔴 ok-U0 표지 기록 실패 — U1 로 넘어가지 않는다"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"',
]
out = '\n'.join(L[:105] + NEW + L[109:])
data = out.encode('utf-8')
if os.path.exists(OUT):
    if open(OUT, 'rb').read() != data:
        sys.exit('기존 U0-maint2.sh 와 다름 — 덮어쓰지 않는다')
else:
    open(OUT, 'wb').write(data)
print(hashlib.sha256(data).hexdigest())
