# SSH 관리자 확인 준비 — 정정·방식 관측·운영자 읽기 스크립트·변경/복구 묶음 (총괄 v1.180 §4-1~4-3)

기준: 2026-11-17 KST. 운영 서버에 한 일은 아래 §2 의 **접속 시도 1회**(비밀값 없음)뿐이다. 설정·키·방화벽·공유기 변경, sudo 사용, 새 키 추가는 하지 않았다. sudo·DB 비밀번호는 요청하지 않았다.

## 1. 정정 (431차 [sshsec-20261117](../sshsec-20261117/README.md) 표현, 원문·체크섬은 그대로 둠)

| 앞 표현 | 정정 |
|---|---|
| "7일 Accepted 244건 모두 `publickey for manicstr`" 를 개발 키 귀속 근거처럼 씀 | 244건은 **인증 종류(publickey)와 사용자(manicstr)만** 집계했다. 키 지문·출발지는 집계하지 않았으므로 모두 개발 키였다고 말할 수 없다. 지문·같은 IP 까지 확인한 것은 07:50~08:10 의 21건뿐. 침입 없음도 입증하지 않는다. 이 정정을 위해 7일 로그를 다시 받지 않았다 |
| `50-cloud-init.conf` 의 PasswordAuthentication | root 전용 파일 안의 값은 **미확인**(grep rc2). 아래 §2 는 개발 계정 한 조건에서 서버가 알린 방식이며 파일 값·전체 유효 정책의 확인이 아니다 |
| "지금 공격 중" | **10:40:59~10:42:07 읽기 시각 기준**으로 최근 5분 실패/Invalid user 49줄(인증 시도 49회로 단정하지 않음)·출발지 22. 그 뒤는 미관측 |

통신 회복 구분(캔들·텔레그램 회복 확인, CryptoPanic reset·Claude 낭독 회복 미확인, PAPER 기회비용 미확인)은 앞 정정 표 그대로 유지한다.

## 2. 새 관측 — 개발 계정 조건에서 서버가 알린 인증 방식

| 항목 | 값 |
|---|---|
| 스크립트 | [probe/probe_methods.sh](probe/probe_methods.sh). 공개키 끔·BatchMode·비밀번호 질문 0, 연결 재사용 없음. **비밀값을 보내지 않는다** |
| 시각·종료값 | 11-17 **11:22:55 KST**(02:22:55Z), ssh rc=255(인증 거절이 기대값) — [probe-out.txt](probe/probe-out.txt) |
| 서버 응답 | `Permission denied (publickey,password)` — [probe-stderr.txt](probe/probe-stderr.txt)(호스트 이름 가림) |

**판단**: 개발 계정(manicstr)·개발 PC 출발 조건에서는 비밀번호 인증이 **실제로 켜져 있다**(KbdInteractive 는 전역 no 라 `password` 표시는 PasswordAuthentication 경로). root·없는 사용자·다른 출발지 조건의 값, Match 블록 유무, 파일 안 실제 값은 여전히 미확인이며 아래 운영자 읽기가 필요하다. 서버 로그에는 이 시도가 manicstr 의 인증 전 종료 1건으로 남는다.

## 3. 운영자용 제한 읽기 — [admin_read.sh](admin_read.sh)

431차 README 의 `<주소>`·괄호 설명·`grep '<같은 키>'` 템플릿을 실행 가능한 스크립트로 바꿨다.

- 인자 5개: 개발계정·개발 PC 출발주소·서버 수신주소·수신포트·외부 예시주소(권장 `203.0.113.1`). 앞 셋은 개발 PC 에서 접속한 세션의 `echo "$SSH_CONNECTION"` 값.
- 빈 값·형식 이상(쉼표로 조건 끼워넣기 포함)·외부=출발이면 **아무것도 읽지 않고 2**, root 아니면 **3**.
- 읽는 것만: `sshd -t`, 5조건 `sshd -T -C`(개발/개발계정·외부/root·외부/root·개발/없는 사용자) — **종료값을 grep 과 분리해 먼저 기록**, C1↔C2 차이(출발지 Match 효과), 설정 파일별 관련 줄·소유/권한·sha256·`sshd_config.d` 목록 해시·00 파일 부재, 서비스 인자·`SSHD_OPTS`, PAM sshd 인증 줄·MFA 모듈 수, authorized_keys(줄 수·지문·해시만), ufw verbose·iptables INPUT/DOCKER-USER·docker 공개 포트.
- 출력은 IPv4 a.b.x.x·IPv6 앞 두 묶음으로 가리고 마지막 줄에 "전체 IPv4 남은 줄·키 본문 줄" 개수를 적는다. 서버에 남는 파일 없음(임시 폴더는 형식 확인 뒤 삭제).

운영자 절차(승인 범위: 읽기만):

```bash
# 개발 PC: 고정 파일을 서버 홈으로(운영자 확인 뒤) — 해시 확인
scp docs/evidence/sshadmin-20261117/admin_read.sh cat-prod:admin_read.sh
ssh cat-prod 'sha256sum admin_read.sh'      # SHA256SUMS 의 admin_read.sh 값과 같아야 함
# 서버(개발 PC 에서 들어간 세션): 값 확인 후 실행
echo "$SSH_CONNECTION"; id -un
sudo bash ~/admin_read.sh <개발계정> <출발주소> <수신주소> <수신포트> 203.0.113.1 > ~/admin-read-out.txt; echo "rc=$?"
tail -1 ~/admin-read-out.txt                 # 남은 IPv4 0·키 본문 0 확인 뒤 개발 PC 로 받음
```

