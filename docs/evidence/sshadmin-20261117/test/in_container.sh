#!/bin/bash
# 일회용 ubuntu:22.04 컨테이너 안(운영과 같은 openssh-server 8.9p1 계열)에서 admin_read.sh·ssh_change.sh·verify_local.sh 를 시험한다.
# 운영 서버 접속 없음. 묶음은 /b(읽기 전용)로 들어온다. systemctl 은 컨테이너에 없어 sshd 에 HUP 를 보내는 대역으로 바꾼다.
set -u
export DEBIAN_FRONTEND=noninteractive LC_ALL=C
apt-get update -qq >/dev/null && apt-get install -y -qq openssh-server openssh-client iproute2 >/dev/null || { echo "설치 실패"; exit 99; }
echo "openssh-server $(dpkg-query -W -f='${Version}' openssh-server)"
cat > /usr/local/bin/systemctl <<'EOF'
#!/bin/bash
# 시험 대역: reload ssh.service → sshd 에 HUP, MainPID/is-active 는 pid 파일 기준
p=$(cat /run/sshd.pid 2>/dev/null)
case "$*" in
  "reload ssh.service") kill -HUP "$p" ;;
  "show -p MainPID --value ssh.service") echo "$p" ;;
  "is-active ssh.service") kill -0 "$p" 2>/dev/null && echo active || { echo inactive; exit 3; } ;;
  *) echo "대역: $*" ;;
