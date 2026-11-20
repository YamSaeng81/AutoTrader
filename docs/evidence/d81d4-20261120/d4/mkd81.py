# D81D4 생성기(총괄 v1.198 §3) — V172D4 원문(ce6df2fa 커밋의 blocks-d4 10블록)에서 지정한 곳만 바꿔 backend 전용 새 실행 블록을 만든다.
# 사용: python -I mkd81.py <저장소 경로> <출력 폴더(없는 경로)>
#   - 원문·값은 고정 Git 객체에서만 읽는다(작업 사본 파일을 읽지 않는다).
#   - 바꿀 곳마다 일치 횟수를 단언한다. 하나라도 어긋나면 아무것도 쓰지 않고 종료 2.
#   - UF2(frontend 교체)는 만들지 않는다. U2확인(c584166b 계열 d456788f)은 SQL 계약만 v96h() 로 옮기고 블록은 넣지 않는다.
import hashlib, os, re, subprocess, sys

REPO, OUT = sys.argv[1], sys.argv[2]
SRC = "ce6df2fa2b1b4a0b089576bc700fb867a466686d"          # V172D4 원문(424차 수용)
SRCP = "docs/evidence/v172d4-20261116/d4/blocks-d4/"
U2C = "d456788f"                                           # U2확인 보정 출처(426차, 결과 7fea1b89 수용)
U2CP = "docs/evidence/v172u2c-20261116/u2c/U2확인.sh"
EV = "7c62a7db73e236e1664745e1fb0037ba45516182"           # 447차(v1.198 수용) — jar 차이 목록 고정 객체
JDP = "docs/evidence/dec-final-abc-20261119/jar-d81c3f2f/jar-diff-vs-0e1d2a02.txt"
OLD = "0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d"
NEW = "d81c3f2f91c0239aacb0e1176dde9b898f4d7466"
GS = "e97ca31b23dec54db7c962395d2f8e60cc92afb0"
GTP = "docs/evidence/dec-costbasis-followup-20261119/rollback-gate-v4-draft"
PFP = "docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql"
NAMES = ["U변수", "U0", "U1", "U1되돌림", "U2", "U3", "U4", "U되돌림", "U뒤관측"]


def git(*a, raw=False):
    b = subprocess.run(["git", "-C", REPO] + list(a), check=True, capture_output=True).stdout
    return b if raw else b.decode("utf-8")


def h(b):
    return hashlib.sha256(b).hexdigest()


ERR = []
B = {n: git("show", SRC + ":" + SRCP + n + ".sh", raw=True).decode("utf-8") for n in NAMES}
SRCSHA = {n: h(B[n].encode("utf-8")) for n in NAMES}


def rep(n, old, new, cnt=1):
    c = B[n].count(old)
    if c != cnt:
        ERR.append("%s: 일치 %d회(기대 %d): %.90s" % (n, c, cnt, old.replace("\n", "⏎")))
        return
    B[n] = B[n].replace(old, new)


def line(n, prefix, new, cnt=1):   # prefix 로 시작하는 줄 전체를 바꾼다(new=None 이면 지운다)
    L = B[n].split("\n")
    idx = [i for i, x in enumerate(L) if x.startswith(prefix)]
    if len(idx) != cnt:
        ERR.append("%s: 줄 머리 일치 %d회(기대 %d): %.90s" % (n, len(idx), cnt, prefix))
        return
    for i in reversed(idx):
        if new is None:
            del L[i]
        else:
            L[i] = new
    B[n] = "\n".join(L)


def between(n, start, end, new):   # start 줄부터 end 줄까지(둘 다 포함, 줄 머리 일치) 를 new 로
    L = B[n].split("\n")
    s = [i for i, x in enumerate(L) if x.startswith(start)]
    e = [i for i, x in enumerate(L) if x.startswith(end)]
    if len(s) != 1 or len(e) != 1 or e[0] < s[0]:
        ERR.append("%s: 구간 머리 %d·끝 %d: %.60s … %.60s" % (n, len(s), len(e), start, end))
        return
    L[s[0]:e[0] + 1] = new.split("\n") if new else []
    B[n] = "\n".join(L)


# ── 고정 값(기계 생성) ──
DIFF = git("diff", "--name-only", OLD, NEW).split("\n")
DIFF = [x for x in DIFF if x]
prod = [x for x in DIFF if "/src/main/" in x]
test = [x for x in DIFF if "/src/test/" in x]
if len(DIFF) != 43 or len(prod) != 14 or len(test) != 29 or any(re.search(r"(resources/|db/migration|frontend)", x) for x in prod):
    ERR.append("DIFFN 형식: 전체 %d 제품 %d 시험 %d" % (len(DIFF), len(prod), len(test)))
JD = git("show", EV + ":" + JDP).split("\n")
RAR = [x for x in JD[1:] if x]
if not JD[0].startswith("기준 0e1d2a02(Git LF 기대 895, JARM b6587953) 895 -> d81c3f2f 898 | 같음 860 변경 35 추가 3 삭제 0") or len(RAR) != 38:
    ERR.append("jar 차이 목록 머리·줄 수(%d)" % len(RAR))
RAR = sorted(RAR, key=lambda x: x.split(" ", 1)[1])   # 저장 목록은 종류별 묶음 — U1 비교기 출력 순서(항목 이름순)로 맞춘다(실제 jar 대조 jarcmp-real/ 과 같은 순서)
keys = [x.split(" ", 1)[1] for x in RAR]
if len(set(keys)) != 38 or sum(x.startswith("변경 ") for x in RAR) != 35 or sum(x.startswith("추가 ") for x in RAR) != 3:
    ERR.append("jar 차이 목록이 비교기 출력 순서(이름순)·변경35·추가3 이 아님")
if any("'" in x for x in RAR + DIFF):
    ERR.append("목록에 작은따옴표")
CLS = sorted(set(x.split(" ", 1)[1] for x in RAR))
GATESQL = h(git("show", GS + ":" + GTP + "/gate.sql", raw=True))
GATESH = h(git("show", GS + ":" + GTP + "/rollback_gate.sh", raw=True))
PFSUM = h(git("show", GS + ":" + PFP, raw=True))
for k, v, x in (("GATESQL", GATESQL, "b24239f82f09a6fcfcf36026a10614d3d7e7df3687fd720e0336fe1b84d77da0"),
                ("GATESH", GATESH, "0b391e58180d334f1f0d38ac88f7186ae03a6b233c945b54df19eaba8aa16906"),
                ("PFSUM", PFSUM, "ca6e7c8d6cc67ee967ac21b7847f7230cc859dc3cb9dc895da6f7694dfe26aa3")):
    if v != x:
        ERR.append("%s %s ≠ 수용 값 %s" % (k, v, x))
for c in (OLD, NEW):
    if git("rev-parse", c + ":scripts/prospective/start_66.py").strip() != "6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d":
        ERR.append("start_66.py 블롭 %s" % c[:8])
    if git("rev-parse", c + ":web-api/src/main/resources/db/migration/V96__paper_aligned_runtime_state.sql").strip() != "5b92eef03548c6bd1feaef2b35451563d5327246":
        ERR.append("V96 블롭 %s" % c[:8])
U2CS = git("show", U2C + ":" + U2CP)
V96HSQL = "SELECT count(*) || '|' || coalesce(string_agg(version || ':' || success || ':' || coalesce(checksum::text, 'NULL'), ',' ORDER BY installed_rank), '-') FROM flyway_schema_history WHERE version::int >= 95"
if U2CS.count(V96HSQL) != 1 or h(U2CS.encode("utf-8")) != "1c9eab3582d8574371501ebb1cc693408bd822f66f752261ad0c8997ddbb7cc2":
    ERR.append("U2확인 SQL·원문 해시(1c9eab35) 대조 실패")

