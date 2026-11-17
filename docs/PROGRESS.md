# 최근 작업 이력

현재 할 일은 [현재 계획](LIMITED_LIVE_TEST_PLAN.md), 문서 선택은 [시작 안내](START_HERE.md)를 따른다. 이 파일은 최근 결과의 색인이며 정책·운영 핀을 별도로 복제하지 않는다.

## 최신 제출과 수용을 구분한다

| 구분 | 기준 | 상태 |
|---|---|---|
| 최신 총괄 판단 | [v1.181](evidence/review-bc5a39d9/README.md) | 432차 범위 수용, 관리자 읽기 허용·복구/상태/시각 관문 보완. 실제 변경/생성 전 |
| 최신 개발 제출(총괄 판정 전) | 433차 [SSH 관문 v2](evidence/sshfix-20261118/README.md) · [A/B v4.2.4](evidence/ab-create-v424-20261118/README.md) · [N03 22:00](evidence/n03-1117-2200/README.md) | 운영 변경·생성 없음. 22:12 예약 미실행(PC 절전), 11-18 08:18 늦은 읽기 |
| 직전 개발 제출(범위 수용) | 432차 [SSH 관리자 준비](evidence/sshadmin-20261117/README.md) · [생성안](evidence/create-plan-20261117/README.md) | bc5a39d9. 운영 변경·생성 없음 |
| 그 전 개발 제출(범위 수용) | 431차 [SSH](evidence/sshsec-20261117/README.md) · [v4.2.3](evidence/ab-create-v423-20261117/README.md) | main13a82299. SSH 실제 변경·9세션/T0 없음 |
| 운영 반영(수용) | [427차 결과](evidence/v172run-20261116/README.md) | backend0e1d2a02/2c5d8427·V96·frontend9d7f123a. U2 rc1·U3 지연 편차 보존 |

### 433차 제출 — SSH 복구/상태 관문 v2·A/B 날짜/C/T0 고정·N03 늦은 읽기 (총괄 판정 전)

- SSH `ssh_change.sh` v2(4a16f658): 이번 stage/addkey 산출물 기록과 대조해 제3 00설정·주 설정 변경·제3 목록 파일·후속 키·조회 불명이면 변경0으로 7, reload 뒤 inactive 6·조회 불명 9, 백업 목록 완전성 검사. 8.9p1 컨테이너 실제 sshd **52/52**, 같은 시험에서 v1은 29/52. 관리자 읽기 보충 admin_read_add.sh(usedns·Match Host·실행 인자 rc 분리). [SSH](evidence/sshfix-20261118/README.md)
- A/B v4.2.4(ab_create 63cd5dd1·run_bundle 47745b0b, 목록 e28b1293): 승인 날짜·C·T0 입력 검증, 시작·첫/매 POST 직전 경계=승인 C 확인. 대역 **21/21**(다음C·다음날 2·도중 이탈 3, v4.2 원문은 같은 입력 0 재현)·실행 틀 6/6·고정 단계 파일 5/5·배치 종료값 분리 6/6. 11-18 값은 12:00까지 승인 없으면 폐기. [A/B](evidence/ab-create-v424-20261118/README.md)
- N03 22:00: 예약 프로세스가 PC 절전(17:18~08:05)으로 미실행. 08:18:23 늦은 읽기 rc0 — 22:00:03 전송 성공·🟢·ERROR0·HEAD 0e1d2a02 재시작0, 새 P0 없음. 17:18~08:18 미관측. [N03](evidence/n03-1117-2200/README.md)

### v1.181 — 432차 범위 수용·SSH 복구 관문·생성 시각 고정

- 38/38·12/12·앞 총괄4/4·동결 묶음16/16 일치, 로컬102대조 성공. 관리자 제한 읽기는 수집 범위를 허용하고 UseDNS/개별 종료값을 추가 확인한다. 제3설정 삭제·키 후속 변경 덮어쓰기·reload0/inactive 성공 분기를 재현했다. A/B의 다음C/다음날 허용도 확인해 승인 날짜/C/T0 관문을 최소 보완한다. 실제 변경·생성은 승인 전이다. [검토와 지시](evidence/review-bc5a39d9/README.md)

### 432차 제출 — SSH 관리자 준비·생성 실행 제출안 (범위 수용)