esac
EOF
chmod +x /usr/local/bin/systemctl
useradd -m -s /bin/bash manicstr; echo 'manicstr:testpass-not-secret' | chpasswd
install -d -m 700 -o manicstr -g manicstr /home/manicstr/.ssh
ssh-keygen -q -t ed25519 -N '' -f /root/k_dev; ssh-keygen -q -t ed25519 -N '' -f /root/k_new
install -m 600 -o manicstr -g manicstr /root/k_dev.pub /home/manicstr/.ssh/authorized_keys
printf 'PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/50-cloud-init.conf; chmod 600 /etc/ssh/sshd_config.d/50-cloud-init.conf
mkdir -p /run/sshd; ssh-keygen -A >/dev/null; /usr/sbin/sshd; sleep 1
ssh-keyscan -p 22 127.0.0.1 2>/dev/null > /root/.ssh_known
mkdir -p /root/.ssh && cp /root/.ssh_known /root/.ssh/known_hosts
mkdir -p /w && cp -r /b/admin_read.sh /b/change /w/ && cd /w
pass=0; fail=0
ok() { if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS $3 (rc=$1)"; else fail=$((fail+1)); echo "FAIL $3 (rc=$1, 기대 $2)"; fi; }
has() { if grep -qE "$2" "$1"; then pass=$((pass+1)); echo "PASS $3"; else fail=$((fail+1)); echo "FAIL $3 — '$2' 없음"; fi; }
DEV=127.0.0.1 LA=127.0.0.1 LP=22 EXT=203.0.113.1

echo "== R 운영자 읽기"
bash admin_read.sh > r0.txt 2>&1; ok $? 2 "R1 인자 없음 → 2"
bash admin_read.sh manicstr "" $LA $LP $EXT > r1.txt 2>&1; ok $? 2 "R2 빈 출발주소 → 2"
bash admin_read.sh manicstr "1.2.3.4,user=root" $LA $LP $EXT > r2.txt 2>&1; ok $? 2 "R3 조건 끼워넣기 → 2"
bash admin_read.sh manicstr $DEV $LA $LP $DEV > r3.txt 2>&1; ok $? 2 "R4 외부=출발 → 2"
su manicstr -c "bash admin_read.sh manicstr $DEV $LA $LP $EXT" > r4.txt 2>&1; ok $? 3 "R5 root 아님 → 3"
bash admin_read.sh manicstr $DEV $LA $LP $EXT > read.txt 2>&1; ok $? 0 "R6 정상 읽기"
has read.txt '^rc\[sshd_T\.dev\]=0' "R6a sshd -T 종료값 줄"
has read.txt '^  passwordauthentication yes' "R6b 50 조각의 yes 가 유효 값으로 보임"
has read.txt '전체 IPv4 남은 줄=0 · 키 본문 줄=0' "R6c 가림 확인 0"
has read.txt '00-cat-hardening.conf 없음' "R6d 변경 전제 표시"
cp read.txt /out/admin-read.container.txt
MS=$(sha256sum < /etc/ssh/sshd_config | cut -d' ' -f1)
DL=$(grep -oE '목록 해시\(이름·sha256 정렬\)=[0-9a-f]{64}' read.txt | cut -d= -f2)
AKS=$(grep -oE '^-- manicstr authorized_keys .* sha256=[0-9a-f]{64}' read.txt | grep -oE '[0-9a-f]{64}$')
echo "읽기에서 얻은 값: main=${MS:0:12} dlist=${DL:0:12} ak=${AKS:0:12}"
E() { env STAMP=$1 EXP_MAIN_SHA=$MS EXP_DLIST_SHA=$DL EXP_AK_SHA=$AKS DEV_ADDR=$DEV LADDR=$LA LPORT=$LP EXT_ADDR=$EXT "${@:2}"; }

echo "== V 적용 전 기준(개발 PC 쪽 스크립트를 컨테이너 안에서)"
bash change/verify_local.sh before manicstr@127.0.0.1 22 /root/k_dev > v0.txt 2>&1; ok $? 0 "V1 before"; has v0.txt 'publickey,password' "V1a 적용 전 password 알림"

echo "== C 변경 묶음"
E 20261118-1400 bash change/ssh_change.sh nosuch > c0.txt 2>&1; ok $? 2 "C0 단계 이름 이상 → 2"
E 20261118-1400 EXP_MAIN_SHA=0000 bash change/ssh_change.sh precheck > c1.txt 2>&1; ok $? 7 "C1 sshd_config 해시 다름 → 7"
E 20261118-1400 bash change/ssh_change.sh stage > c2.txt 2>&1; ok $? 7 "C2 백업 없이 stage → 7"
E 20261118-1400 bash change/ssh_change.sh precheck > c3.txt 2>&1; ok $? 0 "C3 precheck"
E 20261118-1400 bash change/ssh_change.sh backup > c4.txt 2>&1; ok $? 0 "C4 backup"
E 20261118-1400 bash change/ssh_change.sh backup > c5.txt 2>&1; ok $? 5 "C5 백업 다시 → 5(백업 폴더 있음, 덮어쓰지 않음)"
E 20261118-1400 NEW_PUB=/root/k_new.pub EXP_NEW_FP=SHA256:wrong bash change/ssh_change.sh addkey > c6.txt 2>&1; ok $? 7 "C6 새 키 지문 다름 → 7"
FP=$(ssh-keygen -lf /root/k_new.pub | awk '{print $2}')
E 20261118-1400 NEW_PUB=/root/k_new.pub EXP_NEW_FP=$FP bash change/ssh_change.sh addkey > c7.txt 2>&1; ok $? 0 "C7 addkey"
bash change/verify_local.sh newkey manicstr@127.0.0.1 22 /root/k_new > v1.txt 2>&1; ok $? 0 "V2 새 키 단독 접속"
E 20261118-1400 bash change/ssh_change.sh stage > c8.txt 2>&1; ok $? 0 "C8 stage(검사 통과)"
has c8.txt '조건별 기대값 모두 일치' "C8a 5조건 기대값"
E 20261118-1400 bash change/ssh_change.sh stage > c9.txt 2>&1; ok $? 7 "C9 stage 다시 → 7(이미 있음)"
p0=$(cat /run/sshd.pid)
E 20261118-1400 bash change/ssh_change.sh reload > c10.txt 2>&1; ok $? 0 "C10 reload"; sleep 1
bash change/verify_local.sh after manicstr@127.0.0.1 22 /root/k_dev /root/k_new > v2.txt 2>&1; ok $? 0 "V3 after(두 키 성공·publickey 만)"
has v2.txt 'Permission denied \(publickey\)$' "V3a password 사라짐"
echo "sshd pid $p0 → $(cat /run/sshd.pid)"
E 20261118-1400 bash change/ssh_change.sh rollback > c11.txt 2>&1; ok $? 0 "C11 rollback"; sleep 1
bash change/verify_local.sh before manicstr@127.0.0.1 22 /root/k_dev > v3.txt 2>&1; ok $? 0 "V4 복구 뒤 접속"; has v3.txt 'publickey,password' "V4a 원래 방식으로 돌아옴"
E 20261118-1400 bash change/ssh_change.sh rollback-key > c12.txt 2>&1; ok $? 0 "C12 rollback-key"
bash change/verify_local.sh newkey manicstr@127.0.0.1 22 /root/k_new > v4.txt 2>&1; ok $? 1 "V5 키 복구 뒤 새 키 거절"
cmp -s /home/manicstr/.ssh/authorized_keys /root/sshd-bak-20261118-1400/authorized_keys; ok $? 0 "C12a authorized_keys 바이트 복구"

echo "== M Match 블록 음성(외부 출발에 password yes)"
printf 'PasswordAuthentication yes\nMatch Address 203.0.113.0/24\n  PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/50-cloud-init.conf
bash admin_read.sh manicstr $DEV $LA $LP $EXT > readM.txt 2>&1; ok $? 0 "M1 읽기"
has readM.txt '^rc\[diff\.dev_vs_ext\]=0' "M1a(참고) 적용 전엔 C1·C2 키 줄이 같음"
DL=$(grep -oE '목록 해시\(이름·sha256 정렬\)=[0-9a-f]{64}' readM.txt | cut -d= -f2)
E 20261118-1500 bash change/ssh_change.sh backup > m2.txt 2>&1; ok $? 0 "M2 새 백업"
E 20261118-1500 bash change/ssh_change.sh stage > m3.txt 2>&1; ok $? 4 "M3 stage 검사 실패 → 4"
has m3.txt '\[dev_ext\] 기대 .passwordauthentication no. 아님' "M3a 외부 출발 조건에서 잡음"
[ ! -e /etc/ssh/sshd_config.d/00-cat-hardening.conf ]; ok $? 0 "M3b 놓은 파일 지워짐"
has m3.txt 'reload 안 함' "M3c reload 없음"
cp /w/*.txt /out/ 2>/dev/null
echo "== 합계 PASS=$pass FAIL=$fail"
[ $fail -eq 0 ]