IMGOLD = "sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f"
IMGOLDM = "sha256:b3a42cd2dd9947e6dffc5ccec71de077b3d2fccfc810d21796378be73b05aa0c"
IMGOLDC = "sha256:4016cc389044ba95a88f9048f6b14de7118e276c75f9a524998e2b8baa50ac34"
FETOLD = "sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e"
FEMOLD = "sha256:13343943ed4ab0db003bf40e77cb38c9d41e67287a7d51fcb6da818ad6569d93"
FECOLD = "sha256:11d9a954a3054d3eff3c6d2d798fad55beefce3a166c9335259d8fa6367e4f89"
JARM = "33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509"
OLDJARM = "b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8"

UBOLD = '[ "${UB:-}" = V172D4 ] || stop "U 변수가 v1.172 통합 후보 초안 D4 판이 아님 — F → G → v1.172 통합 후보 초안 D4 U 변수(초안은 실행하지 않는다)"'
UBNEW = '[ "${UB:-}" = D81D4 ] || stop "U 변수가 v1.198 backend 전용 D81D4 판이 아님 — F → G → D81D4 U 변수(초안은 실행하지 않는다)"'
for n in ["U1", "U1되돌림", "U2", "U3", "U4", "U되돌림", "U뒤관측"]:
    rep(n, UBOLD, UBNEW)

# ════════ U변수 ════════
n = "U변수"
line(n, "M=~/maint-v172; ", 'M=~/maint-d81; mkdir -p "$M"; OPS=~/ops-scripts; UB=D81D4   # 총괄 v1.198 §3 판 — 후보 d81c3f2f backend 전용 배포 D4(미승인·실행 금지). 생성기 mkd81.py 가 V172D4 원문(ce6df2fa, 이후 U2확인 보정 d456788f·결과 7fea1b89 수용)에서 지정 부분만 바꿨다. 원판 = 현재 운영 0e1d2a02 / backend 2c5d8427 / frontend 9d7f123a / DB V96. frontend 교체·빌드·태그 변경 없음, DDL 없음(V96 기적용). U 블록 전부가 이 표지를 확인한다')
line(n, "OLD=b5cd1d0d", "OLD=%s; NEW=%s" % (OLD, NEW))
line(n, "IMGOLD=sha256:52e12428", "IMGOLD=%s   # 운영 0e1d2a02 backend 대상 ID(V172D4 U1 빌드, ~/maint-v172/image-new.txt · 427차 서버 보존본 maint-v172/image-new-chain.txt 11-16 관측) — U0 가 실행 이미지·그 기록 파일과 같은지 확인한다. 현재값이라고 가정하지 않는다" % IMGOLD)
line(n, "IMGPREV5=", None)
rep(n, "IMGPREV=sha256:618046540764", "IMGPREV5=sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62   # #23 이미지 — 이전 복구 태그 autotrader-backend:1103f48 가 가리킨다(#24 T1 이 만듦, 보존, 바꾸지 않는다)\n"
    "IMGPREV9=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055   # #27 이미지(b5cd1d0) — 보존 태그 autotrader-backend:b5cd1d0 이 가리킨다(V168·V172 U1). D81D4 의 복구 대상이 아니다 — 보존만, 만들거나 바꾸지 않는다\n"
    "FEPREV9=sha256:837db515e0eb8b9cb4bcf8f03ed3b98adf9f8cdb176d13777949c792d10b5017   # b5cd1d0 판 frontend 대상 — 보존 태그 autotrader-frontend:b5cd1d0 이 가리킨다(V172D4 U1 11-16 이 만듦). 보존만\n"
    "IMGPREV=sha256:618046540764")
line(n, "v94() {", "v96h() { local o; o=$($PSQL -At -c \"%s\") || { echo \"🔴 V95 이후 이력 조회 실패\" >&2; return 2; }; printf 'V95 이후 이력(행수|내용): %%s\\n' \"$o\"; [ \"$o\" = \"1|96:true:$V96SUM\" ] ; }   # U2확인(d456788f 1c9eab35) 계약 — 행수·내용 한 SELECT, 체크섬 NULL 은 'NULL' 글자. 통과는 1|96:true:-424836241 하나뿐(종료 1 다름 · 2 조회 실패)" % V96HSQL.replace('"', '\\"'))
between(n, 'DIFFN="$(printf', "  web-api/src/test/resources/schema-h2.sql)\"   #",
        "DIFFN=\"$(printf '%s\\n' " + " \\\n  ".join(DIFF) + ")\"   # git diff --name-only OLD NEW — 43경로(제품 Java 14·시험 29, 리소스·마이그레이션·프런트·build.gradle 0). mkd81.py 가 고정 Git 에서 기계 생성")
between(n, "RARX=\"$(printf '%s\\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/controller/PaperTradingController.class'", "  '추가 BOOT-INF/classes/db/migration/V96__paper_aligned_runtime_state.sql')\"   #",
        "RARX=\"$(printf '%s\\n' " + " \\\n  ".join("'%s'" % x for x in RAR) + ")\"   # 운영 0e1d2a02 Git LF 기대 895항목 대 후보 d81c3f2f LF jar 898항목(eae7e1d2…), 비교기 v2 — 변경 35·추가 3·삭제 0(v1.197 수용 목록, 447차 7c62a7db 고정 객체에서 기계 생성)")
