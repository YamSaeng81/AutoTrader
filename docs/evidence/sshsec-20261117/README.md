# SSH 보안·접근성 제한 읽기와 운영자 확인·변경안 (총괄 v1.179 §4-1·4-2, 11-17 10:40 KST)

기존 권한(개발 키, 비밀번호 없는 sudo 없음)으로 **읽기만** 했다. 서버 쓰기·설정·방화벽·포트·daemon 변경·fail2ban 설치 없음. sudo·DB 비밀번호는 요청하지 않았다. 전체 IP 는 서버 안 변수에서만 집계했고, 저장 출력은 IPv4 앞 두 자리로 가렸다(전체 IPv4 0건, 비밀 패턴 0 — 아래 실행 표).

## 실행과 종료값

| 항목 | 값 |
|---|---|
| 스크립트 | [read.sh](read.sh)(로컬) → [remote.sh](remote.sh)(서버 표준 입력 `bash -s`) |
| 실제 시각 | 로컬 01:40:58Z~01:42:06Z, **서버 10:40:59~10:42:07 KST**([run-time.txt](run-time.txt)) |
| 종료값 | ssh **rc=0**, stderr 0바이트([read-stderr.txt](read-stderr.txt)). 읽기별 `rc[…]`·grep 의 `0 일치/1 일치없음/2 검사실패`를 [out.txt](out.txt)에 따로 적음 |
| 보존 | read.sh 는 `out-raw.txt` 로 받았고, 가림 확인(전체 IPv4 `grep -c` = 0, 비밀 패턴 `grep -ci` = 0·rc1) 뒤 바이트 그대로 `out.txt` 로 이름만 바꿨다 |

## 결과

| 질문 | 관측 | 판정·한계 |
|---|---|---|
| 지금 공격 중인가 | 최근 5분(10:36~10:41) SSH 로그 129줄, 실패 49·**고유 출발지 22**, MaxStartups·drop 0, 성공 1(이 읽기) | **낮은 강도의 분산 시도가 지금도 있다.** 제한 발동은 이 5분에는 없음 |
| 07:50~08:10 출발지 | 원본 정확 IP 집계: Failed 20,754, 고유 IP 28·/16 21. 상위 1위 **20,708 = 143.198 /16 안 고유 IP 1개**, 2~5위 5·4·4·3 | 앞 보고의 "한 출발지"는 이번 정확 집계로 **IP 1개**가 확인됐다(앞 supp.sh 의 가린 뒤 합산은 근거가 아니었다 — 총괄 지적 수용) |
| 성공 로그인 귀속 | 같은 구간 21건 모두 `manicstr`·publickey·**ED25519 SHA256:zBMiDG…KxoQc**·**지금 이 읽기 접속과 같은 IP** | 지문은 개발 PC `~/.ssh/id_ed25519_cat_ops.pub` 와 같다. 서버 authorized_keys 는 이 키 **1줄뿐**(600). 7일 Accepted 244건 모두 `publickey for manicstr` |
| 비밀번호 경로 | sshd_config: `KbdInteractiveAuthentication no`, `UsePAM yes`, `PasswordAuthentication` 줄 없음. `Include sshd_config.d/*.conf` 의 `50-cloud-init.conf` 는 root 600 — **읽기 실패(grep rc2)** | 실제 값 **미확인**. Ubuntu 기본값과 `Failed password` 로그는 비밀번호 인증이 켜져 있을 정황이지만 기본값으로 추정하지 않는다 |
| 유효 설정 | `sudo -n sshd -T` rc1, `sudo -n true` rc1 | 비밀번호 없는 sudo 가 없어 **미확인** → 운영자 읽기안(아래) |
| 판·서비스 | OpenSSH 8.9p1 Ubuntu-3ubuntu0.18, `ssh.service` active(10-19 08:14 기동)·`ssh.socket` inactive, `sshd -D`, 리스너 `0 of 10-100 startups` | 서버는 22 대기, 개발 PC 는 외부 1822 로 들어온다(공유기 포트 전달로 보임, 미확인) |
| 방화벽 | ufw **active**(규칙은 root 필요 rc1), nftables·firewalld·fail2ban inactive. IPv6 전역 주소 0 | ufw 규칙·공유기 전달 목록 미확인. 0.0.0.0 대기 포트 다수(22·5432·8080·3000 등) — Docker 공개 포트는 ufw 를 우회할 수 있어 외부 노출 여부는 공유기 설정에 달림(이번 범위 밖, 기록만) |
| 로그인 셸 계정 | root, manicstr | root 직접 로그인 성공은 7일 0 |

