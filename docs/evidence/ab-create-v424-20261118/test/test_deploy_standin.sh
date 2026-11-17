#!/usr/bin/env bash
# deploy-20261118.sh 종료값 분리 시험 — 운영 접속 없음. 임시 저장소에 이 폴더를 커밋하고, ssh 대역은 원격 명령을 임시 HOME 에서 실행한다.
# D5 는 ssh 대역이 255 로 끝나는 경우(접속 실패), D6 은 원격 tar 가 실패하는 경우(빈 입력).
set -u
SRC=$(cd "$(dirname "$0")/.." && pwd); W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/repo/docs/evidence" "$W/home" "$W/bin"; cp -r "$SRC" "$W/repo/docs/evidence/"; rm -rf "$W/repo/docs/evidence/ab-create-v424-20261118/create/__pycache__" "$W/repo/docs/evidence/ab-create-v424-20261118/deploy-20261118-run.log"
cat > "$W/bin/ssh" <<EOS
#!/usr/bin/env bash
[ -n "\${SSH_FAIL:-}" ] && exit 255
cmd="\${@: -1}"; [ -n "\${SSH_EMPTY:-}" ] && exec < /dev/null; HOME="$W/home" bash -c "\$cmd"
EOS
chmod +x "$W/bin/ssh"; cd "$W/repo" && git init -q && git add -A && git -c user.email=t@t -c user.name=t commit -qm t; C=$(git rev-parse HEAD)
D=docs/evidence/ab-create-v424-20261118; pass=0; fail=0
t() { local name=$1 want=$2; shift 2; rm -f "$D/deploy-20261118-run.log"; env PATH="$W/bin:$PATH" "$@" bash "$D/deploy-20261118.sh" "$C" > "$W/o" 2>&1; local r=$?
      if [ $r -eq "$want" ]; then pass=$((pass+1)); echo "PASS $name rc=$r"; else fail=$((fail+1)); echo "FAIL $name rc=$r 기대 $want"; sed 's/^/  /' "$W/o"; fi; }
t "D5 ssh 접속 실패 → 255(재시도 없음)" 255 SSH_FAIL=1
t "D6 원격 tar 실패(빈 입력) → 6" 6 SSH_EMPTY=1
rm -rf "$W/home/ab-run-20261118"
t "D1 정상 → 0" 0
[ -f "$W/home/ab-run-20261118/steps-20261118.sh" ] && (cd "$W/home/ab-run-20261118" && sha256sum --quiet -c bundle/SHA256SUMS.bundle) && { pass=$((pass+1)); echo "PASS D1a 원격 사본 16개 해시"; } || { fail=$((fail+1)); echo "FAIL D1a"; }
t "D2 원격 폴더 이미 있음 → 5" 5
C=0000000000000000000000000000000000000000; t "D4 없는 커밋 → 3" 3
echo "통과 $pass/$((pass+fail))"; [ $fail -eq 0 ]