line(n, 'JARN="(끝) 881 895"', 'JARN="(끝) 895 898"   # 총괄 v1.198 §2 확정 — 운영 LF 895항목 → 후보 898항목')
line(n, "JARM=" + OLDJARM, "JARM=%s   # 총괄 v1.197 §2 수용 — 후보 d81c3f2f LF jar 항목 이름·sha256 목록(898줄, dec-final-abc-20261119/jar-d81c3f2f/jar-manifest-d81c3f2f.lf.txt)의 sha256" % JARM)
line(n, "FEIMGOLD=sha256:d825a9f5", "FEIMGOLD=%s   # 실행 중 frontend 컨테이너 .Image — 427차 서버 보존본 maint-v172/svc-fe.txt(11-16 UF2 뒤 관측, 새로 만든 컨테이너라 대상 ID 와 같다). 현재값이라고 쓰지 않는다 — U0 가 다시 대조하고 다르면 멈춘다" % FETOLD)
line(n, "IMGOLDM=sha256:ca440b84", "IMGOLDM=%s; IMGOLDC=%s   # 운영 backend 대상 2c5d8427 의 linux/amd64 manifest·config — 427차 보존본 image-new-chain.txt(11-16 관측 기대값)" % (IMGOLDM, IMGOLDC))
line(n, "FETOLD=sha256:837db515", "FETOLD=%s; FEMOLD=%s; FECOLD=%s   # 실행 중 frontend: 이미지 이름 autotrader-frontend 의 대상 ID·linux/amd64 manifest·config — 427차 보존본 image-fe-new-chain.txt(11-16 관측 기대값)" % (FETOLD, FEMOLD, FECOLD))
rep(n, "블록이 ~/maint-v172/imgchain.py 로 쓰고", "블록이 $M/imgchain.py 로 쓰고")
line(n, "GS=6385e1d5", "GS=%s   # 관문 4판·배포 전 읽기 v3 를 담은 main 고정 커밋(445차, 총괄 v1.198 수용). 꺼낸 원문 해시가 GATESQL·GATESH·PFSUM 과 다르면 U0·U되돌림이 멈춘다" % GS)
line(n, "GTP=docs/evidence/v159-20261113/rollback-gate-v3", "GTP=%s; GATESQL=%s; GATESH=%s   # 상태 기반 복구 관문 4판(3판 + STOP_INCOMPATIBLE) 원문 해시" % (GTP, GATESQL, GATESH))
line(n, "PFP=docs/evidence/v159-20261113/d4-draft", "PFP=%s; PFSUM=%s   # 배포 전 읽기 v3(R1 판정 불가·R2 손실 합·R3 세션 연속 손실 입력) 원문 해시 — 판정 줄이 없어 아래 pf3ok 가 가른다" % (PFP, PFSUM))
rep(n, "gsok() { case \"$GS\" in 미정*|\"\") return 1 ;; esac; git cat-file -e \"$GS^{commit}\" 2>/dev/null ; }",
    "gsok() { case \"$GS\" in 미정*|\"\") return 1 ;; esac; git cat-file -e \"$GS^{commit}\" 2>/dev/null ; }\n"
    "# ── D81D4(총괄 v1.198 §3) — 배포 전 읽기 v3 판정. 원문 출력을 바꾸지 않고 개수만 낸다(가격·손익 값·행 원문은 출력하지 않는다) ──\n"
    "pf3ok() { python3 - \"$1\" <<'PYEOF'\n"
    "# 인자: $PSQL -At -F ' | ' < predeploy_read.v3.sql 의 출력 파일. 기대: 1~2줄 R1(LIVE|정수, DYNAMIC|정수) · 3~4줄 R2(LIVE|수, DYNAMIC|수) · 5줄부터 R3 16열(0행 이상).\n"
    "# 종료 0 = 진행 가능(R1 두 종류 0, R3 판정 불가 행 0) · 1 = 🔴 진입 거절·판정 불가 예상(보고) · 3 = 형식 이상(조회 결과로 보지 않는다).\n"
    "# R3 판정 불가(보수적): 무거래 확정이 아닌데 체결BUY 0·확정SELL 0·원가 미상 BUY·필수값 미상 SELL·원가/수량 합 없음·매도수량<매수수량 중 하나. 연속 손실 한도 안 여부는 여기서 줄이지 않는다.\n"
    "import re, sys\n"
    "from decimal import Decimal, InvalidOperation\n"
    "L = open(sys.argv[1], encoding='utf-8').read().split('\\n')\n"
    "if L and L[-1] == '':\n"
    "    L.pop()\n"
    "def bad(m):\n"
    "    print('🔴 배포 전 읽기 v3 형식 이상: ' + m); sys.exit(3)\n"
    "if len(L) < 4:\n"
    "    bad('줄 수 %d < 4' % len(L))\n"
    "INT = re.compile(r'^[0-9]+$'); NUM = re.compile(r'^-?[0-9]+(\\.[0-9]+)?$')\n"
    "r1, r2 = {}, {}\n"
    "for i, k, pat, d in ((0, 'LIVE', INT, r1), (1, 'DYNAMIC', INT, r1), (2, 'LIVE', NUM, r2), (3, 'DYNAMIC', NUM, r2)):\n"
    "    f = L[i].split(' | ')\n"
    "    if len(f) != 2 or f[0] != k or not pat.match(f[1]):\n"
    "        bad('%d줄(%s)' % (i + 1, 'R1' if i < 2 else 'R2'))\n"
    "    d[k] = f[1]\n"
    "def dec(x):\n"
    "    try:\n"
    "        return Decimal(x)\n"
    "    except InvalidOperation:\n"
    "        return None\n"
    "rows, und, sess = 0, 0, {}\n"
    "for i, x in enumerate(L[4:], 5):\n"
    "    f = x.split(' | ')\n"
    "    if len(f) != 16 or f[10] not in ('LIVE', 'DYNAMIC') or not all(INT.match(f[j]) for j in (0, 1, 2, 5, 8, 9, 11, 15)):\n"
    "        bad('R3 %d줄(열 %d)' % (i, len(f)))\n"
    "    rows += 1\n"
    "    if f[9] == '1':\n"
    "        continue\n"
    "    bq, sq = dec(f[4]) if f[4] else None, dec(f[7]) if f[7] else None\n"
    "    u = f[1] == '0' or f[5] == '0' or f[2] != '0' or f[8] != '0' or f[3] == '' or bq is None or sq is None or sq < bq\n"
    "    if u:\n"
    "        und += 1; s = f[10] + ':' + f[11]; sess[s] = sess.get(s, 0) + 1\n"
    "print('R1 판정 불가(30일) LIVE %s · DYNAMIC %s' % (r1['LIVE'], r1['DYNAMIC']))\n"
    "print('R2 손실 합(30일, 기록만) LIVE %s · DYNAMIC %s' % (r2['LIVE'], r2['DYNAMIC']))\n"
    "print('R3 RUNNING REAL 세션 청산 행 %d · 판정 불가 %d%s' % (rows, und, (' (' + ', '.join('%s %d' % kv for kv in sorted(sess.items())) + ')') if sess else ''))\n"
    "if r1['LIVE'] != '0' or r1['DYNAMIC'] != '0' or und:\n"
    "    print('판정 | 🔴 진행 불가 — R1 판정 불가 또는 R3 판정 불가 행이 있다(배포 직후 그 종류·세션의 신규 REAL 진입 거절 예상)'); sys.exit(1)\n"
    "print('판정 | 진행 가능(R1 두 종류 0 · R3 판정 불가 0)')\n"
    "PYEOF\n"
    "}\n"
    "oldtag() {   # oldtag <옛 보존 태그> <기대 대상 ID> → 0 있음·같음 · 1 명시적 부재(API 404) · 2 다른 대상 · 3 조회 불명. 만들거나 바꾸지 않는다(이번 복구 대상이 아님)\n"
    "  local q=0 id\n"
    "  tagq \"$1\" || q=$?\n"
    "  case \"$q\" in 0) ;; 1) return 1 ;; *) return 3 ;; esac\n"
    "  id=$(docker image inspect --format '{{.Id}}' \"$1\") || { echo \"🔴 $1 대상 ID 조회 불명\" >&2; return 3; }\n"
    "  [ \"$id\" = \"$2\" ] || { echo \"🔴 $1 이 ${id:7:12}… 를 가리킨다(기대 ${2:7:12}…)\" >&2; return 2; }\n"
    "}")
rep(n, "# ── 감시 스크립트 고정값 — 운영 b5cd1d0 과 후보의", "# ── 감시 스크립트 고정값 — 운영 0e1d2a02 과 후보 d81c3f2f 의")
rep(n, "# start_66.py 블롭(운영 b5cd1d0 · 후보 — 같다)", "# start_66.py 블롭(운영 0e1d2a02 · 후보 d81c3f2f — 같다, mkd81.py 확인)")

# ════════ U0 ════════
n = "U0"
rep(n, '[ "$M" = "$HOME/maint-v172" ] && [ "${UB:-}" = V172D4 ] && [ "$NEW" = 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d ] && [ -n "${JARN:-}" ] && [ "${JARM:-}" = %s ] && [ -n "${ICSUM:-}" ]' % OLDJARM,
    '[ "$M" = "$HOME/maint-d81" ] && [ "${UB:-}" = D81D4 ] && [ "$OLD" = %s ] && [ "$NEW" = %s ] && [ "${JARN:-}" = "(끝) 895 898" ] && [ "${JARM:-}" = %s ] && [ -n "${ICSUM:-}" ] && type v96h > /dev/null 2>&1 && type pf3ok > /dev/null 2>&1 && type oldtag > /dev/null 2>&1' % (OLD, NEW, JARM))
