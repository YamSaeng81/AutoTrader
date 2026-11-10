#!/usr/bin/env bash
# N21 적용안 블록의 로컬 대역 검증 — 서버·docker·cron·네트워크를 쓰지 않는다.
# 실행: bash standin/run.sh <저장소 경로> <출력 폴더>
# 대역: HOME=임시 폴더, ~/crypto_trader/AutoTrader = 저장소의 로컬 복제(1899b49 체크아웃, origin = 로컬 저장소),
#       docker·crontab·pgrep 은 PATH 앞의 가짜 명령, date +%M 만 가짜 분(FAKE_MIN)으로 바꾼다.
set -u
REPO=$(cd "$1" && pwd); OUT=$2; mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); B="$HERE/../blocks"
IMG=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1
OLD=1899b49f633f8aefdc66b15c42debdaba8b3852c; NEW=6379ee0b2a7527b8136d7fba27e83881980d8555
CRON_TXT='19 * * * * cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1'
CRONH_FAKE=$(printf '%s
' "$CRON_TXT" | sha256sum | cut -d' ' -f1)
PASS=0; FAILN=0

mkenv() {   # $1 = 사례 이름 → 새 HOME
  local H; H=$(mktemp -d); mkdir -p "$H/bin" "$H/crypto_trader"
  git -c core.autocrlf=false clone -q --shared --no-checkout "$REPO" "$H/crypto_trader/AutoTrader"
  git -C "$H/crypto_trader/AutoTrader" -c core.autocrlf=false -c advice.detachedHead=false checkout -q "$OLD"
  git -C "$H/crypto_trader/AutoTrader" config core.autocrlf false
  printf '%s\n' "$CRON_TXT" > "$H/cron.txt"
  echo "$IMG" > "$H/img.txt"; : > "$H/pgrep.txt"
  local L="$H/crypto_trader/AutoTrader/scripts/prospective/freshness_log.jsonl"
  echo '{"at": "2026-11-10T00:19:30+00:00", "green": 22, "unknown": 0, "red": 0, "redCoins": [], "unknownCoins": [], "rechecked": [], "rows": {"KRW-BTC": {"dbTo": "x", "exTo": "x", "exErr": null, "count": 500, "rechecked": false}}}' > "$L"
  cat > "$H/bin/docker" <<EOS
#!/usr/bin/env bash
case "\$1" in compose) echo cid-backend ;; inspect) cat "$H/img.txt" ;; *) echo "가짜 docker: \$*" >&2; exit 9 ;; esac
EOS
  cat > "$H/bin/crontab" <<EOS
#!/usr/bin/env bash
[ "\$1" = -l ] && cat "$H/cron.txt" || { echo "가짜 crontab: 쓰기 거절" >&2; exit 9; }
EOS
  cat > "$H/bin/pgrep" <<EOS
#!/usr/bin/env bash
[ -s "$H/pgrep.txt" ] && { cat "$H/pgrep.txt"; exit 0; } || exit 1
EOS
  cat > "$H/bin/date" <<EOS
#!/usr/bin/env bash
[ "\$*" = "+%M" ] && [ -n "\${FAKE_MIN:-}" ] && { echo "\$FAKE_MIN"; exit 0; }
exec /usr/bin/date "\$@"
EOS
  chmod +x "$H/bin/"*
  echo "$H"
}

blk() {   # $1 HOME, $2.. 블록 이름 — vars.sh 를 넣은 같은 셸에서 블록을 차례로 실행
  local H=$1; shift
  # 대역 치환 1개: 실제 crontab 원문은 서버에만 있으므로 CRONH 를 가짜 원문(CRON_TXT)의 해시로 바꾼다
  local s="source '$B/vars.sh'; CRONH=$CRONH_FAKE"; for b in "$@"; do s="$s; source '$B/$b.sh'"; done
  HOME="$H" PATH="$H/bin:$PATH" FAKE_MIN="${FAKE_MIN:-30}" bash -c "$s" 2>&1
}

expect() {   # $1 사례, $2 기대(통과/멈춤 문구), $3 출력
  local name=$1 want=$2 out=$3
  printf '%s\n' "$out" > "$OUT/$name.out"
  if printf '%s' "$out" | grep -qF -- "$want"; then PASS=$((PASS+1)); echo "PASS $name — $want"
  else FAILN=$((FAILN+1)); echo "FAIL $name — 기대 '$want'"; fi
}