운영자에게 함께 받을 답(스크립트로 읽을 수 없는 것):
1. 공유기(iptime) 포트 전달 목록 — 1822→22 외 5432·8080·3000 등 다른 전달이 있는지, 1822 출발지 제한 여부.
2. 비밀번호로 SSH 에 들어오는 사람·장치·자동화가 있는지, MFA 사용 여부.
3. 서버 콘솔(모니터·키보드) 또는 다른 복구 경로가 있는지.
4. 접속 승인 계정(manicstr 만인지)과 새 키 추가 여부·그 공개키 지문.

## 4. 변경·복구 묶음 — [change/](change/) (적용 안 함, 운영자 확인 1회 대상)

| 파일 | 역할 |
|---|---|
| [change/00-cat-hardening.conf](change/00-cat-hardening.conf) `0a2933a6…` | 실제 설치 내용: `PasswordAuthentication no`·`KbdInteractiveAuthentication no`·`AuthenticationMethods publickey`·`PermitRootLogin no`·`AllowUsers manicstr` |
| [change/ssh_change.sh](change/ssh_change.sh) | 서버 root 단계 실행기. conf 해시를 상수로 고정 |
| [change/verify_local.sh](change/verify_local.sh) | 개발 PC 독립 접속 확인: `-F /dev/null`(별칭 키 섞임 방지)·ControlMaster/ControlPath 끔·IdentitiesOnly·BatchMode |

순서(단계마다 한 번 부르고 보고): **precheck → backup → [addkey → verify newkey] → stage → reload → verify after**. 복구는 `rollback`(설정 파일 삭제→`sshd -t`→reload), `rollback-key`(authorized_keys 를 백업 바이트로).

- **전제값은 운영자 읽기에서 받는다**: `EXP_MAIN_SHA`(sshd_config)·`EXP_DLIST_SHA`(sshd_config.d 목록 해시)·`EXP_AK_SHA`(authorized_keys)·조건 주소. 다르면 7, 00 파일이 이미 있으면 7, 백업 폴더 `/root/sshd-bak-<STAMP>` 가 있으면 5(덮어쓰지 않음).
- **stage** 는 root:root 0644 로 놓고 5조건 `sshd -T -C` 가 모두 password no·kbd no·methods publickey·root no·pubkey yes(개발 조건은 `allowusers manicstr` 정확히 하나)일 때만 0. 하나라도 다르면 방금 놓은 파일만 지우고 **reload 하지 않고 4**.
- **reload** 는 직전에 같은 검사를 다시 하고, 실패면 reload 하지 않는다. 열어 둔 세션은 확인이 끝날 때까지 닫지 않는다.
- 바꾸는 경로는 상수(00 파일·authorized_keys·백업 폴더)뿐이며 변수 경로는 형식 검사 뒤에만 쓴다.
- 운영자 읽기에서 Match 블록·기존 Allow/Deny·MFA·다른 승인 계정이 나오면 conf 내용을 다시 써서 **다시 제출**한다(아래 M3 처럼 00 파일만으로는 덮이지 않는 경우가 있다).
- 공유기 출발지 제한·fail2ban·MaxStartups 조정은 이 묶음에 없다. 비밀번호 경로를 닫아도 인증 전 연결 과부하는 남을 수 있다.

## 5. 일회용 컨테이너 시험 — **40/40**

[test/in_container.sh](test/in_container.sh) 를 로컬 Docker `ubuntu:22.04`(이미지 `0a9887931b0e`) 안에서 실행. **openssh-server 1:8.9p1-3ubuntu0.18**(운영과 같은 판), 실제 sshd 기동, systemctl 은 sshd 에 HUP 를 보내는 대역. 운영 접속 없음. 1회차 결과 [test/run1/console.txt](test/run1/console.txt)·단계별 출력 [test/run1/](test/run1/).

| 묶음 | 확인 |
|---|---|
| R 운영자 읽기 6+4 | 인자 없음/빈 값/쉼표 끼워넣기/외부=출발 → 2, root 아님 → 3, 정상 rc0·`rc[sshd_T.dev]=0` 줄·50 조각의 yes 가 유효값으로 보임·가림 확인 0·00 부재 표시. 출력 예 [test/run1/admin-read.container.txt](test/run1/admin-read.container.txt) |
| V/C 변경 23 | 적용 전 `publickey,password` → precheck·backup·백업 재실행 5·잘못된 지문 7·addkey·새 키 단독 접속·stage(5조건 일치)·stage 재실행 7·reload(PID 유지)·**after: 두 키 성공·`Permission denied (publickey)`** → rollback 뒤 `publickey,password` 복귀·rollback-key 뒤 새 키 거절·authorized_keys 바이트 복구 |
| M Match 음성 7 | 50 조각에 `Match Address 203.0.113.0/24` + `PasswordAuthentication yes` 를 두면 **00 파일이 있어도 외부·root·없는 사용자 조건에서 yes 가 남는다**. stage 가 4로 멈추고 파일을 지우며 reload 하지 않음 |

한계: 컨테이너에는 systemd·ufw·iptables·docker 가 없어 해당 읽기는 rc127 로만 확인했다. 운영 서버의 실제 reload·공유기·콘솔 경로는 시험하지 않았다.