rep(n, '|| stop "변수 순서 — F 변수 → G 변수 → v1.172 통합 후보 초안 D4 U 변수"', '|| stop "변수 순서 — F 변수 → G 변수 → D81D4 U 변수"')
rep(n, 'stop "HEAD 가 b5cd1d0 가 아니거나 추적 파일 변경 있음"', 'stop "HEAD 가 운영 0e1d2a02 가 아니거나 추적 파일 변경 있음"')
rep(n, 'stop "후보 802b691 없음"', 'stop "후보 d81c3f2f 없음"')
rep(n, 'stop "b5cd1d0 가 후보의 조상이 아님"', 'stop "0e1d2a02 가 후보의 조상이 아님"')
rep(n, 'stop "diff 가 정확히 기대 133경로(시험 103·제품 Java 20·V96 1·build.gradle 1·프런트 8)가 아님"', 'stop "diff 가 정확히 기대 43경로(제품 Java 14·시험 29)가 아님"')
rep(n, '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-o13n21/image-new.txt)" ] || stop "실행 이미지가 #27 배포 이미지(~/maint-o13n21/image-new.txt)와 다름"',
    '[ "$IMG0" = "$IMGOLD" ] && [ "$IMGOLD" = "$(cat ~/maint-v172/image-new.txt)" ] || stop "실행 이미지가 운영 0e1d2a02 배포 이미지(~/maint-v172/image-new.txt, 2c5d8427)와 다름 — 보고"')
rep(n, "[ \"$(docker image inspect --format '{{.Id}}' $REPO:latest)\" = \"$IMG0\" ] || stop \"$REPO:latest 가 실행 이미지와 다름\"",
    "OT=0; oldtag $REPO:b5cd1d0 \"$IMGPREV9\" || OT=$?   # D81D4: 옛 보존 태그 두 개 — 있음·같음 / 명시적 부재(기록만) / 다른 대상·조회 불명(멈춤). 만들거나 바꾸지 않는다\n"
    "case \"$OT\" in 0) echo \"옛 보존 태그 $REPO:b5cd1d0 = #27(52e12428…) 그대로\" ;; 1) echo \"⚪ 옛 보존 태그 $REPO:b5cd1d0 없음(API 404) — 이번 복구 대상이 아니므로 만들지 않는다. 기록만\" ;; 2) stop \"옛 보존 태그 $REPO:b5cd1d0 이 #27 이미지가 아닌 대상을 가리킨다 — 보고\" ;; *) stop \"옛 보존 태그 $REPO:b5cd1d0 조회 불명 — 없음으로 보지 않는다. 보고\" ;; esac\n"
    "OF=0; oldtag autotrader-frontend:b5cd1d0 \"$FEPREV9\" || OF=$?\n"
    "case \"$OF\" in 0) echo \"옛 보존 태그 autotrader-frontend:b5cd1d0 = 837db515… 그대로\" ;; 1) echo \"⚪ 옛 보존 태그 autotrader-frontend:b5cd1d0 없음(API 404) — 만들지 않는다. 기록만\" ;; 2) stop \"옛 보존 태그 autotrader-frontend:b5cd1d0 이 837db515… 가 아닌 대상을 가리킨다 — 보고\" ;; *) stop \"옛 보존 태그 autotrader-frontend:b5cd1d0 조회 불명 — 없음으로 보지 않는다. 보고\" ;; esac\n"
    "echo \"옛 보존 태그 판정 backend:b5cd1d0=$OT frontend:b5cd1d0=$OF (0 같음 / 1 명시적 부재)\" | tee \"$M/oldtag-before.txt\"\n"
    "[ \"$(docker image inspect --format '{{.Id}}' $REPO:latest)\" = \"$IMG0\" ] || stop \"$REPO:latest 가 실행 이미지와 다름\"")
line(n, 'rm -f "$M/fe-stage.txt"', None)
rep(n, 'stop "#27 backend 이미지의 manifest·config 가 고정값(ca440b84…·0344757e…)과 다름 — 보고"', 'stop "운영 backend 이미지(2c5d8427)의 manifest·config 가 11-16 기대값(b3a42cd2…·4016cc38…)과 다름 — 기대값을 자동 교체하지 않는다. 보고"')
rep(n, 'stop "실행 중 backend 컨테이너가 #27 이미지 manifest·config 와 연결되지 않음 — 보고"', 'stop "실행 중 backend 컨테이너가 운영 2c5d8427 이미지 manifest·config 와 연결되지 않음 — 보고"')
rep(n, 'TKB=0; tagkeep $REPO:b5cd1d0 "$M/image-before-chain.txt" || TKB=$?   # V172D4 R1', 'TKB=0; tagkeep $REPO:0e1d2a0 "$M/image-before-chain.txt" || TKB=$?   # D81D4 새 보존 태그(V172D4 R1 네 갈래)')
rep(n, 'case "$TKB" in 0) echo "보존 태그 $REPO:b5cd1d0 있음 — #27 backend 대응과 같음" ;; 1) echo "보존 태그 $REPO:b5cd1d0 없음(API 404 확인) — U1 이 만든다" ;; 2) stop "보존 태그 $REPO:b5cd1d0 이 이미 다른 이미지를 가리킨다 — 보고" ;; *) stop "보존 태그 $REPO:b5cd1d0 조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다. 보고" ;; esac',
    'case "$TKB" in 0) echo "보존 태그 $REPO:0e1d2a0 있음 — 운영 backend 대응과 같음" ;; 1) echo "보존 태그 $REPO:0e1d2a0 없음(API 404 확인) — U1 이 만든다" ;; 2) stop "보존 태그 $REPO:0e1d2a0 이 이미 다른 이미지를 가리킨다 — 보고" ;; *) stop "보존 태그 $REPO:0e1d2a0 조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다. 보고" ;; esac')
rep(n, 'v94 || stop "Flyway 최고 버전이 94 가 아님"',
    'v96 || stop "Flyway 최고 버전이 96 이 아님 — 보고(D81D4 는 V96 기적용 상태에서만)"\n'
    'v96h > "$M/v96-before.txt" && VH=0 || VH=$?; cat "$M/v96-before.txt"\n'
    '[ "$VH" = 0 ] || stop "V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH) — 보고"')
rep(n, "entryobs 종료 $ED — b5cd1d0 실행 중 로그", "entryobs 종료 $ED — 0e1d2a02 실행 중 로그")
rep(n, "N17 줄(b5cd1d0 실행 중 로그", "N17 줄(0e1d2a02 실행 중 로그")
rep(n, "N19 줄(b5cd1d0 실행 중 로그", "N19 줄(0e1d2a02 실행 중 로그")
rep(n, "N20 줄(b5cd1d0 실행 중 로그", "N20 줄(0e1d2a02 실행 중 로그")
rep(n, 'stop "start_66.py 가 b5cd1d0 원문(68d441ce…)과 다름"', 'stop "start_66.py 가 운영 0e1d2a02 원문(68d441ce…)과 다름"')
rep(n, 'echo "== v1.159 추가 확인 — V95 없음·V96 원문·frontend 실행 이미지·배포 전 손실 관문(읽기 전용)"', 'echo "== 추가 확인 — 후보 V95 없음·V96 원문 같음·frontend 실행 이미지(교체하지 않음)·배포 전 읽기 v3(읽기 전용)"')
rep(n, 'stop "frontend 이미지 이름이 고정 대상·manifest·config(837db515…·fa5c9ba8…·80ce7550…)와 다름 — 보고"', 'stop "frontend 이미지 이름이 11-16 기대 대상·manifest·config(9d7f123a…·13343943…·11d9a954…)와 다름 — 기대값을 자동 교체하지 않는다. 보고"')
line(n, 'TKF=0; tagkeep autotrader-frontend:b5cd1d0', None)
line(n, 'case "$TKF" in', 'echo "frontend 는 이 판에서 빌드·태그·재생성하지 않는다 — 시작 기동 기록(svc-before.txt frontend 줄)과 U0 대응(image-fe-before-chain.txt)을 U4 까지 직접 대조한다"')
rep(n, 'git show "$GS:$PFP" > "$M/preflight-loss.sql" && [ "$(h256 "$M/preflight-loss.sql")" = "$PFSUM" ] || stop "배포 전 손실 조회문 원문·해시가 다름"',
    'git show "$GS:$PFP" > "$M/predeploy-read.sql" && [ "$(h256 "$M/predeploy-read.sql")" = "$PFSUM" ] || stop "배포 전 읽기 v3 원문·해시가 다름"')
