SHA=<리뷰가 확인한 커밋 SHA>
git fetch origin && git show "$SHA":docs/OPS_DAILY_CHECK.md | awk '/^### 3-1\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g' > "$M/cron-switch.sh"
head -2 "$M/cron-switch.sh"; tail -2 "$M/cron-switch.sh"; sha256sum "$M/cron-switch.sh"   # 첫 줄 '(' · 둘째 줄 set -euo pipefail · 끝 두 줄 SID_OK_STATUS="$TO" … DRY_RUN=1 … / ')'
grep -c '^FROM=CREATED; TO=RUNNING' "$M/cron-switch.sh"                                   # 1
bash "$M/cron-switch.sh" 2>&1 | tee "$M/cron-switch.txt"
crontab -l | grep -c 'SID_OK_STATUS=RUNNING '; crontab -l | grep -c 'SID_OK_STATUS=CREATED '; crontab -l | grep -c 'sid_ledger.sql'   # 1 · 0 · 1
