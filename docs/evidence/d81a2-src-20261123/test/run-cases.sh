#!/usr/bin/env bash
# D81A2 새 실행 출처 영향 확인(총괄 v1.227 §3-1) — 정상 새 출처 / 옛 U0 혼입 / 새 U0 변조 3경계 + 시작 전 가드 거절 5건.
# 사용: run-cases.sh <회차 이름>   → /d/tmp/d81a2-run-<회차> 에 키트·결과를 만든다(이미 있으면 거절). 성공 수를 이전 회차와 합치지 않는다.
# 실행 출처는 고정 커밋에서만 꺼낸다(작업 사본 파일을 쓰지 않는다). 실제 원격 도구는 부르지 않는다.
set -eu
R=${1:?회차 이름}
REPO='D:/Claude Code/projects/crypto-auto-trader'
SRCC=65a384e563a90d949c94a4e343ea491939e6f5f7     # D81A2 고정 출처(U0 91ea9342)
OLDC=cd8bd76fdd4d46bac893a98f4eab36cceb990c99     # 옛 출처(U0 736351db)
PYEXE=/c/Users/SangJoon_Lee/AppData/Local/Microsoft/WindowsApps/python.exe
HERE="$(cd "$(dirname "$0")" && pwd)"
W=/d/tmp/d81a2-run-$R; K=$W/kit
[ ! -e "$W" ] || { echo "회차 폴더가 이미 있음: $W" >&2; exit 1; }
case "$W" in *' '*) echo "공백 있는 경로 거절" >&2; exit 1;; esac
mkdir -p "$K/runner" "$K/fake" "$K/tripwire" "$K/pybin" "$W/cases" "$W/home"
git -C "$REPO" show "$SRCC:docs/evidence/d81a2-src-20261123/runner/run.sh" > "$W/prod-run.sh"
git -C "$REPO" show "$SRCC:docs/evidence/d81a2-src-20261123/runner/extract.py" > "$W/extract.py"
cp "$HERE/fake-ssh.sh" "$K/fake/fake-ssh.sh"; chmod +x "$K/fake/fake-ssh.sh"
bash "$HERE/make-test-copy.sh" "$W/prod-run.sh" "$K/fake/fake-ssh.sh" "$K/runner/run-test.sh" > "$W/make-test-copy.txt"
diff "$W/prod-run.sh" "$K/runner/run-test.sh" > "$W/prod-vs-test-copy.diff" || true
# 감지용 대체 파일: 이름이 불리면 기록만 하고 97로 끝난다(실제 도구를 부르지 않는다)
for t in ssh scp sftp curl wget nc telnet docker psql git; do
  printf '#!/bin/bash\nprintf "%%s %%s\\n" "$0" "$*" >> %s/tripwire.log\nexit 97\n' "$W" > "$K/tripwire/$t"; chmod +x "$K/tripwire/$t"
done
printf '#!/bin/bash\nexec %s "$@"\n' "$PYEXE" > "$K/pybin/python"; chmod +x "$K/pybin/python"
# 고정 커밋에서 11블록 추출(로컬 Git 읽기만)
PYTHONIOENCODING=utf-8 python -I "$W/extract.py" "$W/ext" "$SRCC" > "$W/extract.txt" 2>&1 || { echo "추출 실패" >&2; exit 1; }
git -C "$REPO" show "$OLDC:docs/evidence/d81d4r-20261120/d4/blocks-d81/U0.sh" > "$W/old-U0.sh"
P="$K/tripwire:$K/pybin:/usr/bin:/bin"
ENVB=(env -i HOME="$W/home" PATH="$P" PYTHONIOENCODING=utf-8 LANG=C.UTF-8)
# 시작 전 확인: PATH 선택이 감지용 대체 파일로 가는가, 가짜 식별 응답
{ echo "command -v ssh: $("${ENVB[@]}" bash --noprofile --norc -c 'command -v ssh')"
  echo "command -v psql: $("${ENVB[@]}" bash --noprofile --norc -c 'command -v psql')"
  echo "fake: $K/fake/fake-ssh.sh sha256 $(sha256sum < "$K/fake/fake-ssh.sh" | cut -d' ' -f1)"
  echo "identify: $("${ENVB[@]}" bash --noprofile --norc "$K/fake/fake-ssh.sh" --identify)"
  echo "test-copy: $(sha256sum < "$K/runner/run-test.sh" | cut -d' ' -f1)  prod-run: $(sha256sum < "$W/prod-run.sh" | cut -d' ' -f1)"; } > "$W/preflight.txt"
grep -qx "command -v ssh: $K/tripwire/ssh" "$W/preflight.txt" && grep -qx "command -v psql: $K/tripwire/psql" "$W/preflight.txt" \
  || { echo "PATH 선택이 감지용 대체 파일이 아님 — 시험 시작 안 함" >&2; exit 1; }
