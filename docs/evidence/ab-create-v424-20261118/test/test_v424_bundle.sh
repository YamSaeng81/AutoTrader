#!/usr/bin/env bash
# run_bundle.sh v4.2.4 STEP=2 승인값 전달만 시험(총괄 v1.181 §4). v4.2.3 기록 규칙 시험(49/43)은 반복하지 않는다.
# 운영 서버·DB·HTTP 없음: python3·docker·git 대역을 PATH 앞에 둔다. python3 대역은 인자를 기록하고 STUB_RC 로 끝난다.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd); RB="$HERE/bundle/run_bundle.sh"
REALPY=$(command -v python3 || command -v python)
WU=$(mktemp -d); STUB="$WU/stub"; mkdir -p "$STUB"; trap 'rm -rf "$WU"' EXIT
cat > "$STUB/python3" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do case "\$a" in -c|--version) exec "$REALPY" "\$@";; esac; done
printf '%s\n' "\$*" >> "\$CALLS/py"; echo '{"stub":1}'; exit "\${STUB_RC:-0}"
EOF
printf '#!/usr/bin/env bash\nexit 99\n' > "$STUB/docker"; printf '#!/usr/bin/env bash\necho 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d\n' > "$STUB/git"
chmod +x "$STUB"/*
pass=0; fail=0
ck() { if eval "$2"; then pass=$((pass+1)); echo "PASS $1"; else fail=$((fail+1)); echo "FAIL $1"; fi; }
prep() { OUT="$WU/out$1"; CALLS="$WU/calls$1"; mkdir -p "$OUT" "$CALLS"; echo 0 > "$OUT/1.rc"; printf 'tok-%s\n' "$1" > "$WU/tok$1"; chmod 600 "$WU/tok$1"; }
run2() { env PATH="$STUB:$PATH" CALLS="$CALLS" STEP=2 REPO_DIR=/nonexistent TOKEN_FILE="$WU/tok$1" OUT_DIR="$OUT" "${@:2}" bash "$RB" > "$WU/o$1.txt" 2>&1; }

prep 1; run2 1; r=$?
ck "B1 승인값 없음 → 0 아님(rc=$r)·도구 호출 0·2.rc 없음" "[ $r -ne 0 ] && [ ! -e $CALLS/py ] && [ ! -e $OUT/2.rc ]"
prep 2; run2 2 AB_DATE=2026-11-18 AB_C= AB_T0=2026-11-18T14:15:00+09:00; r=$?
ck "B2 AB_C 빈 값 → 0 아님(rc=$r)·도구 호출 0·2.rc 없음" "[ $r -ne 0 ] && [ ! -e $CALLS/py ] && [ ! -e $OUT/2.rc ]"
prep 3; run2 3 AB_DATE=2026-11-18 AB_C=2026-11-18T14:00:00+09:00 AB_T0=2026-11-18T14:15:00+09:00; r=$?
ck "B3 승인값 전달 → rc0·2.rc=0" "[ $r -eq 0 ] && [ \"\$(cat $OUT/2.rc)\" = 0 ]"
ck "B3a 도구 인자에 승인 날짜·C·T0·--execute" "grep -qF -- '--approved-date 2026-11-18 --approved-c 2026-11-18T14:00:00+09:00 --approved-t0 2026-11-18T14:15:00+09:00 --execute' $CALLS/py"
ck "B3b steps.log 에 승인값 줄" "grep -qF 'AB_C=2026-11-18T14:00:00+09:00' $OUT/steps.log"
prep 4; run2 4 AB_DATE=2026-11-18 AB_C=2026-11-18T14:00:00+09:00 AB_T0=2026-11-18T14:15:00+09:00 STUB_RC=2; r=$?
ck "B4 도구 2(승인 창 밖) → rc2·2.rc=2(다음 단계 막힘)" "[ $r -eq 2 ] && [ \"\$(cat $OUT/2.rc)\" = 2 ]"
echo "통과 $pass/$((pass+fail))"; [ $fail -eq 0 ]
