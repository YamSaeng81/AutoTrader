# v1.250 §1-1 최소 환경 읽기(읽기 전용). sudo·설치·재기동·DB·계좌·외부 API·env/인증값 출력 없음.
echo "== 시각"; date -u '+utc %F %T'; e=$(date +%s); echo "epoch $e"; echo "kst(date -u +32400) $(date -u -d "@$((e+32400))" '+%F %T')"
python3 -I -c 'import datetime as d;n=d.datetime.now(d.timezone.utc);print("kst(python 고정 +09:00)",n.astimezone(d.timezone(d.timedelta(hours=9))).strftime("%F %T"))'
echo "system tz: $(date +%Z%z) $(cat /etc/timezone 2>/dev/null)"
echo "== 시스템"; . /etc/os-release; echo "os $PRETTY_NAME · kernel $(uname -r) · arch $(uname -m)"; echo "nproc $(nproc)"; echo "loadavg $(cat /proc/loadavg)"; echo "uptime $(uptime -p)"
free -m | sed -n '1,2p'; df -h ~ | tail -1 | awk '{print "home disk size",$2,"used",$3,"avail",$4,"use",$5,"mount",$6}'
echo "== 도구"; for t in bash python3 tar sha256sum nohup setsid flock timeout at systemd-run loginctl; do printf '%s %s\n' "$t" "$(command -v $t || echo 없음)"; done
echo "bash $BASH_VERSION · $(python3 -V 2>&1) · $(flock --version 2>&1 | head -1) · $(timeout --version | head -1)"
echo "linger $(loginctl show-user "$USER" -p Linger 2>&1) · logind KillUserProcesses: $(grep -h -E '^\s*KillUserProcesses' /etc/systemd/logind.conf /etc/systemd/logind.conf.d/*.conf 2>/dev/null || echo '설정 줄 없음(기본값)')"
echo "== 경로"; for p in ~/work-batches ~/work-batches/d81b-20261127-exp1 ~/maint-d81b-20261127-exp1 ~/daily-check ~/sid203-ledger ~/ops-scripts; do [ -e "$p" ] && echo "있음 $p" || echo "없음 $p"; done
echo "crontab $(crontab -l | sha256sum | cut -d' ' -f1)"; echo "INSTALLED_SHA $(cat ~/ops-scripts/INSTALLED_SHA)"
echo "== 운영 상태"; cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD) · 작업트리 변경 $(git status --porcelain --untracked-files=no | wc -l)"
for s in backend frontend; do c=$(docker compose -f docker-compose.prod.yml ps -q $s); docker inspect --format "$s Image={{.Image}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" $c; done
echo "== 끝 $(date -u '+%F %T')Z"
