# U 블록 대역 확인(로컬, 서버·DB·docker 없음). 사용: PYTHONIOENCODING=utf-8 bash check_u.sh  (blocks/ 안에서)
set -u
B=u-blocks.md; P=0; F=0
EV="/d/Claude Code/projects/crypto-auto-trader/docs/evidence"
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
# 1 판 표지·폴더·이미지
[ "$M" = "$HOME/maint-n19" ] && [ "$UB" = 92 ] && ok "M=~/maint-n19·UB=92" || ng "M/UB"
[ "$OLD" = 7a944723c2959898226c2ad80f6b079ab5678296 ] && [ "$NEW" = ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8 ] && ok "OLD 7a94472·NEW ee509cf" || ng "OLD/NEW"
[ "$IMGOLD" = sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6 ] && ok "IMGOLD=#24 e51b512a(T1 실제 새 이미지)" || ng IMGOLD
[ "$IMGPREV4" = sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 ] && [ "$IMGPREV5" = sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62 ] && ok "IMGPREV4=#22 9cb5d84f·IMGPREV5=#23 9818254c" || ng IMGPREV
# 2 DIFFN = 실제 로컬 git diff
[ "$(git -C '/d/Claude Code/projects/cat-n19' diff --name-only $OLD $NEW)" = "$DIFFN" ] && ok "DIFFN = git diff 7a94472 ee509cf (2)" || ng DIFFN
# 3 U1 jar 검사식에 실제 로컬 jar 출력(LF)
tr -d '\r' < ../jars/diff-7a94472-ee509cf.txt > "$TMPH/jar-diff.txt"
[ "$(grep -v '^(끝)' "$TMPH/jar-diff.txt")" = "$RARX" ] && ok "RARX = 실제 jar 출력 3줄" || ng RARX
grep -qx '(끝) 881 881' "$TMPH/jar-diff.txt" && ok "끝 줄 881 881" || ng END
grep -q "grep -qx '(끝) 881 881'" $B && grep -q '"\$RARX" \] || stop "jar 차이가 N19 3줄이 아님' $B && ok "U1 검사식 문구" || ng "U1 문구"
# 3b 음성 대조: #24 jar 출력(6줄)이면 걸린다 — 끝 줄이 같으므로 RARX 비교가 막는다
tr -d '\r' < "$EV/n17-rest-price-1103f48/jars/diff-1103f48-7a94472.txt" > "$TMPH/old.txt"
[ "$(grep -v '^(끝)' "$TMPH/old.txt")" != "$RARX" ] && ok "음성: #24 jar 출력은 U1 의 RARX 비교에서 멈춘다(끝 줄은 같아도)" || ng "음성 jar"
# 4 n19obs — 실제 시험 로그 줄(r4, ee509cf 고정 문구)·컨테이너 접두·UTF-8 원문·옛 f6868a5 문구 미계수·0건·인자 오류
X=../runs/r4-fix-related/xml/TEST-com.cryptoautotrader.api.service.N19RestFallbackItemDefenseTest.xml
grep -E "^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} \[" "$X" | sed 's/^/backend-1  | /' > "$TMPH/app.log"
FM=$(grep -c "REST 폴백 시세 항목 건너뜀 . 가격 형식이 숫자가 아님$" "$TMPH/app.log"); NP=$(grep -cE "REST 폴백 시세 항목 건너뜀 . 가격이 아님 \(부호=(0|음수)\)$" "$TMPH/app.log"); CT=$(grep -c "REST 폴백 시세 항목 건너뜀 . 코인 형식이 문자열이 아님$" "$TMPH/app.log"); ALL=$(grep -c "REST 폴백 시세 항목 건너뜀" "$TMPH/app.log")
n19obs "$TMPH/app.log" > "$TMPH/o1.txt"; rc=$?
[ $rc = 0 ] && grep -q "형식 이상 $FM·가격 아님 $NP·코인 형식 $CT" "$TMPH/o1.txt" && [ "$FM" -gt 0 ] && [ "$NP" -gt 0 ] && [ "$CT" -gt 0 ] && [ $((FM+NP+CT)) = "$ALL" ] && ! grep -q "KRW-\|FORGED\|부호" "$TMPH/o1.txt" && ok "n19obs 실제 시험 로그 형식 $FM·가격 아님 $NP·코인 형식 $CT(건너뜀 줄 전체 $ALL 과 같음), 코인·부호 미출력" || { ng "n19obs 실제 로그"; cat "$TMPH/o1.txt"; echo "FM=$FM NP=$NP CT=$CT ALL=$ALL"; }
grep "REST 폴백 시세 항목 건너뜀" "$TMPH/app.log" | grep -q "market=\|FORGED\|KRW-" && ng "시험 로그의 건너뜀 줄에 market 원문이 있음" || ok "시험 로그의 건너뜀 줄 $ALL 개 모두 market 원문 없음"
printf '%s\n' 'backend-1  | 12:00:00.000 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 폴백 시세 항목 건너뜀 — 가격 형식이 숫자가 아님' \
 'backend-1  | 12:00:01.000 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 폴백 시세 항목 건너뜀 — 가격이 아님 (부호=음수)' \
 'backend-1  | 12:00:01.500 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 폴백 시세 항목 건너뜀 — 코인 형식이 문자열이 아님' \
 'backend-1  | 12:00:02.000 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 강제 갱신 시세가 가격이 아님 — 발행하지 않음 (market=KRW-Z, trade_price=0)' \
 'backend-1  | 12:00:02.500 [scheduling-1] WARN  c.c.api.service.LiveTradingService - [§9] REST 폴백 시세 항목 건너뜀 — 가격이 아님 (market=KRW-OLD, 부호=0)' \
 'backend-1  | 12:00:03.000 [scheduling-1] ERROR c.c.api.service.LiveTradingService - [§9] REST ticker fallback 실패: x' > "$TMPH/utf.log"
