# N21 단독 적용안 v2 변수 — 초안(총괄 v1.139 §107-1 경계 보완). 서버 실행은 미승인이다.
# Python-only 영향 검토용으로 보존한다. 실제 적용용은 O1·O3+N21 통합 초안 D3 다. 이 판을 서버에서 실행하지 않는다.
# 순서: vars.sh → A0 → A1 → (A1 뒤 첫 :19 회차가 끝난 다음) A2. A1-되돌림은 운영자 판단 때만.
NB=21v2
cd ~/crypto_trader/AutoTrader
OLD=1899b49f633f8aefdc66b15c42debdaba8b3852c          # 현재 운영 HEAD
MID=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e          # 실패 시험만 추가한 커밋
NEW=6379ee0b2a7527b8136d7fba27e83881980d8555          # N21 후보(총괄 v1.138 로컬 수용)
F=scripts/prospective/start_66.py
T=scripts/prospective/test_freshness_recheck_fail.py   # NEW 에서 새로 추적되는 시험 파일 — 서버에서 실행하지 않는다
BOLD=460031d30ce488f4604be6eb6aea78c10db50caf         # OLD 의 start_66.py 블롭
BNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d         # NEW 의 start_66.py 블롭
HOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d   # OLD start_66.py 파일 sha256
HNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b   # NEW start_66.py 파일 sha256
IMG=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1   # backend 이미지 — 바뀌면 안 된다(재빌드·재기동 없음)
CRONH=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b      # crontab -l 원문 sha256 — 바꾸지 않는다
DC="docker compose -f docker-compose.prod.yml"
M=~/maint-n21v2; mkdir -p "$M"
# ── N21 공통 함수 시작(통합 초안 D3 U변수에 같은 원문으로 들어간다) ──
JLOG=scripts/prospective/freshness_log.jsonl      # 관측 행(회차마다 한 행)
RUNLOG=scripts/prospective/freshness_runs.log     # cron 회차의 표준 출력·오류(>> 덧붙임)
SYSLOG=/var/log/syslog                            # cron 시작 줄(CRON[pid]: (사용자) CMD (…))
# freshness 회차 프로세스 패턴 — 명령줄 맨 앞이 python 실행 파일인 프로세스만 잡는다.
# cron 의 /bin/sh -c 껍데기, 이 블록을 담은 bash(대화형·bash -c·bash 파일) 명령줄은 맨 앞이 python 이 아니라서 잡히지 않는다.
FPAT='^[^ ]*python[0-9.]* +([^ ]+ +)*[^ ]*start_66[.]py +freshness'
# 회차 조회. 인자: 기록 이름. 종료 0 = 회차 없음(pgrep 종료 1·출력 없음), 1 = 회차 있음(pgrep 종료 0), 2 = 조회 실패(그 밖 전부)
fresh_idle() { local rc=0
  pgrep -af "$FPAT" > "$M/pgrep-$1.txt" 2> "$M/pgrep-$1.err" || rc=$?
  case "$rc" in
    1) if [ -s "$M/pgrep-$1.txt" ] || [ -s "$M/pgrep-$1.err" ]; then cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 이상: pgrep 종료 1 인데 출력 있음"; return 2; fi
       echo "freshness 회차 없음(pgrep 종료 1)"; return 0 ;;
    0) cat "$M/pgrep-$1.txt"; echo "freshness 회차가 돌고 있다(pgrep 종료 0)"; return 1 ;;
    *) cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 실패(pgrep 종료 $rc)"; return 2 ;;
  esac; }
