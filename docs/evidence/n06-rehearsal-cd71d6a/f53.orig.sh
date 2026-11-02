SHA=<리뷰가 확인한 커밋 SHA>                                       # F3 블록을 이 커밋의 문서에서 그대로 꺼낸다(손으로 옮기지 않는다)
git fetch origin && git show "$SHA":docs/LIMITED_LIVE_TEST_PLAN.md | awk '/^\*\*F3\. /{f=1; next} f && /^```bash$/{g=1; next} g && /^```$/{exit} g' > "$E/f3-block.sh"
head -1 "$E/f3-block.sh"; tail -1 "$E/f3-block.sh"; sha256sum "$E/f3-block.sh"   # 첫 줄 S=$($PSQL …, 끝 줄 PY
( M=$E; . "$E/f3-block.sh" )                                        # 결과는 $E/f3.txt·$E/account-f3.json — 첫날 $M/f3.txt 는 그대로
$PSQL -At -F ' | ' -c "SELECT *, CASE WHEN status IN ('STOPPED','EMERGENCY_STOPPED') AND active + open_pos + cleanup + unresolved = 0
    THEN 'DB 정리 조건 충족 — 정산·거래소 대조 별도 확인 필요' ELSE '미완료' END AS verdict FROM (
  SELECT status, available_krw, stopped_at AT TIME ZONE 'Asia/Seoul' AS stopped_kst,
  (SELECT count(*) FROM \"order\" WHERE session_kind = 'LIVE' AND session_id = $SID AND state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS active,
  (SELECT count(*) FROM position WHERE session_kind = 'LIVE' AND session_id = $SID AND status IN ('OPEN','CLOSING')) AS open_pos,
  (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL) AS cleanup,
  (SELECT count(*) FROM order_settlement_discrepancy WHERE resolved_at IS NULL) AS unresolved
  FROM live_trading_session WHERE id = $SID) x" | tee "$E/stop-check.txt"
