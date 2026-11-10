# A2. 뒤 확인 — 읽기 전용. A1 뒤 첫 :19 회차가 끝난 다음(회차가 길면 끝날 때까지) 쓴다. 회차를 손으로 돌리지 않는다
(
set -euo pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
[ "${NB:-}" = 21 ] || stop "N21 변수(vars.sh)를 먼저 넣는다"
[ -e "$M/ok-A1" ] || stop "A1 미통과"
if pgrep -af "start_66.py freshness" > "$M/pgrep-a2.txt"; then cat "$M/pgrep-a2.txt"; stop "회차가 아직 돈다 — 끝난 뒤 다시"; fi
date '+%F %T %Z' | tee "$M/t-a2.txt"
{ git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git-a2.txt"
[ "$(head -1 "$M/git-a2.txt")" = "$NEW" ] && [ "$(sed -n 2p "$M/git-a2.txt")" = "(끝)" ] || stop "HEAD 가 6379ee0b 가 아니거나 변경 있음"
[ "$(sha256sum < "$F" | cut -d' ' -f1)" = "$HNEW" ] || stop "start_66.py 가 후보 원문과 다름"
# 체크아웃 뒤 첫 시작 줄 = 새 판 첫 회차 '시작' 시각. PID·종료 기록이 없으므로 완료 행과의 짝은 확정하지 않는다
{ grep -E "CRON.*start_66.py freshness" /var/log/syslog || true; } | tail -3 | tee "$M/cron-a2.txt"
N0=$(cat "$M/log-lines-before.txt"); N1=$(wc -l < scripts/prospective/freshness_log.jsonl)
echo "관측 행 $N0 → $N1"; [ "$N1" -gt "$N0" ] || stop "A1 뒤 새 관측 행 없음 — 회차가 아직 안 끝났거나 실패(손으로 돌리지 않고 보고)"
tail -n +"$((N0 + 1))" scripts/prospective/freshness_log.jsonl > "$M/log-after.jsonl"
# 칸 이름이 그대로인지, N21 상태(실패 종류가 있는데 exTo 가 남은 항목)가 0 인지만 본다. at 은 재확인 회차면 재확인 뒤, 아니면 1차 조회 전 시각이다
python3 -c '
import json, sys
sys.stdout.reconfigure(encoding="utf-8")
keys = ["at", "green", "rechecked", "red", "redCoins", "rows", "unknown", "unknownCoins"]
rk = ["count", "dbTo", "exErr", "exTo", "rechecked"]
bad = 0
for line in open(sys.argv[1], encoding="utf-8"):
    r = json.loads(line)
    ok = sorted(r) == keys and all(sorted(v) == rk for v in r["rows"].values())
    stale = [p for p, v in r["rows"].items() if v["exErr"] and v["exTo"]]
    print(r["at"], "🟢%d ⚪%d 🔴%d 재확인 %d" % (r["green"], r["unknown"], r["red"], len(r["rechecked"])),
          "칸 같음" if ok else "칸 다름", "N21 상태 %d" % len(stale))
    bad += (not ok) or bool(stale)
sys.exit(1 if bad else 0)
' "$M/log-after.jsonl" > "$M/rows-a2.txt" && RA=0 || RA=$?
cat "$M/rows-a2.txt"
[ "$RA" = 0 ] || stop "JSON 칸이 다르거나 N21 상태 행 있음 — rows-a2.txt 를 보고"
{ grep -n "Traceback" scripts/prospective/freshness_runs.log || true; } | tail -3 | tee "$M/traceback-a2.txt"
[ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$IMG" ] || stop "backend 이미지가 바뀌었다 — 보고"
crontab -l | cmp -s - "$M/crontab-before.txt" || stop "cron 이 A0 때와 다름 — 보고"
echo "✅ A2 — 새 판 회차 기록 확인. 재조회 실패 경로(net·404)는 자연 발생 전까지 미관측이다"
)
