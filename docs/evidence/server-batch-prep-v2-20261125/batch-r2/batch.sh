#!/bin/bash
# D81B-EXP1 U0→U1 서버 유한 배치 — 실제 실행 파일(총괄 v1.250 §2, v1.251 batch-r2: 수집 실패 전달 보완). 이 파일의 존재·전송은 실행 승인이 아니다. 등록·시작은 총괄의 원문/해시 검토 뒤.
# 시작: launch.sh 만 이 파일을 setsid nohup 으로 띄운다. 인자 = 작업 ID 하나. 테스트 시각·경로를 바꾸는 환경변수/옵션은 없다(합성 검증은 selftest.sh 가 lib.sh 를 따로 source).
# 하는 일: 잠금·1회 확인 → 배치/블록 해시 → 11-27 10:14 KST N03 원문 보존·판정 → 17:36 U0(창 17:35~19:30 포함) → 통과 시 30분(1740초 여유) 안·:14~:24 제외 U1 → 끝.
# 하지 않는 일: U2·재기동·되돌림·재시도·다음 버전 이동·보고 생성/재전송·cron/systemd 변경·sudo. 블록은 고정 출처 452ac384 의 F·G·U변수·U0/U1 원문을 그대로 붙여 bash -c 로 돌린다(run.sh 의 원격 문자열과 같다, ssh 없음).
set -u
BASE=$HOME/work-batches/d81b-20261127-exp1
JOB=${1:?작업 ID}
[[ $JOB =~ ^d81b-u0u1-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || { echo "🔴 작업 ID 형식 아님"; exit 2; }
LOCK=$BASE/batch.lock; RUNS=$BASE/runs; R=$RUNS/$JOB; BDIR=$BASE/batch-r2; B=$BASE/src/blocks-d81
M=$HOME/maint-d81b-20261127-exp1; DCK=$HOME/daily-check; LDG=$HOME/sid203-ledger; PY=python3
TAG=20261127-1000; LD=20261127
T_N03=1795742040    # 2026-11-27 10:14 KST
T_START=1795768500  # 2026-11-27 17:35 KST 창 시작
T_U0=1795768560     # 2026-11-27 17:36 KST U0 목표
END_EXCL=1795775460 # 2026-11-27 19:31:00 KST 미만(창 끝 19:30 분 포함)
U1AGE=1740          # U1 블록 자체 한도 1800초보다 60초 앞에서 멈춘다
SRCEXP=452ac38430dff30300048026e9478e555e015b8c
EXP_LIST='26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24 F
4d7ff02a94194fc17e11ed062b4c0fdb80a395aa282c941e7b624be95851c822 G
12776871f93dd37c89446413c48deace863b8bb4232ca64ad6cb9dd019972477 U변수
8ed75a9a4a23ef0120881f0c72d3cc8206369dc5a62951c307ef20fdd8c65a4f U0
beec0138758be9d707e13548bf4315ed402cbc93cce4a2ac22c2856355a2c198 U1
a272ab5862a97539233f9425d4efda2da077e26fe6828a0211cd99c2c5827c8b U1되돌림
caac178a9b7cae104584b91107c19ee5594eb0437d01b74e16935fe29803644f U2
cc414408aa11133279ad491d6815ac0f9ed521ce0ea87cc46e7a4ec4b204e573 U3
979c5d85addf7f58c92cfe31aa1759438db790995d642fb530600e0676d3b740 U4
65df00d5d52430077357d9207b188de5e063f96374c33fe0c32591dca95bfe83 U되돌림
c748a791e87e31ad557977ce3b8c3ab3c0a82436aa4d68ffee93c07bd3d9fd31 U뒤관측'
now_epoch() { date +%s; }
sleep_s() { sleep "$1"; }
exec_block() { bash -c "$1"; }
verify_self() { ( cd "$BDIR" && sha256sum --quiet -c BATCH.sha256 ) > /dev/null 2>&1; }
. "$BDIR/pins.sh" || { echo "🔴 pins.sh 읽기 실패"; exit 2; }   # collect_pins — 필수 명령 종료값 직접 확인(v1.251 §2)
. "$BDIR/lib.sh" || { echo "🔴 lib.sh 읽기 실패"; exit 2; }
echo "[배치 시작] $(date -u '+%F %T')Z 작업=$JOB pid=$$"
batch_main; FR=$?
echo "[배치 끝] $(date -u '+%F %T')Z 종료=$FR"
exit $FR