rep(n, '$PSQL -At -F \' | \' < "$M/preflight-loss.sql" > "$M/preflight-loss.txt" 2>&1 || { cat "$M/preflight-loss.txt"; stop "🔴 배포 전 손실 조회 실패 — 배포 전 중단·보고(빌드·체크아웃·재기동으로 넘어가지 않는다)"; }',
    '$PSQL -At -F \' | \' < "$M/predeploy-read.sql" > "$M/predeploy-read.txt" 2>&1 || { cat "$M/predeploy-read.txt"; stop "🔴 배포 전 읽기 v3 조회 실패 — 배포 전 중단·보고(빌드·체크아웃·재기동으로 넘어가지 않는다)"; }')
rep(n, 'cat "$M/preflight-loss.txt"\n', 'pf3ok "$M/predeploy-read.txt" > "$M/predeploy-verdict.txt" && PV=0 || PV=$?; cat "$M/predeploy-verdict.txt"\n')
rep(n, '[ "$(grep -c \'^판정 | \' "$M/preflight-loss.txt")" = 1 ] && grep -qE \'^판정 \\| - \\| .*진행 가능\\(손실 세 보호식 모두 허용\\)$\' "$M/preflight-loss.txt" || stop "🔴 배포 전 손실 관문이 진행 가능이 아님(거절·판정 불가·필수값 누락) — 배포 전 중단·보고. 한도·자본·세션·손실 행을 바꾸지 않는다"',
    '[ "$PV" = 0 ] || stop "🔴 배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 $PV — 1 판정 불가·진입 거절 예상 / 3 형식 이상) — 배포 전 중단·보고. 한도·자본·세션·과거 행을 바꾸지 않는다(원문 predeploy-read.txt 보존)"')
rep(n, 'rm -f "$M/image-new.txt" "$M/image-new-chain.txt" "$M/image-fe-new.txt" "$M/image-fe-new-chain.txt"   # V172D4 R2', 'rm -f "$M/image-new.txt" "$M/image-new-chain.txt"   # V172D4 R2(D81D4 는 frontend 빌드 기록이 없다)')

# ════════ U1 ════════
n = "U1"
rep(n, "BK=0; tagkeep $REPO:b5cd1d0 \"$M/image-before-chain.txt\" || BK=$?   # V172D4 R1: 두 보존 태그를 모두 판정한 뒤에만 쓴다", "BK=0; tagkeep $REPO:0e1d2a0 \"$M/image-before-chain.txt\" || BK=$?   # V172D4 R1: 판정한 뒤에만 쓴다(D81D4 는 backend 보존 태그 하나)")
line(n, 'FK=0; tagkeep autotrader-frontend:b5cd1d0', None)
rep(n, 'echo "보존 태그 판정 backend=$BK frontend=$FK (0 있음·같음 / 1 명시적 부재 / 2 다른 대상 / 3 조회 불명)"', 'echo "보존 태그 판정 backend:0e1d2a0=$BK (0 있음·같음 / 1 명시적 부재 / 2 다른 대상 / 3 조회 불명) · frontend 보존 태그 없음(교체하지 않는다)"')
rep(n, 'case "$BK" in 0|1) ;; 2) stop "$REPO:b5cd1d0 태그가', 'case "$BK" in 0|1) ;; 2) stop "$REPO:0e1d2a0 태그가')
rep(n, '*) stop "$REPO:b5cd1d0 태그 조회 불명', '*) stop "$REPO:0e1d2a0 태그 조회 불명')
line(n, 'case "$FK" in', None)
rep(n, '[ "$BK" = 0 ] || docker tag "$IMGOLD" $REPO:b5cd1d0   # 명시적 부재일 때만 만든다', '[ "$BK" = 0 ] || docker tag "$IMGOLD" $REPO:0e1d2a0   # 명시적 부재일 때만 만든다')
rep(n, 'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패', 'same3 $REPO:0e1d2a0 "$M/image-before-chain.txt" || stop "기존 backend 이미지 보존 태그 실패')
line(n, '[ "$FK" = 0 ] || docker tag', None)
line(n, 'same3 autotrader-frontend:b5cd1d0 "$M/image-fe-before-chain.txt" || stop "기존 frontend 이미지 보존 태그 실패', None)
line(n, 'echo 보존태그 > "$M/fe-stage.txt"', None)
rep(n, 'stop "새 backend manifest 가 #27 과 같음 — U1-되돌림"', 'stop "새 backend manifest 가 운영 2c5d8427 과 같음 — U1-되돌림"')
rep(n, 'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 빌드 중 바뀌었거나', 'same3 $REPO:0e1d2a0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 빌드 중 바뀌었거나')
rep(n, 'stop "jar 차이가 기대 62줄(변경 48·추가 14, V96 SQL 포함)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"', 'stop "jar 차이가 기대 38줄(변경 35·추가 3·삭제 0)이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"')
rep(n, 'stop "항목 수가 881 → 895 가 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"', 'stop "항목 수가 895 → 898 이 아님 — jar-diff.txt 를 보고(재기동 전). U1-되돌림"')
rep(n, 'stop "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름', 'stop "새 jar 항목 내용이 후보 d81c3f2f LF 목록(JARM 33a06f0e…)과 다름')
between(n, 'echo "== frontend(같은 릴리스) — 보존 태그 확인·새 이미지 빌드(재기동 없음)"', 'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "🔴 실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"',
        'echo "== frontend 무교체 — 빌드·태그 변경 없음. backend 빌드 뒤에도 frontend 이미지 이름·실행 컨테이너가 U0 대응 그대로인지만 본다"\n'
        'FS3=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" || FS3=$?\n'
        '[ "$FS3" = 0 ] || stop "frontend 이미지 이름이 U0 대응과 $([ "$FS3" = 1 ] && echo 다름 || echo "대응 조회 불명") — frontend 를 바꾸지 않는다. 보고(U1-되돌림은 frontend 대응이 맞을 때만 HEAD·backend latest 를 되돌린다)"\n'
        'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "🔴 실행 중 frontend 가 바뀌었다 또는 조회 실패 — 보고"')
rep(n, 'echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 는 U3 뒤 UF2)"', 'echo "✅ U1 통과 — ⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 교체 없음)"')