n19obs "$TMPH/utf.log" > "$TMPH/o2.txt"; [ $? = 0 ] && grep -q "형식 이상 1·가격 아님 1·코인 형식 1" "$TMPH/o2.txt" && ! grep -q "KRW-\|부호" "$TMPH/o2.txt" && ok "n19obs UTF-8 원문 1·1·1, 코인·부호 미출력, N17 줄·옛 f6868a5 문구(market= 포함)·기존 ERROR 줄은 세지 않음" || { ng "n19obs UTF-8"; cat "$TMPH/o2.txt"; }
n17obs "$TMPH/utf.log" > "$TMPH/o2b.txt"; [ $? = 0 ] && grep -q "LIVE 1·DYNAMIC 0" "$TMPH/o2b.txt" && ok "n17obs 는 그대로 — N19 줄을 N17 로 세지 않음" || { ng "n17obs 분리"; cat "$TMPH/o2b.txt"; }
printf '%s\n' 'backend-1  | 12:00:00.000 [main] INFO x - Started CryptoAutoTrader' > "$TMPH/none.log"
n19obs "$TMPH/none.log" > "$TMPH/o3.txt"; [ $? = 0 ] && grep -q "^N19: 미관측" "$TMPH/o3.txt" && ok "n19obs 0건 → 미관측·종료 0" || ng "n19obs 0건"
n19obs > "$TMPH/o4.txt"; [ $? = 3 ] && ok "n19obs 인자 없음 → 종료 3" || ng "n19obs 인자"
# 5 U 블록 판 표지 검사(6곳)가 UB 없으면 멈춘다
for i in 2 3 4 5 6 7; do
  blk $i > "$TMPH/b$i.sh"
  ( unset UB; bash "$TMPH/b$i.sh" ) > "$TMPH/r$i.txt" 2>&1; rc=$?
  [ $rc = 1 ] && grep -q "U 변수가 v1.124(§5-9 92 블록) 판이 아님" "$TMPH/r$i.txt" && ok "블록 $i: UB 없음 → 첫 줄에서 멈춤(바꾼 것 없음)" || { ng "블록 $i 판 표지"; cat "$TMPH/r$i.txt"; }
done
# 6 U0 첫 줄: M 이 #24 폴더면 멈춘다
blk 1 > "$TMPH/b1.sh"
( bash -c ". '$TMPH/vars.sh' >/dev/null; M=\$HOME/maint-n17; sid203() { :; }; BTCIN=x; . '$TMPH/b1.sh'" ) > "$TMPH/r1.txt" 2>&1; rc=$?
[ $rc = 1 ] && grep -q "변수 순서 — F 변수 → G 변수 → §5-9 92 U 변수" "$TMPH/r1.txt" && ok "U0: M=~/maint-n17 이면 첫 줄에서 멈춤" || { ng "U0 폴더"; cat "$TMPH/r1.txt"; }
rm -rf "$TMPH"
echo "합계 통과 $P · 실패 $F"; [ $F = 0 ]
