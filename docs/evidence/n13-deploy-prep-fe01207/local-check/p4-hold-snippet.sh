echo "== HOLD 질의 장기 대여·연결 획득(기동 뒤 30분 로그 — 시작 catchup 과 5분 뒤 첫 정기 회차를 포함 · 배포 전 기준선: $(head -1 "$M/hold-before.txt"))"
holdobs "$M/app-after30.log" | tee "$M/holdobs-after30.txt" && HQ=0 || HQ=$?
[ "$HQ" != 1 ] || stop "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고(되돌림 여부는 총괄 판단, P 되돌림 블록 준비됨)"
grep -q "시작 Catchup 완료" "$M/app-after30.log" || echo "⚪ 시작 catchup 완료 줄이 아직 없다 — HOLD 질의 개선 관측 미완(되돌리지 않는다)"
if [ "$HQ" = 3 ]; then cp "$M/holdobs-after30.txt" "$M/hold-check-after30.txt"; echo "⚪ 장기 대여 경고가 있다 — 개선 미확인. 되돌리지 않는다. 위 줄을 보고"; fi
