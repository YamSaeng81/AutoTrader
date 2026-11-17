#!/bin/bash
# ssh_change.sh v2 시험 — 일회용 ubuntu:22.04 컨테이너(openssh-server 8.9p1) 안 실제 경로·실제 sshd. 운영 서버 접속 없음.
# v1 시험(sshadmin-20261117/test, 40/40)은 반복하지 않는다. 총괄 v1.181 §3 의 바뀐 경로(정상 1벌 + 음성)만 본다.
# systemctl 은 컨테이너에 없어 대역: /tmp/svcmode 가 active|inactive|unknown|reloadfail 이고 호출을 /tmp/svc.log 에 남긴다.
set -u
export DEBIAN_FRONTEND=noninteractive LC_ALL=C
apt-get update -qq >/dev/null && apt-get install -y -qq openssh-server openssh-client >/dev/null || { echo "설치 실패"; exit 99; }
echo "openssh-server $(dpkg-query -W -f='${Version}' openssh-server)"
cat > /usr/local/bin/systemctl <<'EOF'
#!/bin/bash
mode=$(cat /tmp/svcmode 2>/dev/null || echo active); echo "$*" >> /tmp/svc.log
p=$(cat /run/sshd.pid 2>/dev/null)
case "$*" in
  "reload ssh.service") [ "$mode" = reloadfail ] && exit 1; kill -HUP "$p" ;;
  "show -p MainPID --value ssh.service") case $mode in unknown) echo "Failed to connect to bus" >&2; exit 1;; inactive) echo 0;; *) echo "$p";; esac ;;
  "is-active ssh.service") case $mode in inactive) echo inactive; exit 3;; unknown) echo "Failed to connect to bus" >&2; exit 1;; esac
                           kill -0 "$p" 2>/dev/null && echo active || { echo inactive; exit 3; } ;;
  *) echo "대역: $*" ;;
esac
EOF
chmod +x /usr/local/bin/systemctl
# sshd -T 대역 감싸기(N11·N12 에서만 켬): /tmp/tamper 면 nouser 조건 직전에 00 파일을 제3 내용으로 바꾸고, /tmp/failT 면 nouser 조건 sshd -T 를 1 로
mv /usr/sbin/sshd /usr/sbin/sshd.real
cat > /usr/sbin/sshd <<'EOF'
#!/bin/bash
case "$*" in *nosuchuser-catchk*)
  if [ -f /tmp/tamper ]; then rm -f /tmp/tamper; printf '# third-party\nPasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/00-cat-hardening.conf; fi
  if [ -f /tmp/failT ]; then rm -f /tmp/failT; echo "대역: -T 실패" >&2; exit 1; fi ;;