# ════════ U1되돌림 ════════
n = "U1되돌림"
between(n, "# U1-되돌림(V172D4 — V171D4 에 총괄 v1.172 R2 보완)", "#   없음/보존태그 = frontend 빌드 전",
        "# U1-되돌림(D81D4 — V172D4 원문에서 frontend 단계를 뺐다) — U1 이 '… U1-되돌림' 으로 멈췄거나, U1 통과 뒤 운영자가 U2 를 하지 않기로 했을 때만 쓴다(자동 실행 없음). 재기동하지 않는다.\n"
        "# 대상은 HEAD=NEW 뿐이다(이미 OLD 이면 체크아웃 없이 backend latest·원문만 맞춘다). 그 밖 HEAD·추적 변경·실행 컨테이너 대응 불일치·cron 불일치·프로세스/이미지 조회 실패는 바꾸지 않고 멈춘다.\n"
        "# frontend 는 이 판에서 바꾸지 않는다 — 이미지 이름·실행 컨테이너가 U0 대응과 다르거나 조회 불명이면 아무것도 바꾸지 않고 멈춘다. 재기동 뒤 복구(U되돌림)와는 전제·출처 판정이 다르다.")
rep(n, 'stop "image-before 가 #27 이미지(IMGOLD)가 아님"', 'stop "image-before 가 운영 2c5d8427 이미지(IMGOLD)가 아님"')
rep(n, 'stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H)', 'stop "HEAD 가 후보·0e1d2a02 어느 것도 아님($H)')
line(n, 'FS=$(cat "$M/fe-stage.txt"', None)
line(n, 'case "$FS" in 없음|보존태그|빌드시작|빌드끝)', None)
rep(n, 'stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"', 'stop "실행 중 backend 가 운영 2c5d8427 대응이 아님 또는 조회 실패 — 재기동이 있었을 수 있다. 바꾸지 않고 보고"')
rep(n, 'echo "== V172D4 R2 — 두 이름의 출처 판정(원판·이번 빌드판·제3·조회 불명). 두 서비스 판정을 끝내기 전에는 HEAD·태그를 바꾸지 않는다"', 'echo "== V172D4 R2 — backend latest 출처 판정(원판·이번 빌드판·제3·조회 불명)과 frontend 이미지 이름 무변경 확인. 판정을 끝내기 전에는 HEAD·태그를 바꾸지 않는다"')
between(n, 'FN=$(namecls autotrader-frontend', 'case "$FN:$FS" in',
        'FC=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/u1rb-probe.txt" || FC=$?\n'
        'echo "이름 출처 판정 backend latest=$BN · frontend 이미지 이름 U0 대응 판정 $FC(0 같음 / 1 다름 / 2 조회 불명)" | tee "$M/u1rb-names.txt"\n'
        '[ "$BN" != 제3 ] || { tail -3 "$M/u1rb-probe.txt"; stop "backend latest 가 U0 원판·U1 이번 빌드 기록(image-new-chain) 어느 것도 아님 — 출처 미확인, 바꾸지 않고 보고"; }\n'
        'case "$FC" in 0) ;; 1) tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름이 U0 대응과 다름 — 이 판은 frontend 를 바꾸지 않았다(출처 미확인). 바꾸지 않고 보고" ;; *) tail -3 "$M/u1rb-probe.txt"; stop "frontend 이미지 이름 대응 조회 불명 — 같음으로 추정하지 않는다. 바꾸지 않고 보고" ;; esac')
line(n, 'FL=0; [ "$FN" = 원판 ] || FL=1', None)
rep(n, 'if [ "$BL" = 1 ]; then same3 $REPO:b5cd1d0 "$M/image-before-chain.txt"', 'if [ "$BL" = 1 ]; then same3 $REPO:0e1d2a0 "$M/image-before-chain.txt"')
line(n, 'if [ "$FL" = 1 ]; then same3 autotrader-frontend:b5cd1d0', None)
rep(n, 'echo "되돌릴 이미지 이름: backend latest $([ "$BL" = 1 ] && echo 보존 태그로 || echo 그대로) · frontend $([ "$FL" = 1 ] && echo 보존 태그로 || echo 그대로)"', 'echo "되돌릴 이미지 이름: backend latest $([ "$BL" = 1 ] && echo 보존 태그 대상으로 || echo 그대로) · frontend 그대로(무교체)"')
rep(n, 'echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"', 'echo "HEAD 가 이미 0e1d2a02 — 체크아웃하지 않는다"')
rep(n, 'if [ "$FL" = 1 ]; then docker tag "$FETOLD" autotrader-frontend; fi\nsame3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름 원복 확인 실패 — 보고"',
    'same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 바뀌었다 또는 조회 실패 — 보고"')
rep(n, 'stop "되돌림 결과가 b5cd1d0 가 아님 — 보고"', 'stop "되돌림 결과가 0e1d2a02 가 아님 — 보고"')
rep(n, 'echo "↩ U1-되돌림 끝 — HEAD b5cd1d0·backend latest = #27(52e12428)·frontend 이미지 이름 = U0 대상(837db515), 실행 컨테이너 두 개 대응 그대로, 재기동 없음.', 'echo "↩ U1-되돌림 끝 — HEAD 0e1d2a02·backend latest = 운영 2c5d8427·frontend 무변경(9d7f123a), 실행 컨테이너 두 개 대응 그대로, 재기동 없음.')

# ════════ U2 ════════
n = "U2"
rep(n, 'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 #27 이미지 대응이 아님 또는 조회 실패(아직 바꾼 것 없음) — 보고"',
    'isimg backend "$IMGOLD" $IMGOLD $IMGOLDM $IMGOLDC || stop "실행 중 backend 가 운영 2c5d8427 대응이 아님 또는 조회 실패(아직 바꾼 것 없음) — 보고"\n'
    'same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 U0 대응과 다름 또는 조회 불명(아직 바꾼 것 없음) — 바꾸지 않고 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패(아직 바꾼 것 없음) — 바꾸지 않고 보고"')
rep(n, 'date \'+%F %T %Z\' | tee "$M/t-deploy.txt"; date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-deploy.utc"\nv94 || stop "재기동 전 Flyway 최고 버전이 94 가 아님 — 보고(아직 바꾼 것 없음)"',
    'v96 || stop "재기동 전 Flyway 최고 버전이 96 이 아님 — 보고(아직 바꾼 것 없음)"\n'
    'v96h > "$M/v96-pre-deploy.txt" && VH=0 || VH=$?; cat "$M/v96-pre-deploy.txt"\n'
    '[ "$VH" = 0 ] || stop "재기동 전 V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH) — 보고(아직 바꾼 것 없음)"\n'
    'date \'+%F %T %Z\' | tee "$M/t-deploy.txt"; date -u \'+%Y-%m-%dT%H:%M:%SZ\' > "$M/t-deploy.utc"')
rep(n, 'echo "== 여기부터 재기동 — 새 backend 기동 때 Flyway 가 V96 하나를 적용한다. 이 뒤의 멈춤은 \'되돌림 필요\'(V96 은 되돌리지 않는다)"', 'echo "== 여기부터 재기동 — V96 은 이미 적용돼 있고 새 마이그레이션은 없다(DDL 단계 없음). 이 뒤의 멈춤은 \'되돌림 필요\'(U되돌림은 관문 4판 판정 뒤에만)"')
rep(n, '[ "$($PSQL -At -c "SELECT string_agg(version || \':\' || success || \':\' || checksum, \',\' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 95")" = "96:t:$V96SUM" ] || stop "되돌림 필요: V95 이후 이력이 V96 한 줄(성공·체크섬 $V96SUM)이 아님"',
    'v96h > "$M/v96-deploy.txt" && VH=0 || VH=$?; cat "$M/v96-deploy.txt"\n'
    '[ "$VH" = 0 ] || stop "되돌림 필요: V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH)"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "되돌림 필요: 실행 중 frontend 가 U0 대응이 아님 또는 조회 실패"')
rep(n, 'echo "✅ U2 통과 — 5~10분 뒤 U3(frontend 는 아직 구판 — 새 모의 세션을 만들지 않는다)"', 'echo "✅ U2 통과 — 5~10분 뒤 U3(frontend 교체 없음 — 새 모의 세션을 만들지 않는다)"')