침입 성공 없음은 **입증하지 않는다**: 성공 로그인 귀속은 07:50~08:10 의 21건과 7일 Accepted 종류까지만 봤다. 같은 키를 다른 장치·사람이 쓰는지는 모른다.

## 운영자 읽기안 (sudo, 읽기만 — 운영자가 직접 실행)

```bash
# 1) 개발 접속 조건의 유효 값(주소는 운영자가 서버 안에서 채운다. 결과는 아래 키 줄만 보고)
sudo sshd -T -C user=manicstr,host=dev,addr=<개발PC 공인IP>,laddr=<서버 내부IP>,lport=22 \
 | grep -Ei '^(passwordauthentication|kbdinteractiveauthentication|authenticationmethods|pubkeyauthentication|permitrootlogin|allowusers|allowgroups|maxstartups|usepam|port|listenaddress) '
# 2) 외부 공격자 조건(root·없는 사용자)
sudo sshd -T -C user=root,host=x,addr=203.0.113.1(문서용 예시 외부 주소),laddr=<서버 내부IP>,lport=22 | grep -Ei '<같은 키>'
sudo sshd -T -C user=nosuchuser,host=x,addr=203.0.113.1(문서용 예시 외부 주소),laddr=<서버 내부IP>,lport=22 | grep -Ei '<같은 키>'
# 3) cloud-init 조각의 관련 줄만, Match 블록 유무
sudo grep -nEi '^\s*(PasswordAuthentication|KbdInteractiveAuthentication|AuthenticationMethods|PermitRootLogin|Match|Allow|Deny)' /etc/ssh/sshd_config.d/*.conf
# 4) 방화벽
sudo ufw status verbose; sudo iptables -S DOCKER-USER 2>/dev/null | head
```

공유기(iptime) 관리 화면의 포트 전달 목록(1822→22 및 다른 전달)도 운영자가 확인한다. 비밀번호 기반 자동화·MFA 사용 여부(예: 다른 장치가 비밀번호로 들어오는지)를 함께 답한다 — 7일 로그에는 publickey 외 성공이 없다.

## 변경·복구안 (제출용, **적용 안 함** — 운영자 확인 1회 뒤)

목표: 승인된 키 로그인(`manicstr`, 위 지문)과 관리 접속을 보존하면서 비밀번호 기반 경로를 닫는다.

1. **사전**: (a) 기존 SSH 세션 하나를 끝까지 열어 둔다. (b) 서버 콘솔(모니터·키보드 등) 접근 가능 여부 확인. (c) 개발 키 외 **별도 새 키**를 authorized_keys 에 추가하고 그 키로 새 접속 성공을 먼저 확인(키 분실 대비). (d) 백업: `sudo install -d -m 700 /root/sshd-bak-<날짜> && sudo cp -a /etc/ssh/sshd_config /etc/ssh/sshd_config.d /root/sshd-bak-<날짜>/`.
2. **차분**: 새 파일 `/etc/ssh/sshd_config.d/00-cat-hardening.conf`(sshd 는 처음 얻은 값을 쓰므로 `50-cloud-init.conf` 보다 앞 이름):
   ```
   PasswordAuthentication no
   KbdInteractiveAuthentication no
   AuthenticationMethods publickey
   PermitRootLogin no
   AllowUsers manicstr
   ```
   읽기안 3)에서 Match 블록이 나오면 그 블록 안 값도 따로 확인하고 차분을 다시 제출한다. MaxStartups 는 기본(10:30:100)을 유지하고, 제한이 다시 관리 접속을 끊으면 별도 안으로 다룬다.