printf 'case\tkind\texpect_rc\trc\tfake_calls\texpect_calls\tout_dir\tincomplete\texit_codes_lines\tcmd_match\tverdict\n' > "$W/results.tsv"
pass=0; fail=0
# runcase <이름> <종류> <기대 rc> <기대 호출> <블록 준비 함수> [env 덮어쓰기...]
runcase() {
  local c=$1 kind=$2 erc=$3 ecall=$4 prep=$5; shift 5
  local C=$W/cases/$c; mkdir -p "$C/run"; cp -r "$W/ext" "$C/run/blocks-d81"; $prep "$C/run/blocks-d81"
  local kit=${CASE_KIT:-$K}
  set +e
  "${ENVB[@]}" D81_TEST_HOST=d81-test.invalid D81_FAKE_BIN="$kit/fake/fake-ssh.sh" D81_FAKE_LOG="$C/fake.log" D4RUN="$C/run" "$@" \
    bash --noprofile --norc "$kit/runner/run-test.sh" U0 deploy > "$C/console.txt" 2>&1
  local rc=$?; set -e; echo "$rc" > "$C/rc.txt"
  local calls=0; [ -f "$C/fake.log" ] && calls=$(wc -l < "$C/fake.log")
  local od=no inc=no ecl=0 cm=-
  [ -d "$C/run/out" ] && od=yes; [ -e "$C/run/out/INCOMPLETE.txt" ] && inc=yes
  [ -f "$C/run/out/exit-codes.txt" ] && ecl=$(wc -l < "$C/run/out/exit-codes.txt")
  if [ "$calls" = 1 ]; then
    # 기대 문자열은 실행 틀과 같은 env -i 환경(LANG=C.UTF-8)에서 만든다 — r1 은 구동기 셸 로케일로 %q 가 달라 DIFF(구동기 결함)
    "${ENVB[@]}" B="$C/run/blocks-d81" bash --noprofile --norc -c \
      's="$(cat "$B/F.sh" "$B/G.sh" "$B/U변수.sh" "$B/U0.sh")"; printf "%s" "bash -c $(printf "%q" "$s")"' > "$C/expected-cmd.txt"
    cmp -s "$C/expected-cmd.txt" "$C/fake.log.cmd.1" && cm=same || cm=DIFF
  fi
  local v=ok
  [ "$rc" = "$erc" ] && [ "$calls" = "$ecall" ] || v=FAIL
  if [ "$ecall" = 1 ]; then [ "$cm" = same ] && [ "$inc" = no ] && [ "$ecl" = 1 ] || v=FAIL
  else [ "$od" = no ] || v=FAIL; fi
  [ "$v" = ok ] && pass=$((pass+1)) || fail=$((fail+1))
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$c" "$kind" "$erc" "$rc" "$calls" "$ecall" "$od" "$inc" "$ecl" "$cm" "$v" >> "$W/results.tsv"
}
keep()   { :; }
oldu0()  { cp "$W/old-U0.sh" "$1/U0.sh"; }
tamper() { printf '#\n' >> "$1/U0.sh"; }
# 시작 전 가드 거절(가짜 누락·다른 경로·다른 해시·Windows PATH·운영 별칭) — 모두 종료2·호출0
runcase g1_fake_missing  guard 2 0 keep D81_FAKE_BIN=
mkdir -p "$K/fake2"; cp "$K/fake/fake-ssh.sh" "$K/fake2/fake-ssh.sh"
runcase g2_fake_other_path guard 2 0 keep D81_FAKE_BIN="$K/fake2/fake-ssh.sh"
cp -r "$K" "$W/kit-h"; printf '#\n' >> "$W/kit-h/fake/fake-ssh.sh"
CASE_KIT=$W/kit-h runcase g3_fake_hash_mismatch guard 2 0 keep
runcase g4_win_path guard 2 0 keep PATH="C:\\Windows;$P"
runcase g5_prod_alias_host guard 2 0 keep D81_TEST_HOST="cat""-prod"
# v1.227 §3-1 영향 3경계
runcase b1_new_source_ok   boundary 0 1 keep
runcase b2_old_u0_mixed    boundary 2 0 oldu0
runcase b3_new_u0_tampered boundary 2 0 tamper
tw=0; [ -f "$W/tripwire.log" ] && tw=$(wc -l < "$W/tripwire.log")
{ echo "회차 $R: 통과 $pass / $((pass+fail)), 실패 $fail, 감지용 대체 파일 호출 $tw"
  echo "출처 $SRCC · 옛 U0 출처 $OLDC"; } > "$W/summary.txt"
cat "$W/summary.txt"; cat "$W/results.tsv"
[ "$fail" = 0 ] && [ "$tw" = 0 ]
