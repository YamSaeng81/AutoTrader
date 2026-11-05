echo "== N15·N16 줄(기동 뒤 30분 로그 · 자연 발생만)"
entryobs "$M/app-after30.log" | tee "$M/entryobs-after30.txt" && EQ=0 || EQ=$?
[ "$EQ" = 0 ] || stop "🔴 N15 만료 사유 기록 실패 줄(종료 $EQ) — 바로 되돌리지 말고 위 출력과 원문 줄을 보고"
grep -q "^N15: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N15 만료 미관측 — 운영 반영은 HEAD·이미지·jar 로만 확인, 동작 관측은 미관측으로 보고(유도하지 않는다)" || true
grep -q "^N16: 미관측" "$M/entryobs-after30.txt" && echo "⚪ N16 가격 아닌 시세 미관측 — 동작 관측은 미관측으로 보고(유도하지 않는다). 미래 관측 시각 보정은 로그 줄이 없어 이 함수로 보지 않는다" || true