# ════════ U3 ════════
n = "U3"
rep(n, '[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 300 ] || stop "기동 5분 전 — 5분이 지난 뒤 U3"',
    '[ $(( $(date +%s) - $(date -d "$S" +%s) )) -ge 300 ] || stop "기동 5분 전 — 5분이 지난 뒤 U3"\n'
    '[ "$T" != deploy ] || [ $(( $(date +%s) - $(date -d "$S" +%s) )) -le 600 ] || echo "⚪ 기동 5~10분 창을 지났다 — 늦은 실행 편차로 보고(기준 시각 t-deploy 그대로, 기동 뒤 전체 로그를 본다)"')
rep(n, 'if [ "$T" = rollback ]; then ! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "되돌림 뒤 마이그레이션이 돌았다 — 보고"; else grep -qE "Migrating schema .*96|Successfully applied 1 migration" "$M/app-$T.log" || echo "⚪ V96 적용 줄이 로그에 없음 — U2 의 Flyway 이력 확인으로 갈음(기록만)"; fi',
    '! grep -qE "Migrating schema|Successfully applied" "$M/app-$T.log" || stop "기동 뒤 마이그레이션이 돌았다(D81D4 는 DDL 없음, $T) — 보고"')
rep(n, 'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"\n',
    'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"\n'
    'v96h > "$M/v96-u3-$T.txt" && VH=0 || VH=$?; cat "$M/v96-u3-$T.txt"\n'
    '[ "$VH" = 0 ] || stop "V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH) — 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"\n')
rep(n, 'echo "✅ U3(rollback) 통과 — b5cd1d0 복귀 확인. U4 는 하지 않고 출력을 보고"', 'echo "✅ U3(rollback) 통과 — 0e1d2a02 복귀 확인. U4 는 하지 않고 출력을 보고"')

# ════════ U4 ════════
n = "U4"
rep(n, 'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"\n',
    'v96 || stop "되돌림 필요: Flyway 최고 버전이 96 이 아님"\n'
    'v96h > "$M/v96-after30.txt" && VH=0 || VH=$?; cat "$M/v96-after30.txt"\n'
    '[ "$VH" = 0 ] || stop "V95 이후 이력이 1|96:true:$V96SUM 하나가 아님 또는 조회 실패(종료 $VH) — 보고"\n')
line(n, '[ -e "$M/ok-UF2" ] || stop', None)
rep(n, 'diff <(grep -vE \'^(backend|frontend) \' "$M/svc-before.txt") <(grep -vE \'^(backend|frontend) \' "$M/svc-after30.txt") || stop "되돌림 필요: backend·frontend 외 서비스가 바뀜"\n'
    '[ "$(grep \'^frontend \' "$M/svc-fe.txt")" = "$(grep \'^frontend \' "$M/svc-after30.txt")" ] || stop "frontend 되돌림 필요: UF2 뒤 frontend 재시작·이미지 변경 있음"',
    'diff <(grep -v \'^backend \' "$M/svc-before.txt") <(grep -v \'^backend \' "$M/svc-after30.txt") || stop "되돌림 필요: backend 밖 서비스(frontend·db·redis·db-backup)의 이미지·기동 시각이 시작 기록(svc-before)과 다름"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"\n'
    'same3 autotrader-frontend "$M/image-fe-before-chain.txt" || stop "frontend 이미지 이름이 U0 대응과 다름 또는 조회 불명 — 바꾸지 않고 보고"')

# ════════ U되돌림 ════════
n = "U되돌림"
rep(n, 'stop "image-before 가 #27 이미지(IMGOLD)가 아님"', 'stop "image-before 가 운영 2c5d8427 이미지(IMGOLD)가 아님"')
rep(n, 'stop "HEAD 가 후보·b5cd1d0 어느 것도 아님($H)', 'stop "HEAD 가 후보·0e1d2a02 어느 것도 아님($H)')
rep(n, 'echo "== V171D4 — 바꾸기 전 실행 컨테이너 대응 판정(backend·frontend)과 보존 태그 확인"', 'echo "== 바꾸기 전 실행 컨테이너 대응 판정(backend 구판·새판, frontend 는 U0 대응 그대로여야 함)과 보존 태그 확인"')
between(n, 'if isimg frontend "$FEIMGOLD $(okimg "$M/image-fe-before-chain.txt")"', 'else cat "$M/rb-probe.txt" 2>/dev/null | tail -4; stop "실행 frontend 가 U0·U1 대응 어느 것도 아니거나 조회 실패(제3의 이미지일 수 있다) — 바꾸지 않고 보고"; fi',
        'isimg frontend "$FEIMGOLD" $(ok3 "$M/image-fe-before-chain.txt") 2>> "$M/rb-probe.txt" || { tail -4 "$M/rb-probe.txt"; stop "실행 frontend 가 U0 대응이 아니거나 조회 실패 — 이 판은 frontend 를 바꾸지 않는다. 바꾸지 않고 보고"; }')
rep(n, 'same3 $REPO:b5cd1d0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름', 'same3 $REPO:0e1d2a0 "$M/image-before-chain.txt" || stop "backend 보존 태그가 U0 대응과 다름')
line(n, 'same3 autotrader-frontend:b5cd1d0', None)
rep(n, 'echo "== V172D4 R2 — 두 이름의 출처 판정(원판·이번 빌드판·제3·조회 불명). 두 서비스 판정을 끝내기 전에는 HEAD·태그·컨테이너를 바꾸지 않는다"', 'echo "== V172D4 R2 — backend latest 출처 판정(원판·이번 빌드판·제3·조회 불명)과 frontend 이미지 이름 무변경 확인. 판정을 끝내기 전에는 HEAD·태그·컨테이너를 바꾸지 않는다"')
between(n, 'FNM=$(namecls autotrader-frontend', 'FN=0; [ "$FNM" = 원판 ] || FN=1',
        '[ "$BN" != 제3 ] || { tail -3 "$M/rb-probe.txt"; stop "backend latest 가 U0 원판·U1 이번 빌드판 어느 것도 아님(제3의 대상) — 바꾸지 않고 보고"; }\n'
        'FC=0; same3 autotrader-frontend "$M/image-fe-before-chain.txt" 2>> "$M/rb-probe.txt" || FC=$?\n'
        'case "$FC" in 0) ;; 1) tail -3 "$M/rb-probe.txt"; stop "frontend 이미지 이름이 U0 대응과 다름 — 이 판은 frontend 를 바꾸지 않았다(출처 미확인). 바꾸지 않고 보고" ;; *) tail -3 "$M/rb-probe.txt"; stop "frontend 이미지 이름 대응 조회 불명 — 같음으로 추정하지 않는다. 바꾸지 않고 보고" ;; esac')
