(
set -euo pipefail
FROM=RUNNING; TO=STOPPED                                    # 56일 종료 뒤에는 리뷰 판단을 받아 이 줄만 FROM=RUNNING; TO=<최종 상태>
D=~/daily-check; B="$D/crontab.before-$TO"; N="$D/crontab.new-$TO"
stop() { echo "🔴 멈춤: $* — cron 은 바꾸지 않았다"; exit 1; }
[ ! -e "$B" ] || stop "$B 가 이미 있다(전에 바꾼 적이 있다) — crontab -l 출력과 함께 보고"
crontab -l > "$B" || stop "crontab 조회 실패"
[ -s "$B" ] || stop "조회한 crontab 이 비었다"
[ "$(grep -c "SID_OK_STATUS=$FROM " "$B" || true)" = 1 ] || stop "SID_OK_STATUS=$FROM 줄이 정확히 1개가 아니다"
! grep -q "SID_OK_STATUS=$TO " "$B" || stop "SID_OK_STATUS=$TO 줄이 이미 있다"
sed "s/SID_OK_STATUS=$FROM /SID_OK_STATUS=$TO /" "$B" > "$N"
[ "$(wc -l < "$N")" = "$(wc -l < "$B")" ] || stop "수정본 줄 수가 다르다"
[ "$(grep -c "SID_OK_STATUS=$TO " "$N" || true)" = 1 ] || stop "수정본에서 바뀐 줄이 1개가 아니다"
diff <(grep -v "SID_OK_STATUS=$TO " "$N" || true) <(grep -v "SID_OK_STATUS=$FROM " "$B" || true) > /dev/null || stop "수정본에서 다른 줄이 달라졌다"
crontab "$N" || stop "crontab 적용 실패 — crontab -l 로 지금 상태를 확인해 보고"
crontab -l | cmp -s - "$N" || { echo "🔴 적용 뒤 대조 실패 — crontab \"$B\" 로 되돌리고 crontab -l 출력을 보고"; exit 1; }
echo "✅ cron 전환 $FROM → $TO · 보존본 $B · 적용본 $N"
SID_OK_STATUS="$TO" REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh
)
