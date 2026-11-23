(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
h256() { sha256sum < "$1" | cut -d' ' -f1 ; }
pf3ok() { python3 - "$1" <<'PYEOF'
# 인자: $PSQL -At -F ' | ' < predeploy_read.v3.sql 의 출력 파일. 기대: 1~2줄 R1(LIVE|정수, DYNAMIC|정수) · 3~4줄 R2(LIVE|유한 십진수, DYNAMIC|유한 십진수) · 5줄부터 R3 16열(0행 이상).
# 종료 0 = 진행 가능(R1 두 종류 0, R3 판정 불가 행 0) · 1 = 🔴 보수적 사전 기준 미충족(보고 — 제품 진입 결과라고 단정하지 않는다) · 3 = 형식 이상·읽기 실패(조회 결과로 보지 않는다).
# R3 판정 불가(보수적): 무거래 확정이 아닌데 체결BUY 0·확정SELL 0·원가 미상 BUY·필수값 미상 SELL·원가/매수수량/매도순대금/매도수량 합 NULL·매도수량<매수수량 중 하나. 연속 손실 한도 안 여부는 여기서 줄이지 않는다.
# 총괄 v1.199 §3 형식: 개수 열은 정수, 원가·순대금은 유한 십진수, 수량은 0 이상 유한 십진수, 합계는 SQL NULL(빈 칸)도 허용, 무거래 플래그는 0/1만.
#   무거래 1 행도 형식을 먼저 검사한다. 개수 0 인데 합계 있음·원가 미상 수 > 체결 수는 형식 이상. 오류 출력에 원문 값을 복사하지 않는다.
import re, sys
from decimal import Decimal
def bad(m):
    print('🔴 배포 전 읽기 v3 형식 이상: ' + m); sys.exit(3)
try:
    with open(sys.argv[1], 'rb') as fh:
        L = fh.read().decode('utf-8').split('\n')
except (OSError, UnicodeDecodeError) as e:
    bad('파일 읽기 실패(%s)' % type(e).__name__)
if L and L[-1] == '':
    L.pop()
if len(L) < 4:
    bad('줄 수 %d < 4' % len(L))
INT = re.compile(r'[0-9]+'); NUM = re.compile(r'-?[0-9]+(\.[0-9]+)?'); UQ = re.compile(r'[0-9]+(\.[0-9]+)?')
r1, r2 = {}, {}
for i, k, pat, d in ((0, 'LIVE', INT, r1), (1, 'DYNAMIC', INT, r1), (2, 'LIVE', NUM, r2), (3, 'DYNAMIC', NUM, r2)):
    f = L[i].split(' | ')
    if len(f) != 2 or f[0] != k or not pat.fullmatch(f[1]):
        bad('%d줄(%s)' % (i + 1, 'R1' if i < 2 else 'R2'))
    d[k] = f[1]
def num(x, pat, i, j):
    if x == '':
        return None
    if not pat.fullmatch(x):
        bad('R3 %d줄 %d열 수치 형식' % (i, j + 1))
    return Decimal(x)
rows, und, sess = 0, 0, {}
for i, x in enumerate(L[4:], 5):
    f = x.split(' | ')
    if len(f) != 16 or f[10] not in ('LIVE', 'DYNAMIC') or not all(INT.fullmatch(f[j]) for j in (0, 1, 2, 5, 8, 11, 15)):
        bad('R3 %d줄(열 %d)' % (i, len(f)))
    if f[9] not in ('0', '1'):
        bad('R3 %d줄 무거래 플래그가 0/1 아님' % i)
    cost, bq, net, sq = num(f[3], NUM, i, 3), num(f[4], UQ, i, 4), num(f[6], NUM, i, 6), num(f[7], UQ, i, 7)
    if (f[1] == '0' and (cost is not None or bq is not None)) or (f[5] == '0' and (net is not None or sq is not None)) or int(f[2]) > int(f[1]):
        bad('R3 %d줄 개수와 합계가 맞지 않음' % i)
    rows += 1
    if f[9] == '1':
        continue
    u = f[1] == '0' or f[5] == '0' or f[2] != '0' or f[8] != '0' or cost is None or bq is None or net is None or sq is None or sq < bq
    if u:
        und += 1; s = f[10] + ':' + f[11]; sess[s] = sess.get(s, 0) + 1
print('R1 판정 불가(30일) LIVE %s · DYNAMIC %s' % (r1['LIVE'], r1['DYNAMIC']))
print('R2 손실 합(30일, 기록만) LIVE %s · DYNAMIC %s' % (r2['LIVE'], r2['DYNAMIC']))
print('R3 RUNNING REAL 세션 청산 행 %d · 판정 불가 %d%s' % (rows, und, (' (' + ', '.join('%s %d' % kv for kv in sorted(sess.items())) + ')') if sess else ''))
if r1['LIVE'] != '0' or r1['DYNAMIC'] != '0' or und:
    print('판정 | 🔴 보수적 사전 기준 미충족 — R1 판정 불가 또는 R3 판정 불가 행이 있다(비용 원천 사전 확인 기준. 제품 진입 결과라고 단정하지 않는다)'); sys.exit(1)
print('판정 | 진행 가능(R1 두 종류 0 · R3 판정 불가 0)')
PYEOF
}
$PSQL -At -F ' | ' < "$M/predeploy-read.sql" > "$M/predeploy-read.txt" 2>&1 || { cat "$M/predeploy-read.txt"; stop "🔴 배포 전 읽기 v3 조회 실패 — 배포 전 중단·보고(빌드·체크아웃·재기동으로 넘어가지 않는다)"; }
pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" && PV=0 || PV=$?; cat "$M/predeploy-verdict.txt"
[ "$PV" = 0 ] || stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 $PV — 1 보수적 사전 기준 미충족 / 3 형식 이상·읽기 실패) — 배포 전 중단·보고. 한도·자본·세션·과거 행을 바꾸지 않는다(원문 predeploy-read.txt 보존)"
rm -f "$M/image-new.txt" "$M/image-new-chain.txt"   # V172D4 R2(D81D4 는 frontend 빌드 기록이 없다): 이번 빌드판 기록은 이 U0 뒤의 U1 만 쓴다(앞 시도 기록을 이번 빌드로 오인하지 않게, 모든 확인 통과 뒤에만 지운다)
touch "$M/ok-U0"; echo "✅ U0 통과 — 30분 안에 U1(매시 :14~:24 는 피한다)"
)