3. **검사**: `sudo sshd -t`(rc0) → 읽기안 1)·2) 재실행으로 password/kbd `no`, authenticationmethods `publickey`, permitrootlogin `no` 확인.
4. **적용**: `sudo systemctl reload ssh`(기존 세션 유지).
5. **적용 뒤 확인**: 다른 터미널에서 `ssh cat-prod 'date'` 성공(개발 키), 새 키로도 성공, `ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password cat-prod` 가 `Permission denied (publickey)` 로 거절. 5분 뒤 `journalctl -u ssh --since -5min` 에서 `Failed password` 0·`Connection closed … [preauth]` 증가를 본다.
6. **복구**(열어 둔 세션 또는 콘솔): `sudo rm /etc/ssh/sshd_config.d/00-cat-hardening.conf && sudo sshd -t && sudo systemctl reload ssh`. 자동 되돌림은 두지 않는다.

공유기의 1822 출발지 제한·fail2ban 은 개발 PC 공인 IP 고정 여부를 모르므로 이번 안에 넣지 않는다(별도 판단).

## 앞 보고 정정 메모 ([netinv-20261117](../netinv-20261117/README.md), 원문·체크섬은 그대로 둠)

총괄 v1.179 §2 에 따라 앞 보고(430차)의 표현 중 근거보다 강했던 것을 정정한다.

| 앞 표현 | 정정 |
|---|---|
| "한 출발지 `143.198.x.x` 의 무차별 대입" | 당시 근거(supp.sh)는 IP를 가린 **뒤** 합산했으므로 IP 1개라고 말할 수 없었다. 이번 원본 정확 집계에서 20,708건은 **IP 1개**로 확인됐다. 같은 20분 동안 다른 출발지 27개도 있었다(5건 이하). |
| "149,646 연결 버림"을 20분 실패 수와 나란히 씀 | 149,646은 약 48분 31초(07:18~08:06:58) 제한 구간의 수다. 20분 실패 20,754와 합치지 않는다. 개발 연결이 끊긴 원인으로는 유력하지만, 연결 하나하나의 인과는 확정하지 않았다. |
| "성공 로그인은 publickey 21뿐" | 07:50~08:10 에 한정한 값이다. 21건 모두 개발 키 지문이며, 이 읽기 접속과 같은 IP에서 왔다. 7일 Accepted 244건도 같은 종류다. 이것으로 전체 기간 침입이 없었다고 입증하지는 않는다. |
| "`sshd -T` 는 sudo 권한이 없어 미확인" | supp.sh 는 오류를 버리고 공통 문구를 썼다. 그래서 당시에는 원인이 입증되지 않았다. 이번 읽기에서 `sudo -n true` rc1, `sudo -n sshd -T` rc1 을 따로 기록했다. |
| "통신 9줄 = 단발·자동 회복·주문 영향 0" | 아래 표로 나눈다. |
| "읽기 6개는 출력이 채워짐(rc1 은 토큰 grep)" | rc1 은 그대로 둔다. 파일이 채워졌다는 것과 각 읽기가 성공했다는 판정은 다르다. 이번 스크립트부터 읽기별 rc 와 grep 0/1/2 를 따로 적는다. |

통신 9줄은 다음처럼 나눈다.

| 줄 | 회복 판정 |
|---|---|
| Upbit 캔들 3줄 | 바로 다음 수집 회차에서 같은 3코인 수집이 끝났다. 회복을 확인했다. |
| 텔레그램 1줄 | 2차 시도에서 성공했다. 첫 시도가 수신됐는지는 모른다. |
| CryptoPanic `Connection reset` 1줄 | CryptoPanic 은 403 이 계속 나고 있었다. 이 reset 이 회복됐는지는 따로 확인하지 않았다. |
| Claude `REPORT_NARRATION` 실패 1줄 | 보존한 창 안에서 다시 성공한 기록이 없다. 회복을 확인하지 않았다. |

- 실자금 보유 0과 SID203 주문 0은 보고 범위에서 확인했다.
- PAPER 신호 지연이나 놓친 진입 같은 기회비용은 확인하지 않았다.
- 05:00 이후 보고 구간에 ERROR 0이었다는 것을 원인이 해소됐다는 뜻으로 쓰지 않는다.
