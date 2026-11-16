set -u; echo "[서버시각] $(date '+%F %T %Z')"
cd ~; grep -rIl --include='*' -e d825a9f5 -e 837db515 -e 80ce7550 -e fa5c9ba8 maint-* daily-check ops-scripts 2>/dev/null | head -30
echo "== 처음 등장"; for p in d825a9f5 837db515 fa5c9ba8 80ce7550; do echo "-- $p"; grep -rIn -e $p maint-* 2>/dev/null | sort | head -4 | cut -c1-260; done
echo "== apt history 날짜(docker 관련)"; grep -h -B3 'docker-ce' /var/log/apt/history.log* 2>/dev/null | grep 'Start-Date' ; zcat /var/log/apt/history.log.*.gz 2>/dev/null | grep -B3 'docker-ce' | grep 'Start-Date'