esac
exec -a /usr/sbin/sshd /usr/sbin/sshd.real "$@"
EOF
chmod 755 /usr/sbin/sshd
useradd -m -s /bin/bash manicstr
install -d -m 700 -o manicstr -g manicstr /home/manicstr/.ssh
ssh-keygen -q -t ed25519 -N '' -f /root/k_dev; ssh-keygen -q -t ed25519 -N '' -f /root/k_new; ssh-keygen -q -t ed25519 -N '' -f /root/k_late
install -m 600 -o manicstr -g manicstr /root/k_dev.pub /home/manicstr/.ssh/authorized_keys
printf 'PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/50-cloud-init.conf; chmod 600 /etc/ssh/sshd_config.d/50-cloud-init.conf
mkdir -p /run/sshd; ssh-keygen -A >/dev/null; /usr/sbin/sshd; sleep 1
mkdir -p /root/.ssh; ssh-keyscan -p 22 127.0.0.1 2>/dev/null > /root/.ssh/known_hosts
mkdir -p /w && cp -r /b/change /w/ && cd /w
AK=/home/manicstr/.ssh/authorized_keys; DST=/etc/ssh/sshd_config.d/00-cat-hardening.conf; MAIN=/etc/ssh/sshd_config
cp -a $AK /root/ak.base; cp -a $MAIN /root/main.base
pass=0; fail=0
ok()  { if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS $3 (rc=$1)"; else fail=$((fail+1)); echo "FAIL $3 (rc=$1, 기대 $2)"; fi; }
yes_() { if eval "$1"; then pass=$((pass+1)); echo "PASS $2"; else fail=$((fail+1)); echo "FAIL $2"; fi; }
has() { if grep -qE "$2" "$1"; then pass=$((pass+1)); echo "PASS $3"; else fail=$((fail+1)); echo "FAIL $3 — '$2' 없음"; fi; }
nrel() { grep -c '^reload ssh.service$' /tmp/svc.log 2>/dev/null || true; }
sha() { sha256sum < "$1" | cut -d' ' -f1; }
dl() { (cd /etc/ssh/sshd_config.d && for f in *; do [ -f "$f" ] && printf '%s %s\n' "$f" "$(sha256sum < "$f" | cut -d' ' -f1)"; done) | sort | sha256sum | cut -d' ' -f1; }
reset() {   # 시나리오 사이 기준 상태로(시험 하네스만 하는 일)
  echo active > /tmp/svcmode; rm -f /tmp/tamper /tmp/failT "$DST" /etc/ssh/sshd_config.d/10-third.conf
  cp -p /root/ak.base $AK; cp -p /root/main.base $MAIN; kill -HUP "$(cat /run/sshd.pid)"; sleep 1
}
DEV=127.0.0.1 LA=127.0.0.1 LP=22 EXT=203.0.113.1
NEWFP=$(ssh-keygen -lf /root/k_new.pub | awk '{print $2}')
E() { env STAMP=$1 EXP_MAIN_SHA=$(sha $MAIN) EXP_DLIST_SHA=$(dl) EXP_AK_SHA=$(sha $AK) DEV_ADDR=$DEV LADDR=$LA LPORT=$LP EXT_ADDR=$EXT \
          NEW_PUB=/root/k_new.pub EXP_NEW_FP=$NEWFP bash change/ssh_change.sh "${@:2}"; }
# E 는 부르는 순간의 디스크 값을 EXP 로 넘긴다 — 운영에서는 운영자 읽기 값으로 고정한다. 전제 검사가 아니라 바뀐 경로를 보기 위함.
# 단, backup 뒤 단계는 EXP 와 무관하게 $BK/state 기록과 대조한다.
EB() { local s=$1; shift; env STAMP=$s EXP_MAIN_SHA=$(cat /root/exp.main) EXP_DLIST_SHA=$(cat /root/exp.dl) EXP_AK_SHA=$(cat /root/exp.ak) \
          DEV_ADDR=$DEV LADDR=$LA LPORT=$LP EXT_ADDR=$EXT NEW_PUB=/root/k_new.pub EXP_NEW_FP=$NEWFP bash change/ssh_change.sh "$@"; }
fix_exp() { sha $MAIN > /root/exp.main; dl > /root/exp.dl; sha $AK > /root/exp.ak; }
set -o pipefail; mkdir -p /out/steps; L() { tee "/out/steps/$1.txt"; }
: > /tmp/svc.log; echo active > /tmp/svcmode; fix_exp

echo "== N1 정상 한 벌(바뀐 기록·상태 확인 경로)"
S=20261118-0101
EB $S precheck 2>&1 | L n1-precheck; ok $? 0 "N1a precheck"
EB $S backup   2>&1 | L n1-backup;   ok $? 0 "N1b backup(목록 rc 0 0 0·state 기록)"
has /out/steps/n1-backup.txt 'find/sort/sha256sum rc=0 0 0' "N1b' 백업 목록 종료값 각각 0"
EB $S addkey   2>&1 | L n1-addkey;   ok $? 0 "N1c addkey(직후 해시·권한·지문 기록)"
has /out/steps/n1-addkey.txt '기록 ak.added=[0-9a-f]{64}' "N1c' ak.added 기록"
EB $S stage    2>&1 | L n1-stage;    ok $? 0 "N1d stage(직후 해시·목록 기록)"
r0=$(nrel); EB $S reload 2>&1 | L n1-reload; ok $? 0 "N1e reload active"
has /out/steps/n1-reload.txt "is-active='active'\(rc=0\)" "N1e' active·MainPID 확인 줄"
yes_ "[ $(nrel) = $((r0+1)) ]" "N1e'' reload 1회"
bash change/verify_local.sh after manicstr@127.0.0.1 22 /root/k_dev /root/k_new > /out/steps/n1-after.txt 2>&1; ok $? 0 "N1f 두 키 접속·publickey 만"
EB $S rollback 2>&1 | L n1-rollback; ok $? 0 "N1g rollback(이번 stage 산출물 확인 뒤 지움·active)"
yes_ "[ ! -e $DST ] && [ \$(dl) = \$(cat /root/exp.dl) ]" "N1g' 00 없음·목록 백업과 같음"
EB $S rollback-key 2>&1 | L n1-rbkey; ok $? 0 "N1h rollback-key(addkey 직후 해시 일치 → 복구)"
yes_ "[ \$(sha $AK) = \$(cat /root/exp.ak) ] && [ \"\$(stat -c '%a %U' $AK)\" = '600 manicstr' ]" "N1h' 바이트·권한 복구"
EB $S rollback-key 2>&1 | L n1-rbkey2; ok $? 0 "N1i 이미 백업과 같음 → 쓰지 않고 0"
has /out/steps/n1-rbkey2.txt '이미 백업과 같음' "N1i' 쓰지 않음 표시"

echo "== N2 00 파일이 제3 내용으로 바뀜 → 지우지·reload 하지 않고 7"
reset; fix_exp; S=20261118-0102
EB $S backup >/dev/null 2>&1 && EB $S stage > /dev/null 2>&1 && EB $S reload > /dev/null 2>&1; ok $? 0 "N2 준비 backup·stage·reload"
printf '# third-party\nPasswordAuthentication no\n' > $DST; t=$(sha $DST); r0=$(nrel)
EB $S rollback 2>&1 | L n2-rollback; ok $? 7 "N2a rollback → 7"
yes_ "[ -e $DST ] && [ \$(sha $DST) = $t ] && [ \$(nrel) = $r0 ]" "N2b 제3 파일 그대로·reload 0회"

echo "== N3 sshd_config 가 백업과 다름 → 주의만 내고 진행하지 않고 7"
reset; fix_exp; S=20261118-0103
EB $S backup >/dev/null 2>&1 && EB $S stage > /dev/null 2>&1; ok $? 0 "N3 준비 backup·stage"
printf '# 이 묶음 밖 변경\n' >> $MAIN; r0=$(nrel)
EB $S rollback 2>&1 | L n3-rollback; ok $? 7 "N3a rollback → 7"
yes_ "[ -e $DST ] && [ \$(sha $DST) = 0a2933a644b044700eabce5b8cfadac98c963a9383147e84a66cc8caf92e367b ] && [ \$(nrel) = $r0 ]" "N3b 00 그대로·reload 0회"
EB $S reload 2>&1 | L n3-reload; ok $? 7 "N3c stage 뒤 주 설정 바뀜 → reload 도 7"

echo "== N4 sshd_config.d 에 제3 파일 추가 → 7"
reset; fix_exp; S=20261118-0104
EB $S backup >/dev/null 2>&1 && EB $S stage > /dev/null 2>&1; ok $? 0 "N4 준비 backup·stage"
printf '# third\n' > /etc/ssh/sshd_config.d/10-third.conf; r0=$(nrel)
EB $S rollback 2>&1 | L n4-rollback; ok $? 7 "N4a rollback → 7"
yes_ "[ -e $DST ] && [ -e /etc/ssh/sshd_config.d/10-third.conf ] && [ \$(nrel) = $r0 ]" "N4b 두 파일 그대로·reload 0회"

echo "== N5 addkey 뒤 후속 키 추가 → rollback-key 덮어쓰지 않고 7"
reset; fix_exp; S=20261118-0105
EB $S backup >/dev/null 2>&1 && EB $S addkey > /dev/null 2>&1; ok $? 0 "N5 준비 backup·addkey"
cat /root/k_late.pub >> $AK; t=$(sha $AK)
EB $S rollback-key 2>&1 | L n5-rbkey; ok $? 7 "N5a rollback-key → 7"
yes_ "[ \$(sha $AK) = $t ] && grep -qF \"\$(cut -d' ' -f2 /root/k_late.pub)\" $AK" "N5b 후속 키 보존"

echo "== N6 addkey 기록 없이 키 파일이 바뀜(제3) → 7"
reset; fix_exp; S=20261118-0106
EB $S backup >/dev/null 2>&1; ok $? 0 "N6 준비 backup"
cat /root/k_late.pub >> $AK; t=$(sha $AK)
EB $S rollback-key 2>&1 | L n6-rbkey; ok $? 7 "N6a rollback-key → 7"
yes_ "[ \$(sha $AK) = $t ]" "N6b 제3 내용 보존"

echo "== N7·N8·N10 reload 상태 관문"
reset; fix_exp; S=20261118-0107
EB $S backup >/dev/null 2>&1 && EB $S stage > /dev/null 2>&1; ok $? 0 "N7 준비 backup·stage"
echo inactive > /tmp/svcmode; EB $S reload 2>&1 | L n7-reload; ok $? 6 "N7 reload rc0·inactive → 6(성공 아님)"
echo unknown  > /tmp/svcmode; EB $S reload 2>&1 | L n8-reload; ok $? 9 "N8 reload rc0·상태 조회 불명 → 9"
echo reloadfail > /tmp/svcmode; EB $S reload 2>&1 | L n10-reload; ok $? 4 "N10 reload 명령 실패 → 4"
echo "== N9 rollback 뒤 inactive → 6"
echo inactive > /tmp/svcmode; EB $S rollback 2>&1 | L n9-rollback; ok $? 6 "N9 rollback reload 뒤 inactive → 6"
yes_ "[ ! -e $DST ]" "N9b 파일은 이번 산출물이라 지워짐(상태만 실패로 보고)"

echo "== N11 stage 검사 중 00 파일이 제3 내용으로 바뀜 → 지우지 않고 7"
reset; fix_exp; S=20261118-0111
EB $S backup >/dev/null 2>&1; ok $? 0 "N11 준비 backup"
touch /tmp/tamper; r0=$(nrel)
EB $S stage 2>&1 | L n11-stage; ok $? 7 "N11a stage → 7"
yes_ "grep -q third-party $DST && [ \$(nrel) = $r0 ]" "N11b 제3 파일 그대로·reload 0회"
echo "== N12 stage 검사 실패, 놓은 바이트 그대로 → 그 파일만 지우고 4"
reset; fix_exp; S=20261118-0112
EB $S backup >/dev/null 2>&1; ok $? 0 "N12 준비 backup"
touch /tmp/failT; EB $S stage 2>&1 | L n12-stage; ok $? 4 "N12a stage → 4"
yes_ "[ ! -e $DST ] && [ \$(dl) = \$(cat /root/exp.dl) ]" "N12b 놓은 파일만 지움·목록 백업과 같음"

echo "== N13 백업 목록 검증"
reset; fix_exp; S=20261118-0113
EB $S backup >/dev/null 2>&1; ok $? 0 "N13 준비 backup"
B=/root/sshd-bak-$S; cp $B/SHA256SUMS /root/sums.keep
grep -v '\./authorized_keys$' /root/sums.keep > $B/SHA256SUMS
EB $S stage 2>&1 | L n13a-stage; ok $? 7 "N13a 목록에서 필수 항목 빠짐 → 7"
cp /root/sums.keep $B/SHA256SUMS; printf 'x\n' > $B/unlisted
EB $S stage 2>&1 | L n13b-stage; ok $? 7 "N13b 목록 밖 파일 → 7"
rm -f $B/unlisted; : > $B/SHA256SUMS
EB $S stage 2>&1 | L n13c-stage; ok $? 7 "N13c 빈 목록 → 7"
yes_ "[ ! -e $DST ]" "N13d 세 경우 모두 설정 파일 안 놓음"

echo "== N14 stage 기록 없이 같은 바이트의 00 파일(수동 복사) → rollback 7"
reset; fix_exp; S=20261118-0114
EB $S backup >/dev/null 2>&1; ok $? 0 "N14 준비 backup"
install -m 0644 change/00-cat-hardening.conf $DST; r0=$(nrel)
EB $S rollback 2>&1 | L n14-rollback; ok $? 7 "N14a rollback → 7"
yes_ "[ -e $DST ] && [ \$(nrel) = $r0 ]" "N14b 파일 그대로·reload 0회"

reset
echo "== 합계 PASS=$pass FAIL=$fail"
[ $fail -eq 0 ]
