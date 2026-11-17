# SSH 복구/상태 관문 v2 + 관리자 읽기 보충 (총괄 v1.181 §2·§3)

기준: 2026-11-18 KST. **운영 서버에 한 일 없음**(SSH 설정·키·sudo·reload 0). 시험은 로컬 Docker 일회용 `ubuntu:22.04`(이미지 `0a9887931b0e`, openssh-server **1:8.9p1-3ubuntu0.18**) 안의 실제 경로·실제 sshd에서 했다. systemctl만 대역이다. v1 원문 [sshadmin-20261117/change/ssh_change.sh](../sshadmin-20261117/change/ssh_change.sh)(`cbcf114d…`, 커밋 bc5a39d9)과 v1 40/40 시험은 그대로 두었고 반복하지 않았다.

## 1. 바뀐 것 — [change/ssh_change.sh](change/ssh_change.sh) v2 `4a16f658…`

`00-cat-hardening.conf`(`0a2933a6…`)·`verify_local.sh`(`315c7f9a…`)는 v1과 같은 바이트다. 새 종료값은 **6 서비스 비활성/실패**와 **9 상태 조회 불명**이다. 7은 전제 불일치·제3·조회 불명이며 이때 변경 명령은 0이다.

| 총괄 관측(v1) | v2 계약 |
|---|---|
| 00 설정이 제3 내용 → 삭제·reload·0 | 00 파일 해시가 고정값이고 **이번 stage 기록(`$BK/state/staged.dst`)과 같을 때만** 지운다. 제3 내용·기록 없음·조회 불명이면 7(지우기·reload 0) |
| 주 설정이 백업과 다름 → 주의 뒤 진행·0 | `sshd_config`가 백업과 다르거나 00 파일을 뺀 `sshd_config.d` 목록이 백업과 다르면 7(변경 0) |
| 후속 키 추가 뒤 rollback-key → 백업으로 덮어씀·0 | 현재가 백업과 같으면 쓰지 않고 0. **addkey 직후 기록 해시와 같을 때만** 복구한다. 후속 키·제3 내용·기록 없음이면 7 |
| reload 0·inactive → 0 | reload 명령 종료값과 별도로 `is-active=active`(rc0)·MainPID 숫자를 확인한다. 비활성 6, 조회 불명 9 |
| stage 실패 때 지우는 파일 | 놓은 바이트(고정 해시)일 때만 지운다. 아니면 7. 지운 뒤 목록이 백업과 같은지도 확인한다 |
| 백업 목록 | find/sort/sha256sum 종료값을 각각 기록한다. 빈 목록·필수 항목 누락·목록 줄 수≠파일 수는 7, 생성 실패는 8 |

backup이 `$BK/state/`를 만들고 백업 시점·addkey 직후(해시·권한·지문 목록)·stage 직후(00 해시·주 설정·목록 해시) 값을 임시 파일→이름 바꾸기→다시 읽기로 기록한다. 기록 실패는 8이다. 읽기 실패는 빈 값이 아니라 `UNKNOWN`으로 다룬다. 목록 해시 형식은 v1·admin_read와 같아 운영자 읽기 값을 그대로 쓴다.

## 2. 시험 — [test/in_container_v2.sh](test/in_container_v2.sh): **52/52**

[console](test/run1/console.txt) · 단계 출력 [test/run1/steps/](test/run1/steps/). 정상 1벌과 바뀐 경로의 음성 사례만 넣었다.

| 묶음 | 확인 |
|---|---|
| N1 정상 | precheck·backup(목록 rc `0 0 0`)·addkey(기록)·stage·reload(active 확인 줄·reload 1회)·verify after(두 키·publickey만)·rollback·rollback-key(바이트·600 manicstr 복구)·다시 rollback-key는 쓰지 않고 0 |
| N2~N4 | 00 제3 내용 / 주 설정 변경(reload도 7) / `10-third.conf` 추가 → 모두 **7, 파일 그대로, reload 0회** |
| N5·N6 | addkey 뒤 후속 키 / 기록 없이 제3 키 → rollback-key **7, 키 파일 보존** |
| N7~N10 | reload 뒤 inactive **6** · 상태 조회 불명 **9** · rollback 뒤 inactive **6**(이번 산출물은 지워짐, 상태만 실패) · reload 명령 실패 **4** |
| N11·N12 | stage 검사 도중 00이 제3 내용으로 바뀜 → **7, 지우지 않음** / 놓은 바이트 그대로 검사 실패 → 그 파일만 지우고 4 |
| N13·N14 | 백업 목록 필수 항목 누락·목록 밖 파일·빈 목록 → 7 / stage 기록 없이 같은 바이트 00(수동 복사) → rollback 7 |

**대조**: 같은 시험을 v1 스크립트에 대면 [29/52](test/v1-same-tests.txt)다. 총괄 표의 결함 4행(N2 제3 설정·N3 주 설정·N5 후속 키·N7 inactive)을 포함해 23개가 실패한다. 시험이 바뀐 경로를 실제로 구분한다는 뜻이다.

한계: 컨테이너에는 systemd가 없어 active/inactive/조회 불명은 대역 값이다. 운영 서버의 실제 reload·ufw·공유기·콘솔은 검증하지 않았다. 대역 sshd 래퍼는 N11·N12에서만 nouser 조건 직전에 동작한다.

## 3. 관리자 읽기 보충 — [admin_read_add.sh](admin_read_add.sh) `0fd06eb0…`

admin_read.sh(`0b8f3c32…`)는 그대로 쓴다. 같은 관리자 세션에서 이어 부르는 읽기 전용 보충이다. `sshd -T` 종료값, **usedns 값**, 설정 파일의 `UseDNS`·`Match … Host`·`Include` 줄, 서비스 `ExecStart`·sshd 프로세스 인자를 `rc[...]`로 각각 남긴다. 컨테이너 확인 결과는 [root 아님 3, root 0](test/admin-read-add.container.txt)이며 `usedns no`, `Include` 1줄을 확인했다. systemd가 없어 ExecStart는 rc1로만 확인했다.

판정 규칙(운영자 출력에 적용): `rc[sshd_T.plain]=0`, `usedns no` 한 줄, Match Host 0, 명령줄 `-f/-o` 없음일 때만 admin_read 5조건을 실제 접속 정책으로 쓴다. 아니면 hostname 조건까지 다시 고정한다. admin_read의 전체 rc0은 개별 성공이 아니다. `rc[sshd_t]`·각 `rc[sshd_T.*]`·필수 키 grep·목록 해시·방화벽 rc를 따로 보고, 필수 읽기 실패는 **미확인**으로 적는다. 마지막 줄의 가림 확인(남은 IPv4 0·키 본문 0)을 본 뒤에만 가져온다.

```bash
# 서버(운영자 본인 관리자 권한) — admin_read.sh 뒤에 이어서. 이미 모은 7일 로그는 다시 모으지 않는다
sudo bash ~/admin_read_add.sh > ~/admin-read-add-out.txt; echo "rc=$?"
```

운영자 답 4개(공유기 전달·비밀번호/MFA 의존·콘솔 복구·승인 계정/새 키)는 [432차 §3](../sshadmin-20261117/README.md)과 같다. 관리자 실행이 불가능하면 그 항목만 대기한다.

## 4. 적용 경계

v2 묶음 적용은 구체 묶음 검토와 운영자 확인 1회 뒤에만 한다. 지금은 backup·addkey·stage·reload·rollback·rollback-key를 실행하지 않는다. 관리자 읽기 결과에서 Match·Allow/Deny·MFA·다른 승인 계정이 나오면 conf를 다시 써서 다시 제출한다.
