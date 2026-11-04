#!/usr/bin/env bash
# N14 설치 블록 대역 실행 — 서버 명령 아님. 가짜 HOME·로컬 복제·crontab/flock 대역.
# 대역 차이: crontab 은 운영 원문이 없어 가짜 내용을 쓰고, 블록의 CRON 기대 해시만 그 가짜 내용의 해시로 바꾼다(그 밖의 줄은 바이트 그대로).
set -u
SIM="$1"; BLOCK="$2"; MAIN_REPO="$3"; CASE="$4"
rm -rf "$SIM"; mkdir -p "$SIM/home/ops-scripts" "$SIM/home/daily-check" "$SIM/bin"
export HOME="$SIM/home"
git -C "$MAIN_REPO" show d491fd1:scripts/ops/daily_check.sh > "$HOME/ops-scripts/daily_check.sh"
git -C "$MAIN_REPO" show d491fd1:scripts/ops/daily_checks.sql > "$HOME/ops-scripts/daily_checks.sql"
echo d491fd1609b700d195ecc4d0ccea6be2987bf2fe > "$HOME/ops-scripts/INSTALLED_SHA"
printf '0 10,22 * * * SID_OK_STATUS=CREATED REPO_DIR=$HOME/crypto_trader/AutoTrader SQL_FILE=$HOME/ops-scripts/daily_checks.sql $HOME/ops-scripts/daily_check.sh >> $HOME/daily-check/cron.log 2>&1\n' > "$SIM/crontab.txt"
git clone -q --no-checkout --shared "$MAIN_REPO" "$HOME/crypto_trader/AutoTrader"
cat > "$SIM/bin/crontab" <<EOF
#!/usr/bin/env bash
[ "\$1" = -l ] && cat "$SIM/crontab.txt"
EOF
cat > "$SIM/bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$SIM/bin/"*; export PATH="$SIM/bin:$PATH"
CR=$(sha256sum < "$SIM/crontab.txt" | cut -d' ' -f1)
sed "s/CRON=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b/CRON=$CR/" "$BLOCK" > "$SIM/block.sh"
case "$CASE" in
  ok) ;;
  wrong-installed) echo 8435935e975ceb6c2e16a09ef5b82530574a7da2 > "$HOME/ops-scripts/INSTALLED_SHA" ;;
  pre-exists) mkdir -p "$HOME/ops-scripts/pre-c669ccd" ;;
  wrong-hash) sed -i 's/NEWSH=ff0a5d7d/NEWSH=00000000/' "$SIM/block.sh" ;;
esac
before=$(cd "$HOME/ops-scripts" && sha256sum daily_check.sh daily_checks.sql INSTALLED_SHA)
bash "$SIM/block.sh"; rc=$?
echo "rc=$rc"
echo "-- 설치본"; (cd "$HOME/ops-scripts" && sha256sum daily_check.sh daily_checks.sql && cat INSTALLED_SHA)
[ -d "$HOME/ops-scripts/pre-c669ccd" ] && { echo "-- 보존본"; ls "$HOME/ops-scripts/pre-c669ccd"; }
after=$(cd "$HOME/ops-scripts" && sha256sum daily_check.sh daily_checks.sql INSTALLED_SHA)
[ "$before" = "$after" ] && echo "설치본 그대로" || echo "설치본 바뀜"
