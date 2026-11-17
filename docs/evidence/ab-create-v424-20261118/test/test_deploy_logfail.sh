#!/usr/bin/env bash
# deploy-20261118.sh 기록 실패 보완 시험(총괄 v1.182 §4) — 운영 접속 없음. Linux/WSL 에서 실행한다.
# tee 대역: TEE_FAIL_FROM=N 이면 N번째 tee 호출부터 파일에 쓰지 않고 28 로 끝난다(디스크/권한 실패 대역).
# ssh 대역: 호출 횟수를 세고 원격 명령을 임시 HOME 에서 실행한다. SSH_FAIL=1 → 255, SSH_EMPTY=1 → 원격 tar 실패.
# tee 호출 순서(정상): 1 archive · 2 풀기 · 3 로컬 16개 · 4 목록 해시 · 5 원격 풀기 rc(첫 ssh 직후) · 6 원격 검증 rc · 7 원격 해시 출력(2줄) · 8 완료 → 기록 9줄
# OLD=<옛 b3db2858 파일 경로> 를 주면 R1 로 현행 공백(기록 없음·ssh 2회·종료 0) 재현을 따로 보인다(통과 수에 넣지 않음).
set -u
SRC=$(cd "$(dirname "$0")/.." && pwd); W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
REAL_TEE=$(command -v tee)
mkdir -p "$W/repo/docs/evidence" "$W/home" "$W/bin"; cp -r "$SRC" "$W/repo/docs/evidence/"; rm -rf "$W/repo/docs/evidence/ab-create-v424-20261118/create/__pycache__" "$W/repo/docs/evidence/ab-create-v424-20261118/deploy-20261118-run.log"
cat > "$W/bin/ssh" <<EOS
#!/usr/bin/env bash
echo x >> "$W/sshcount"
[ -n "\${SSH_FAIL:-}" ] && exit 255
cmd="\${@: -1}"; [ -n "\${SSH_EMPTY:-}" ] && exec < /dev/null; HOME="$W/home" bash -c "\$cmd"
EOS
cat > "$W/bin/tee" <<EOS
#!/usr/bin/env bash
echo x >> "$W/teecount"; n=\$(wc -l < "$W/teecount")
if [ -n "\${TEE_FAIL_FROM:-}" ] && [ "\$n" -ge "\$TEE_FAIL_FROM" ]; then cat; echo "tee: 쓰기 실패(대역)" >&2; exit 28; fi
exec "$REAL_TEE" "\$@"
EOS
chmod +x "$W/bin/ssh" "$W/bin/tee"; cd "$W/repo" && git init -q && git add -A && git -c user.email=t@t -c user.name=t commit -qm t; CG=$(git rev-parse HEAD)
D=docs/evidence/ab-create-v424-20261118; L=$D/deploy-20261118-run.log; pass=0; fail=0
run() { local script=$1 c=$2; shift 2; rm -f "$L" "$W/sshcount" "$W/teecount"; rm -rf "$W/home/ab-run-20261118"; : > "$W/sshcount"
        env PATH="$W/bin:$PATH" "$@" bash "$script" "$c" > "$W/o" 2>&1; R=$?; S=$(wc -l < "$W/sshcount"); N=$( [ -f "$L" ] && wc -l < "$L" || echo 없음); }
chk() { local name=$1 cond=$2; if eval "$cond"; then pass=$((pass+1)); echo "PASS $name (rc=$R ssh=$S 기록줄=$N)"; else fail=$((fail+1)); echo "FAIL $name (rc=$R ssh=$S 기록줄=$N) 조건: $cond"; sed 's/^/  /' "$W/o"; fi; }

run "$D/deploy-20261118.sh" "$CG"
chk "L4 정상 → 0 · ssh 2 · 기록 9줄 · 완료 줄" '[ $R -eq 0 ] && [ $S -eq 2 ] && [ "$N" = 9 ] && grep -q "배치 완료" "$L"'
run "$D/deploy-20261118.sh" "$CG" TEE_FAIL_FROM=1
chk "L1 첫 기록 실패 → 8 · 원격 호출 0" '[ $R -eq 8 ] && [ $S -eq 0 ] && grep -q "원격 호출 전" "$W/o"'
run "$D/deploy-20261118.sh" "$CG" TEE_FAIL_FROM=5
chk "L2 첫 원격 호출 직후 기록 실패 → 8 · 원격 검증 ssh 미실행 · 미확인 표시" '[ $R -eq 8 ] && [ $S -eq 1 ] && [ -d "$W/home/ab-run-20261118" ] && grep -q "원격 상태 미확인/부분" "$W/o" && ! grep -q "배치 완료" "$L"'
run "$D/deploy-20261118.sh" "$CG" TEE_FAIL_FROM=8
chk "L5 마지막 완료 기록 실패 → 8 (성공 아님)" '[ $R -eq 8 ] && [ $S -eq 2 ] && ! grep -q "배치 완료" "$L"'
run "$D/deploy-20261118.sh" "$CG" TEE_FAIL_FROM=7
chk "L6 원격 해시 출력 기록 실패 → 8 · 완료 줄 없음" '[ $R -eq 8 ] && [ $S -eq 2 ] && ! grep -q "배치 완료" "$L"'
run "$D/deploy-20261118.sh" "$CG" SSH_EMPTY=1 TEE_FAIL_FROM=5
chk "L3 원격 tar 실패 + 기록 실패 → 원래 6 유지" '[ $R -eq 6 ] && [ $S -eq 1 ] && grep -q "앞 단계 실패 종료값 6 유지" "$W/o"'
run "$D/deploy-20261118.sh" "$CG" SSH_FAIL=1 TEE_FAIL_FROM=5
chk "L3b ssh 접속 실패 + 기록 실패 → 원래 255 유지" '[ $R -eq 255 ] && [ $S -eq 1 ]'
run "$D/deploy-20261118.sh" 0000000000000000000000000000000000000000 TEE_FAIL_FROM=1
chk "L3c git archive 실패 + 기록 실패 → 원래 3 유지 · 원격 호출 0" '[ $R -eq 3 ] && [ $S -eq 0 ]'
echo "통과 $pass/$((pass+fail))"
if [ -n "${OLD:-}" ]; then
  run "$OLD" "$CG" TEE_FAIL_FROM=1
  echo "R1 옛 $(sha256sum < "$OLD" | cut -c1-8) 첫 기록부터 실패: rc=$R ssh=$S 기록줄=$N (현행 공백 재현, 통과 수 아님)"
fi
[ $fail -eq 0 ]