- 정정: 7일244건은 종류/사용자만, root 전용 파일 값 미확인, 공격 지속은10:40 기준. 개발 계정 조건 서버 응답 `publickey,password`(11:22:55, 비밀값 없음) — 그 조건에서password 방식 제시를 확인했으며 비밀번호 성공은 검사하지 않았다. 운영자 읽기 스크립트(인자 검사·`sshd -T -C` 종료값 분리·가림)와 변경/복구 묶음(전제 해시·검사 실패 시 reload 없음)을 8.9p1 컨테이너에서40/40, Match 블록이 00 파일을 덮는 경우 4로 멈춤 확인. 생성은13a82299 archive16/16, D=11-18 일정·명령표, SMA20 계획만 통과·음성4 거절. [SSH](evidence/sshadmin-20261117/README.md) · [생성](evidence/create-plan-20261117/README.md)

### v1.180 — 431차 범위 수용·도구 동결·SSH 관리자 확인

- 커밋13a82299의7/7·50/50·16/16·앞 총괄4/4 일치, 로컬149대조 성공. v4.2.3의 토큰 오류 구분/후속 거절을 수용하고 같은 검증은 반복하지 않는다. 과거20,708건은IP1개로 확인. 20분 성공21건의 키 귀속과7일244건의 종류/사용자 집계를 구분한다. 실제 생성·관리자 변경은 승인 전이며 유효 설정 읽기와 실제 차분/복구안을 준비한다. [검토와 지시](evidence/review-13a82299/README.md)

### 431차 제출 — SSH 제한 읽기·실행 틀 v4.2.3 (범위 수용)

- SSH10:40:59~10:42:07 rc0, 최근5분 실패/Invalid user49줄·출발지22·제한0. 유효 인증 값·방화벽 규칙은 미확인이다. 도구는bad10c08, 보존Linux49/49·Git Bash43/43, 옛 판29/43. chmod 오실행·WSL 무효/덮임 회차는 성공 근거와 분리한다. [SSH](evidence/sshsec-20261117/README.md) · [도구](evidence/ab-create-v423-20261117/README.md)

## 최근 완료 기록

| 차수 | 결과·판정 | 근거 |
|---|---|---|
| 433 제출 | SSH 관문 v2 52/52·A/B 시각 관문 21/21·N03 늦은 읽기 | [sshfix](evidence/sshfix-20261118/README.md) · [v424](evidence/ab-create-v424-20261118/README.md) |
| v1.181 / 432 | 관리자 읽기 수집 허용·SSH 복구/상태와 A/B 시각 관문 보완 | [review-bc5a39d9](evidence/review-bc5a39d9/README.md) |
| v1.180 / 431 | v4.2.3 고정·SSH 제한 읽기 수용, 관리자 확인 대기 | [review-13a82299](evidence/review-13a82299/README.md) |
| v1.179 / 430 | 단건·봉/T0·N03 범위 수용, SSH 후속·토큰 오류 보완 | [review-c3dca385](evidence/review-c3dca385/README.md) |
| v1.178 / 429 | DB 메타·로컬 연결·늦은 읽기 수용, 리다이렉트 보완 | [review-ab062049](evidence/review-ab062049/README.md) |

## 마지막 운영 읽기와 인계

마지막 운영 읽기는 **11-18 08:18:23 KST** N03 22:00 보고 늦은 읽기다(DB는 22:00 보고 값, 직접 조회 없음). 22:12 예약은 개발 PC 절전으로 실행되지 않았고 11-17 17:18~11-18 08:18은 미관측이다. 장부 마지막 읽기 10:13:16, SSH 로그 10:40:59~10:42:07, 인증 방식 시도 11:22:55. [현재 인계](CURRENT_HANDOFF.md)

## 이력 보존과 갱신

- [분리 직전 전체 원문](PROGRESS_ARCHIVE_20261112.md)에397차를 포함한3,483줄을 보존했다. 앞 판정의 상세는 각 고정 evidence/커밋에서 찾는다.
- 그보다 이른 기록은 [old_progress](old_progress.md), [CHANGELOG](CHANGELOG.md)를 날짜·키워드로 찾는다.
- 새 결과는 최근 **5건 이내**의 짧은 요약/링크만 둔다. 개발 차수와 총괄 차수는 기존 번호를 잇고 실제 시각·미관측·제출/수용을 구분한다.
