# 최근 작업 이력

현재 할 일은 [현재 계획](LIMITED_LIVE_TEST_PLAN.md), 문서 선택은 [시작 안내](START_HERE.md)를 따른다. 이 파일은 최근 결과의 색인이며 정책·운영 핀을 별도로 복제하지 않는다.

## 최신 제출과 수용을 구분한다

| 구분 | 기준 | 상태 |
|---|---|---|
| 최신 총괄 판단 | [v1.180](evidence/review-13a82299/README.md) | 431차 범위 수용·v4.2.3 고정·SSH 관리자 확인. 실제 생성 전 |
| 최신 개발 제출(판정 전) | 432차 [SSH 관리자 준비](evidence/sshadmin-20261117/README.md) · [생성 제출안](evidence/create-plan-20261117/README.md) | 운영 변경·생성 없음. 접속 시도1회(비밀값 없음)만 |
| 직전 개발 제출(범위 수용) | 431차 [SSH](evidence/sshsec-20261117/README.md) · [v4.2.3](evidence/ab-create-v423-20261117/README.md) | main13a82299. 4목록 일치. SSH 실제 변경·9세션/T0 없음 |
| 그 전 개발 제출(범위 수용) | 430차 [v4.2·단건·봉/T0](evidence/ab-create-v42-20261117/README.md) · [N03](evidence/n03-1117-1000/README.md) · [통신](evidence/netinv-20261117/README.md) | mainc3dca385. 통신 회복은 항목별로 구분 |
| 운영 반영(수용) | [427차 결과](evidence/v172run-20261116/README.md) | backend0e1d2a02/2c5d8427·V96·frontend9d7f123a. U2 rc1·U3 지연 편차 보존 |

### 432차 제출 — SSH 관리자 준비·생성 실행 제출안 (판정 전)

- 정정: 7일244건은 종류/사용자만, root 전용 파일 값 미확인, 공격 지속은10:40 기준. 개발 계정 조건 서버 응답 `publickey,password`(11:22:55, 비밀값 없음) — 그 조건의 비밀번호 인증은 켜져 있다. 운영자 읽기 스크립트(인자 검사·`sshd -T -C` 종료값 분리·가림)와 변경/복구 묶음(전제 해시·검사 실패 시 reload 없음)을 8.9p1 컨테이너에서40/40, Match 블록이 00 파일을 덮는 경우 4로 멈춤 확인. 생성은13a82299 archive16/16, D=11-18 일정·명령표, SMA20 계획만 통과·음성4 거절. [SSH](evidence/sshadmin-20261117/README.md) · [생성](evidence/create-plan-20261117/README.md)

### v1.180 — 431차 범위 수용·도구 동결·SSH 관리자 확인

- 커밋13a82299의7/7·50/50·16/16·앞 총괄4/4 일치, 로컬149대조 성공. v4.2.3의 토큰 오류 구분/후속 거절을 수용하고 같은 검증은 반복하지 않는다. 과거20,708건은IP1개로 확인. 20분 성공21건의 키 귀속과7일244건의 종류/사용자 집계를 구분한다. 실제 생성·관리자 변경은 승인 전이며 유효 설정 읽기와 실제 차분/복구안을 준비한다. [검토와 지시](evidence/review-13a82299/README.md)

### 431차 제출 — SSH 제한 읽기·실행 틀 v4.2.3 (범위 수용)

- SSH10:40:59~10:42:07 rc0, 최근5분 실패/Invalid user49줄·출발지22·제한0. 유효 인증 값·방화벽 규칙은 미확인이다. 도구는bad10c08, 보존Linux49/49·Git Bash43/43, 옛 판29/43. chmod 오실행·WSL 무효/덮임 회차는 성공 근거와 분리한다. [SSH](evidence/sshsec-20261117/README.md) · [도구](evidence/ab-create-v423-20261117/README.md)

### v1.179 — 430차 범위 수용·SSH 후속·토큰 검사 보완

- 고정c3dca385의7목록 일치, 로컬432대조 성공. v4.2·SMA20 단건·검사한 봉/T0·차트 대비·N03 읽기를 범위 수용했다. SSH 제한은 별도 보안/접근성 후속으로 올리고 IP 가림 집계·통신 전부 회복 서술을 정정했다. nosecret 검사 오류가0건/후속 허용으로 바뀌는 공백을 재현해 최소 보완을 지시했다. [검토](evidence/review-c3dca385/README.md)

### 430차 제출 — 생성 묶음·단건·N03·통신 조사 (범위 수용)

- v4.2 리다이렉트·SMA20 로컬 연결1/1·봉/T0·차트 후보·실행 틀v4.2.2를 제출했다. N03·장부10:13:16 rc0, 통신 조사10:17 rc1은 유지한다. 캔들/텔레그램 뒤 성공과CP·Claude회복/PAPER 기회비용 미확인을 구분한다. 세션 공백은 사용 한도 중단·자동 재개 없음이다. [도구](evidence/ab-create-v42-20261117/README.md) · [N03](evidence/n03-1117-1000/README.md) · [통신](evidence/netinv-20261117/README.md)

## 최근 완료 기록

| 차수 | 결과·판정 | 근거 |
|---|---|---|
| v1.180 / 431 | v4.2.3 고정·SSH 제한 읽기 수용, 관리자 확인 대기 | [review-13a82299](evidence/review-13a82299/README.md) |
| v1.179 / 430 | 단건·봉/T0·N03 범위 수용, SSH 후속·토큰 오류 보완 | [review-c3dca385](evidence/review-c3dca385/README.md) |
| v1.178 / 429 | DB 메타·로컬 연결·늦은 읽기 수용, 리다이렉트 보완 | [review-ab062049](evidence/review-ab062049/README.md) |
| v1.177 / 428 | N05·화면 수용, 생성 도구·단건·봉/T0 보완 | [review-26a12f3e](evidence/review-26a12f3e/README.md) |
| v1.176 / 427 | V172D4 운영 반영 수용·핀 갱신 | [review-7fea1b89](evidence/review-7fea1b89/README.md) |

## 마지막 운영 읽기와 인계

마지막 N03·장부는 **11-17 10:13:16**, SSH는 **10:40:59~10:42:07**이다. DB 값은10:00 보고 기준이며 직접 조회는 없다. 22:12 이후 N03 예약은 아직 실제 실행을 확인하지 않았다. 실제 생성·T0는 승인 전이다. [현재 인계](CURRENT_HANDOFF.md)

## 이력 보존과 갱신

- [분리 직전 전체 원문](PROGRESS_ARCHIVE_20261112.md)에397차를 포함한3,483줄을 보존했다. 앞 판정의 상세는 각 고정 evidence/커밋에서 찾는다.
- 그보다 이른 기록은 [old_progress](old_progress.md), [CHANGELOG](CHANGELOG.md)를 날짜·키워드로 찾는다.
- 새 결과는 최근 **5건 이내**의 짧은 요약/링크만 둔다. 개발 차수와 총괄 차수는 기존 번호를 잇고 실제 시각·미관측·제출/수용을 구분한다.