rep(n, 'echo "실행 판정 backend=$BR frontend=$FR · frontend 이미지 이름 $([ "$FN" = 1 ] && echo U1새판 || echo U0) · 단계 $(cat "$M/fe-stage.txt" 2>/dev/null || echo 없음) · backend latest $BN"', 'echo "실행 판정 backend=$BR · frontend 무교체(실행·이미지 이름 U0 대응) · backend latest $BN"')
rep(n, 'echo "== 상태 기반 복구 관문 3판 — 판정만(자동 되돌림 없음). 실계좌 보유·RUNNING 이 있으면 EXCH_RECON 에 승인된 읽기 블록의 대조 출력 파일을 준다"', 'echo "== 상태 기반 복구 관문 4판 — 판정만(자동 되돌림 없음). 실계좌 보유·RUNNING 이 있으면 EXCH_RECON 에 승인된 읽기 블록의 대조 출력 파일을 준다"')
rep(n, 'cat "$M/gate-out.txt"\n',
    'cat "$M/gate-out.txt"\n'
    'GV=$(grep \'^VERDICT \' "$M/gate-out.txt" | tail -1 | cut -d\' \' -f2) || GV=\n'
    '[ "$GV" != STOP_INCOMPATIBLE ] || stop "관문 4판 STOP_INCOMPATIBLE(종료 $GR) — 구판 0e1d2a02 가 읽지 못하는 청산 사유(ENTRY_REJECTED 등)가 있다. 구판으로 되돌리지 않는다(최종 운영 판정의 복구 한계). 호환 바이너리를 새로 만들거나 DB 값을 바꾸지 않고 현재 바이너리를 유지해 gate-out.txt 를 보고 — 별도 복구 판단 전 자동 복구 없음"\n')
between(n, 'if [ "$FR" = 새판 ]; then', 'else echo "frontend 는 구판 그대로 — 바꾸지 않는다" | tee "$M/fe-restored.txt"; fi',
        'echo "frontend 는 이 판에서 교체하지 않았다 — 바꾸지 않는다(9d7f123a 유지)" | tee "$M/fe-rollback.txt"')
rep(n, 'echo "HEAD 가 이미 b5cd1d0 — 체크아웃하지 않는다"', 'echo "HEAD 가 이미 0e1d2a02 — 체크아웃하지 않는다"')
rep(n, 'svc | tee "$M/svc-rollback.txt"\n',
    'svc | tee "$M/svc-rollback.txt"\n'
    'diff <(grep -v \'^backend \' "$M/svc-before.txt") <(grep -v \'^backend \' "$M/svc-rollback.txt") || stop "backend 밖 서비스(frontend 포함)가 시작 기록과 다름 — 보고"\n'
    'isimg frontend "$FEIMGOLD" $FETOLD $FEMOLD $FECOLD || stop "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 보고"\n')
rep(n, 'echo "✅ b5cd1d0 (#27 이미지) 로 복귀 — 5분 뒤 U3 를 다시 실행', 'echo "✅ 0e1d2a02 (운영 2c5d8427 이미지) 로 복귀 — 5분 뒤 U3 를 다시 실행')

# ════════ 검사 — 옛 값 잔존·금지/허용 명령·구조 ════════
OLDV = {"b5cd1d0d47ea": "옛 OLD", "52e12428": "옛 IMGOLD", "ca440b84": "옛 IMGOLDM", "0344757e": "옛 IMGOLDC", "837db515": "옛 FETOLD",
        "fa5c9ba8": "옛 FEMOLD", "80ce7550": "옛 FECOLD", "d825a9f5": "옛 FEIMGOLD", OLDJARM[:16]: "옛 JARM", "6385e1d5": "관문 3판 GS",
        "c9dc2056": "3판 gate.sql", "fe19aab4": "3판 sh", "af4105c1": "손실 조회 v2", "maint-o13n21": "#27 기록 폴더", "881 895": "옛 JARN",
        "v94": "v94", "96:t:": "옛 t 표기", "preflight-loss": "v2 파일", "fe-stage": "frontend 단계", "image-fe-new": "frontend 새 빌드 기록",
        "UF2": "UF2", "svc-fe.txt": "UF2 기록", "V172D4\" ]": "옛 판 표지", "maint-v172\"": "옛 기록 폴더 표지"}
ALLOW = {("52e12428", "IMGPREV9="), ("837db515", "FEPREV9="), ("837db515", 'case "$OF" in'), ("52e12428", 'case "$OT" in'),
         ("svc-fe.txt", "FEIMGOLD="), ("image-fe-new", "FETOLD=")}
resid = []
for nm in NAMES:
    for i, x in enumerate(B[nm].split("\n"), 1):
        if x.lstrip().startswith("#"):
            continue
        for k, why in OLDV.items():
            if k in x and not any(k == a and x.startswith(b) for a, b in ALLOW):
                # 파이썬 heredoc 주석(jar 비교기 원문)·v1.xx 판 이력 주석 안의 언급은 실행 값이 아니다
                code = x.split("   #")[0] if "   #" in x else x
                if k in code and not any(k == a and code.startswith(b) for a, b in ALLOW):
                    resid.append("%s:%d %s: %.100s" % (nm, i, why, x))
if resid:
    ERR.append("옛 값 잔존 %d줄" % len(resid))
CHG = re.compile(r"(docker (tag|rm|rmi|image rm|compose[^\n]*(up|build|down|restart|stop|pull))|\$DC (up|build|down|restart|stop|pull|rm)|git (checkout|reset|clean|tag|push|commit))")
chgcount = {nm: len(CHG.findall(B[nm])) for nm in NAMES}
FORBID = re.compile(r"(\$DC build frontend|\$DC up[^\n]*frontend|docker tag \"\$FETOLD\"|autotrader-frontend:0e1d2a0|ALTER |UPDATE |DELETE FROM|INSERT INTO|DROP |TRUNCATE|flyway (migrate|repair|clean))")
forb = ["%s: %s" % (nm, m.group(0)) for nm in NAMES for m in FORBID.finditer(B[nm])]
if forb:
    ERR.append("금지 명령 %s" % forb)
EXPCHG = {"U변수": 0, "U0": 0, "U1": 4, "U1되돌림": 2, "U2": 1, "U3": 0, "U4": 0, "U되돌림": 3, "U뒤관측": 0}   # U1 = backend 보존 태그·checkout·build·임시 컨테이너 rm / U1되돌림 = checkout·latest 태그 / U2 = backend up / U되돌림 = checkout·latest 태그·backend up
if chgcount != EXPCHG:
    ERR.append("변경 명령 수 %s(기대 %s)" % (chgcount, EXPCHG))
if ERR:
    print("🔴 생성 거절 — 아무것도 쓰지 않았다")
    for e in ERR:
        print("  " + e)
    for r in resid:
        print("  잔존 " + r)
    sys.exit(2)

os.makedirs(OUT)
man = []
for nm in NAMES:
    b = B[nm].encode("utf-8")
    with open(os.path.join(OUT, nm + ".sh"), "wb") as f:
        f.write(b)
    man.append("%s  %s.sh" % (h(b), nm))
with open(os.path.join(OUT, "..", "blocks-d81.sha256"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(man) + "\n")
with open(os.path.join(OUT, "..", "gen-report.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("원문 ce6df2fa blocks-d4 sha256(9블록, UF2 제외):\n")
    f.write("".join("  %s  %s.sh\n" % (SRCSHA[x], x) for x in NAMES))
    f.write("U2확인 원문 d456788f 1c9eab35… SQL 대조 일치\n")
    f.write("DIFFN %d경로(제품 %d·시험 %d) · RARX %d줄(변경 35·추가 3) · 클래스/리소스 변경 이름 %d개\n" % (len(DIFF), len(prod), len(test), len(RAR), len(CLS)))
    f.write("관문 4판 gate.sql %s · rollback_gate.sh %s · 배포 전 읽기 v3 %s (GS %s)\n" % (GATESQL, GATESH, PFSUM, GS))
    f.write("변경 명령 수(블록별, 정규식 CHG) %s\n" % chgcount)
    f.write("금지 명령 0 · 옛 운영 값 실행 줄 잔존 0(허용: IMGPREV9·FEPREV9 정의와 옛 보존 태그 판정 줄)\n")
    f.write("제품 경로 14:\n" + "".join("  %s\n" % x for x in prod))
print("✅ 생성 %d블록 → %s" % (len(NAMES), OUT))
for x in man:
    print(x)
