(
set -euo pipefail
stop() { echo "🔴 멈춤: $* — 설치본은 바꾸지 않았다(아래 '교체 뒤' 표시가 없으면)"; exit 1; }
NEWSHA=c669ccdc6e248ce8e55e9a7ac7405197d266fa18; NEWSH=ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc
OLDSHA=d491fd1609b700d195ecc4d0ccea6be2987bf2fe; OLDSH=2135e95314edeea68f6b9dfc31a1c0b0034f84b9d3cbfd52be7ee82afab9cc60
SQLSH=653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8; CRON=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b
OPS=~/ops-scripts; P="$OPS/pre-${NEWSHA:0:7}"; T=$(mktemp -d)
h256() { sha256sum < "$1" | cut -d' ' -f1 ; }
hm=$(TZ=Asia/Seoul date +%H%M); { [ "$hm" -ge 0955 ] && [ "$hm" -lt 1010 ]; } || { [ "$hm" -ge 2155 ] && [ "$hm" -lt 2210 ]; } && stop "정기 점검 시각 근처(KST $hm) — 10분 뒤 다시"
exec 9>~/daily-check/.lock.flock; flock -n 9 || stop "정기 점검이 실행 중 — 끝난 뒤 다시"
[ "$(cat "$OPS/INSTALLED_SHA")" = "$OLDSHA" ] && [ "$(h256 "$OPS/daily_check.sh")" = "$OLDSH" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$SQLSH" ] || stop "설치본이 d491fd1 판이 아님"
crontab -l > "$T/crontab.before"; [ "$(h256 "$T/crontab.before")" = "$CRON" ] || stop "crontab 이 f2dce280… 과 다름"
[ ! -e "$P" ] || stop "$P 가 이미 있다 — 덮어쓰지 않는다"
cd ~/crypto_trader/AutoTrader; git fetch origin
git cat-file -e "$NEWSHA^{commit}" || stop "후보 커밋이 없음"
git show "$NEWSHA:scripts/ops/daily_check.sh" > "$T/daily_check.sh"; git show "$NEWSHA:scripts/ops/daily_checks.sql" > "$T/daily_checks.sql"
[ "$(h256 "$T/daily_check.sh")" = "$NEWSH" ] && [ "$(h256 "$T/daily_checks.sql")" = "$SQLSH" ] || stop "후보 파일 해시가 기대와 다름"
bash -n "$T/daily_check.sh" || stop "bash -n 실패"
mkdir "$P"; cp -p "$OPS/daily_check.sh" "$OPS/daily_checks.sql" "$OPS/INSTALLED_SHA" "$P/"; cp "$T/crontab.before" "$P/crontab.before"
( cd "$P" && sha256sum daily_check.sh daily_checks.sql INSTALLED_SHA crontab.before ) | tee "$P/SHA256SUMS"
echo "== 교체 뒤(셸·INSTALLED_SHA 만)"
cp "$T/daily_check.sh" "$OPS/daily_check.sh.new"; chmod +x "$OPS/daily_check.sh.new"; mv "$OPS/daily_check.sh.new" "$OPS/daily_check.sh"
echo "$NEWSHA" > "$OPS/INSTALLED_SHA.new"; mv "$OPS/INSTALLED_SHA.new" "$OPS/INSTALLED_SHA"
[ "$(h256 "$OPS/daily_check.sh")" = "$NEWSH" ] && [ "$(cat "$OPS/INSTALLED_SHA")" = "$NEWSHA" ] && [ "$(h256 "$OPS/daily_checks.sql")" = "$SQLSH" ] || stop "교체 뒤 대조 실패 — 아래 되돌림"
crontab -l | cmp -s - "$T/crontab.before" || stop "crontab 이 바뀜 — 보고"
git -C ~/crypto_trader/AutoTrader rev-parse HEAD
echo "✅ 설치 — 잠금을 풀고 DRY_RUN 1회"
)
INSTALL_RC=$?
if [ "$INSTALL_RC" -ne 0 ]; then
  echo "🔴 설치 종료 $INSTALL_RC — DRY_RUN 은 실행하지 않았다. 위 출력 위치에 따라 보고/되돌림"
  false
else
  SID_OK_STATUS=CREATED REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh
  DRY_RC=$?; echo "DRY_RUN rc=$DRY_RC — 본문/설치 해시로 판정"
fi