# 적용 직전 기준 위치. 인자: 표지 이름. 시스템 기록 inode·줄 수, 실행 기록 줄 수, 관측 행 수. 하나라도 못 읽으면 종료 1
n21mark() {
  [ -r "$SYSLOG" ] || { echo "시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] || { echo "실행 기록 $RUNLOG 를 읽을 수 없음"; return 1; }
  [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "관측 행 $JLOG 를 읽을 수 없음"; return 1; }
  printf '%s %s %s %s\n' "$(stat -c %i "$SYSLOG")" "$(wc -l < "$SYSLOG")" "$(wc -l < "$RUNLOG")" "$(wc -l < "$JLOG")" > "$M/mark-$1.txt"
  echo "기준 위치(시스템 기록 inode·줄, 실행 기록 줄, 관측 행): $(cat "$M/mark-$1.txt")"; }
# 적용 뒤 확인. 인자: 표지 이름. 구간 = 표지 위치 뒤에 덧붙은 줄. 종료 0 = 세 근거 모두 확인, 1 = 미확인 또는 이상(이유 출력)
# PID·완료 식별자가 없어 시작 줄과 관측 행의 1:1 짝은 확정하지 않는다. at 은 재확인 회차면 재확인 뒤, 아니면 1차 조회 전 시각이다
n21after() { local ino s0 r0 j0 ino1 s1 r1 j1 bad=0
  read -r ino s0 r0 j0 < "$M/mark-$1.txt" || { echo "미확인: 기준 위치 기록 없음"; return 1; }
  [ -r "$SYSLOG" ] || { echo "미확인: 시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] && [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "미확인: 실행 기록·관측 행 파일을 읽을 수 없음"; return 1; }
  ino1=$(stat -c %i "$SYSLOG"); s1=$(wc -l < "$SYSLOG"); r1=$(wc -l < "$RUNLOG"); j1=$(wc -l < "$JLOG")
  echo "기준 → 지금: 시스템 기록 inode $ino→$ino1 줄 $s0→$s1 · 실행 기록 줄 $r0→$r1 · 관측 행 $j0→$j1"
  [ "$ino1" = "$ino" ] && [ "$s1" -ge "$s0" ] || { echo "미확인: 시스템 기록이 교체·축소됨(회전 등) — 회전된 파일을 찾지 않고 보고"; return 1; }
  [ "$r1" -ge "$r0" ] && [ "$j1" -ge "$j0" ] || { echo "미확인: 실행 기록 또는 관측 행이 줄었다 — 보고"; return 1; }
  sed -n "$((s0 + 1)),${s1}p" "$SYSLOG" | grep -E 'CRON\[[0-9]+\]: (\([^)]*\) )?CMD \(.*start_66[.]py freshness' > "$M/cron-after-$1.txt" || true
  sed -n "$((r0 + 1)),${r1}p" "$RUNLOG" > "$M/runlog-after-$1.txt"
  sed -n "$((j0 + 1)),${j1}p" "$JLOG" > "$M/log-after-$1.jsonl"
  echo "== 적용 뒤 cron 시작 줄 $(wc -l < "$M/cron-after-$1.txt")개"; tail -3 "$M/cron-after-$1.txt"
  echo "== 적용 뒤 실행 기록 $(wc -l < "$M/runlog-after-$1.txt")줄 · 그중 Traceback $(grep -c 'Traceback' "$M/runlog-after-$1.txt" || true)줄"
  echo "== 적용 뒤 관측 행 $(wc -l < "$M/log-after-$1.jsonl")개"
  [ -s "$M/cron-after-$1.txt" ] || { echo "미확인: 적용 뒤 cron 시작 줄이 없다 — 새 판이 cron 으로 시작됐다는 근거 없음"; bad=1; }
  [ -s "$M/log-after-$1.jsonl" ] || { echo "미확인: 적용 뒤 새 관측 행이 없다 — 회차가 아직 안 끝났거나 실패(손으로 돌리지 않는다)"; bad=1; }
  if grep -q 'Traceback' "$M/runlog-after-$1.txt"; then grep -n -A3 'Traceback' "$M/runlog-after-$1.txt" | tail -20; echo "이상: 적용 뒤 실행 기록에 새 Traceback"; bad=1; fi
  local py=0; [ ! -s "$M/log-after-$1.jsonl" ] || python3 -c '
import json, sys
sys.stdout.reconfigure(encoding="utf-8")
keys = ["at", "green", "rechecked", "red", "redCoins", "rows", "unknown", "unknownCoins"]
rk = ["count", "dbTo", "exErr", "exTo", "rechecked"]
bad = 0
for line in open(sys.argv[1], encoding="utf-8"):
    try:
        r = json.loads(line)
        ok = sorted(r) == keys and all(sorted(v) == rk for v in r["rows"].values())
        stale = [p for p, v in r["rows"].items() if v["exErr"] and v["exTo"]]
    except Exception as e:
        print("행 해석 실패:", type(e).__name__); bad += 1; continue
    print(r["at"], "🟢%d ⚪%d 🔴%d 재확인 %d" % (r["green"], r["unknown"], r["red"], len(r["rechecked"])),
          "칸 같음" if ok else "칸 다름", "N21 상태 %d" % len(stale))
    bad += (not ok) or bool(stale)
sys.exit(1 if bad else 0)
' "$M/log-after-$1.jsonl" > "$M/rows-after-$1.txt" 2>&1 || py=$?
  [ ! -s "$M/rows-after-$1.txt" ] || cat "$M/rows-after-$1.txt"
  [ "$py" = 0 ] || { echo "이상: JSON 칸이 다르거나 N21 상태 행(실패 종류가 있는데 exTo 가 남음)·해석 실패(종료 $py)"; bad=1; }
  return "$bad"; }
# ── N21 공통 함수 끝 ──
