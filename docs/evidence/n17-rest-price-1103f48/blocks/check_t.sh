# T 블록 대역 확인(로컬, 서버·DB·docker 없음). 사용: bash check_t.sh  (blocks/ 안에서)
set -u
B=t-blocks.md; P=0; F=0
ok() { echo "✅ $*"; P=$((P+1)); }; ng() { echo "❌ $*"; F=$((F+1)); }
blk() { python - "$B" "$1" <<'PY'
import re,sys,pathlib
s=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
b=re.findall(r'(?:^|\n)(\*\*[^\n]*?\*\*)[^\n]*\n```bash\n(.*?)\n```',s,re.S)
sys.stdout.buffer.write((b[int(sys.argv[2])][1]+'\n').encode('utf-8'))
PY
}
TMPH=$(mktemp -d); export HOME=$TMPH
blk 0 > "$TMPH/vars.sh"; . "$TMPH/vars.sh"
# 1 판 표지·폴더
[ "$M" = "$HOME/maint-n17" ] && [ "$TB" = 87 ] && ok "M=~/maint-n17·TB=87" || ng "M/TB"
[ "$OLD" = 1103f48505a1457029f6555547a87aa986535738 ] && [ "$NEW" = 7a944723c2959898226c2ad80f6b079ab5678296 ] && ok "OLD 1103f48·NEW 7a94472" || ng "OLD/NEW"
[ "$IMGOLD" = sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62 ] && ok "IMGOLD=#23 9818254c" || ng IMGOLD
[ "$IMGPREV4" = sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 ] && ok "IMGPREV4=#22 9cb5d84f" || ng IMGPREV4
# 2 DIFFN = 실제 로컬 git diff
[ "$(git -C '/d/Claude Code/projects/cat-n17' diff --name-only $OLD $NEW)" = "$DIFFN" ] && ok "DIFFN = git diff 1103f48 7a94472 (4)" || ng DIFFN
# 3 T1 jar 검사식에 실제 로컬 jar 출력(LF)
tr -d '\r' < ../jars/diff-1103f48-7a94472.txt > "$TMPH/jar-diff.txt"
[ "$(grep -v '^(끝)' "$TMPH/jar-diff.txt")" = "$RARX" ] && ok "RARX = 실제 jar 출력 6줄" || ng RARX
grep -qx '(끝) 881 881' "$TMPH/jar-diff.txt" && ok "끝 줄 881 881" || ng END
grep -q "grep -qx '(끝) 881 881'" $B && grep -q '"\$RARX" \] || stop "jar 차이가 N17 6줄이 아님' $B && ok "T1 검사식 문구" || ng "T1 문구"
# 3b 음성 대조: 옛 #23 출력이면 걸린다
tr -d '\r' < ../../k23-final-1103f48/jars/diff-fe01207-1103f48.txt > "$TMPH/old.txt"
[ "$(grep -v '^(끝)' "$TMPH/old.txt")" != "$RARX" ] && ! grep -qx '(끝) 881 881' "$TMPH/old.txt" && ok "음성: #23 jar 출력은 T1 에서 멈춘다" || ng "음성 jar"
# 4 n17obs — 실제 시험 로그 줄(콘솔 ? 표기 포함)·컨테이너 접두·UTF-8 원문·0건·인자 오류
X=../tests/r2-fix-related/xml/TEST-com.cryptoautotrader.api.service.N17RestInvalidPriceTest.xml
grep -E "^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} \[" "$X" | sed 's/^/backend-1  | /' > "$TMPH/app.log"
L=$(grep -c "\[§9\] REST 강제 갱신 시세가 가격이 아님" "$TMPH/app.log"); D=$(grep -c "\[Dynamic\]\[§9\] REST 강제 갱신 시세가 가격이 아님" "$TMPH/app.log"); L=$((L-D))
n17obs "$TMPH/app.log" > "$TMPH/o1.txt"; rc=$?
[ $rc = 0 ] && grep -q "LIVE $L·DYNAMIC $D" "$TMPH/o1.txt" && [ $((L+D)) -gt 0 ] && ok "n17obs 실제 시험 로그 LIVE $L·DYNAMIC $D" || { ng "n17obs 실제 로그"; cat "$TMPH/o1.txt"; }
printf '%s\n' 'backend-1  | 12:00:00.000 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 강제 갱신 시세가 가격이 아님 — 발행하지 않음 (market=KRW-X, trade_price=0)' \
 'backend-1  | 12:00:01.000 [scheduling-1] WARN  c.c.a.service.DynamicTradingService - [Dynamic][§9] REST 강제 갱신 시세가 가격이 아님 — 발행하지 않음 (market=KRW-Y, trade_price=-1)' \
 'backend-1  | 12:00:02.000 [scheduling-1] WARN  c.c.a.service.DynamicTradingService - [Dynamic][§9] SL 미점검: sessionId=1 coin=KRW-Y REST강제갱신=INVALID_PRICE' > "$TMPH/utf.log"
n17obs "$TMPH/utf.log" > "$TMPH/o2.txt"; [ $? = 0 ] && grep -q "LIVE 1·DYNAMIC 1" "$TMPH/o2.txt" && ! grep -q "KRW-X\|trade_price" "$TMPH/o2.txt" && ok "n17obs UTF-8 원문 1·1, 코인·가격 미출력, 감시기 요약 줄은 세지 않음" || { ng "n17obs UTF-8"; cat "$TMPH/o2.txt"; }
printf '%s\n' 'backend-1  | 12:00:00.000 [main] INFO x - Started CryptoAutoTrader' > "$TMPH/none.log"
n17obs "$TMPH/none.log" > "$TMPH/o3.txt"; [ $? = 0 ] && grep -q "^N17: 미관측" "$TMPH/o3.txt" && ok "n17obs 0건 → 미관측·종료 0" || ng "n17obs 0건"
n17obs > "$TMPH/o4.txt"; [ $? = 3 ] && ok "n17obs 인자 없음 → 종료 3" || ng "n17obs 인자"
# 5 T 블록 판 표지 검사(6곳)가 TB 없으면 멈춘다
for i in 2 3 4 5 6 7; do
  blk $i > "$TMPH/b$i.sh"
  ( unset TB; bash "$TMPH/b$i.sh" ) > "$TMPH/r$i.txt" 2>&1; rc=$?
  [ $rc = 1 ] && grep -q "T 변수가 v1.119(§5-9 87 블록) 판이 아님" "$TMPH/r$i.txt" && ok "블록 $i: TB 없음 → 첫 줄에서 멈춤(바꾼 것 없음)" || { ng "블록 $i 판 표지"; cat "$TMPH/r$i.txt"; }
done
# 6 T0 첫 줄: M 이 #23 폴더면 멈춘다
blk 1 > "$TMPH/b1.sh"
( M=$HOME/maint-n15n16; BTCIN=x; sid203() { :; }; export -f sid203; bash -c ". '$TMPH/vars.sh' >/dev/null; M=\$HOME/maint-n15n16; sid203() { :; }; BTCIN=x; . '$TMPH/b1.sh'" ) > "$TMPH/r1.txt" 2>&1; rc=$?
[ $rc = 1 ] && grep -q "변수 순서 — F 변수 → G 변수 → §5-9 87 T 변수" "$TMPH/r1.txt" && ok "T0: M=~/maint-n15n16 이면 첫 줄에서 멈춤" || { ng "T0 폴더"; cat "$TMPH/r1.txt"; }
rm -rf "$TMPH"
echo "합계 통과 $P · 실패 $F"; [ $F = 0 ]