newrow() {  # $1 HOME, $2 exErr(null/"net"), $3 exTo(null/"x")
  echo "{\"at\": \"2099-01-01T00:19:30+00:00\", \"green\": 21, \"unknown\": 1, \"red\": 0, \"redCoins\": [], \"unknownCoins\": [\"KRW-BTC\"], \"rechecked\": [\"KRW-BTC\"], \"rows\": {\"KRW-BTC\": {\"dbTo\": \"x\", \"exTo\": $3, \"exErr\": $2, \"count\": 500, \"rechecked\": true}}}" >> "$1/crypto_trader/AutoTrader/scripts/prospective/freshness_log.jsonl"
}

# s01 정상: A0 → A1 → 새 판 행 → A2 → 운영자 판단 되돌림
H=$(mkenv s01)
expect s01-a0 "✅ A0 통과" "$(blk "$H" A0)"
expect s01-a1 "✅ A1 통과" "$(blk "$H" A1)"
expect s01-head-new "$NEW" "$(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"
expect s01-a2-norow "새 관측 행 없음" "$(blk "$H" A2)"
newrow "$H" '"net"' null
expect s01-a2 "✅ A2" "$(blk "$H" A2)"
expect s01-rollback "↩ 되돌림 끝" "$(blk "$H" A1-rollback)"
expect s01-head-old "$OLD" "$(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"
rm -rf "$H"

# s02 :19 근처 — 아무것도 바꾸지 않고 멈춤
H=$(mkenv s02); expect s02-window "매시 :14~:24" "$(FAKE_MIN=19 blk "$H" A0)"
expect s02-head "$OLD" "$(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"; rm -rf "$H"
# s03 회차가 돌고 있음
H=$(mkenv s03); echo "4242 /usr/bin/python3 scripts/prospective/start_66.py freshness --save" > "$H/pgrep.txt"
expect s03-running "회차가 아직 돌고 있다" "$(blk "$H" A0)"; rm -rf "$H"
# s04 추적 파일 변경
H=$(mkenv s04); echo "# x" >> "$H/crypto_trader/AutoTrader/scripts/prospective/start_66.py"
expect s04-dirty "추적 파일 변경 있음" "$(blk "$H" A0)"; rm -rf "$H"
# s05 이미지 다름
H=$(mkenv s05); echo sha256:0000 > "$H/img.txt"
expect s05-image "426a09a2… 가 아님" "$(blk "$H" A0)"; rm -rf "$H"
# s06 crontab 다름
H=$(mkenv s06); echo "# 다른 줄" >> "$H/cron.txt"
expect s06-cron "f2dce280… 과 다름" "$(blk "$H" A0)"; rm -rf "$H"
# s07 새 시험 파일이 추적 안 된 채 이미 있음
H=$(mkenv s07); echo x > "$H/crypto_trader/AutoTrader/scripts/prospective/test_freshness_recheck_fail.py"
expect s07-untracked "이미 있다" "$(blk "$H" A0)"; rm -rf "$H"
# s08 A0 없이 A1
H=$(mkenv s08); expect s08-noa0 "A0 미통과" "$(blk "$H" A1)"; rm -rf "$H"
# s09 A0 뒤 회차 시작 → A1 은 바꾸지 않고 멈춤
H=$(mkenv s09); blk "$H" A0 > /dev/null; echo "4243 python3 start_66.py freshness" > "$H/pgrep.txt"
expect s09-a1-running "아직 바꾼 것 없음" "$(blk "$H" A1)"
expect s09-head "$OLD" "$(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"; rm -rf "$H"
# s10 A2 에서 N21 상태 행(실패인데 exTo 남음) → 멈춤
H=$(mkenv s10); blk "$H" A0 A1 > /dev/null; newrow "$H" '"net"' '"x"'
expect s10-stale "N21 상태 행 있음" "$(blk "$H" A2)"; rm -rf "$H"
# s11 A2 에서 이미지가 바뀜 → 멈춤
H=$(mkenv s11); blk "$H" A0 A1 > /dev/null; newrow "$H" null '"x"'; echo sha256:1111 > "$H/img.txt"
expect s11-a2-image "이미지가 바뀌었다" "$(blk "$H" A2)"; rm -rf "$H"
# s12 되돌림 직전 추적 파일 변경 → 덮어쓰지 않음
H=$(mkenv s12); blk "$H" A0 A1 > /dev/null; echo "# y" >> "$H/crypto_trader/AutoTrader/scripts/prospective/start_66.py"
expect s12-rb-dirty "덮어쓰지 않고 보고" "$(blk "$H" A1-rollback)"
expect s12-head "$NEW" "$(git -C "$H/crypto_trader/AutoTrader" rev-parse HEAD)"; rm -rf "$H"

echo "대역 사례 $PASS/$((PASS+FAILN)) 통과" | tee "$OUT/summary.txt"
[ "$FAILN" = 0 ]
