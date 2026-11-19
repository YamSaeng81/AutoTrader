# 최근 작업 이력

현재 할 일은 [현재 계획](LIMITED_LIVE_TEST_PLAN.md), 문서 선택은 [시작 안내](START_HERE.md)를 따른다. 이 파일은 최근 결과의 색인이며 정책·운영 핀을 별도로 복제하지 않는다.

## 최신 제출과 수용을 구분한다

| 구분 | 기준 | 상태 |
|---|---|---|
| 최신 총괄 판단 | [v1.192](evidence/review-0bc5d295/README.md) | 443차 일부 범위 수용. 성적표 v3·원문 고정 수용, 비용 후보 두 경계/실행 로그 보존 보완 뒤 통합 검증 |
| **최근 개발 결과(일부 범위 수용)** | 443차 [비용 기준 후보](evidence/dec-costbasis-20261119/README.md) · [성적표 v3](evidence/dec-scorecard-v3-20261119/README.md) · [포함표](evidence/dec-inclusion-20261119/README.md) · [N03 22:00 회수](evidence/n03-1118-2200/README.md) | 후보 b861bdf9(로컬): 같은 PG 시험 기점 15/48 → 후보 48/48(XML1/1), 영향 H2 214/215→고정물 뒤 13/13, PG 4클래스 35/35. 성적표 v2 red 2/2 → v3 60/60·변형14/14. 시험 원문 두 가지 test-only 커밋·원격 보존 |
| 최근 개발 결과(로컬 범위 수용) | 442차 **16595682** [손익 정의 영향](evidence/dec-pnl-definition-20261119/README.md) · [성적표 v2](evidence/dec-scorecard-v2-20261118/README.md) · [금액/BTC PG](evidence/dec-amtbtc-pg-20261119/README.md) · [최소 알림](evidence/dec-min-alert-20261119/README.md) · [운용안](evidence/dec-ops-plan-20261119/README.md) | 각 PG1/1·성적표52/52/변형10·알림 XML8개81성공. 조사/후보 수용, 성적표 실제 평가 사용은 잔여 보완·출처 연결 뒤 |
| 최근 개발 결과(로컬 범위 수용) | 441차 f14ff757 [금액/BTC](evidence/dec-amtbtc-20261118/README.md) · [매도 PG](evidence/dec-pg-sell-20261118/README.md) · [성적표](evidence/dec-scorecard-20261118/README.md) | 금액/BTC·매도 PG 범위 수용, 성적표는 시제품 제출만 수용하고 판정 도구 사용은 보완 뒤 |
| 앞 개발 결과(로컬 범위 수용) | 440차 acd420df [P7 v5](evidence/dec-reconcile-v5-20261118/README.md) | 시계 축 263/263·변형12/12 연결, 운영 앱/거래소 축 관계 미확인 입력은 보류 유지 |
| 수용된 최근 개발 결과 | 439차 [격리 PG 현금흐름](evidence/dec-pg-cashflow-20261118/README.md) · [모드+쿨다운 통합](evidence/dec-modecd-20261118/README.md) | 0e1d2a02 실제 엔진 LIVE·DYNAMIC 예약→부분 체결→취소 환급·정정3·반복·거래0 대조 30/30, 조정 잔차 0. DB−수집기 시계 +1.37~1.50초·앱 시각 축 거짓 이상 재현. 통합 ac52715e 12클래스 101 성공 |
| 앞 개발 결과(로컬 범위 수용) | 438차 [P7 v4 U1~U3](evidence/dec-reconcile-v4-20261118/README.md) · [PAPER9 11-19 제안](evidence/ab-create-v424-20261119/README.md) | v3 red 109중12실패→v4 109/109, 기존94 판정 동일, 새 변형11/11. 11-19 단계/배치 날짜 차분·좁은 시험15/5/6. PG·실제 생성 없음 |
| 직전 개발 결과(로컬 범위 수용) | 437차 [P7 v3](evidence/dec-reconcile-v3-20261118/README.md) | **294645f1** 커밋·푸시(230파일 블롭 일치). 207/207·저장87입력 일치/CLI7 연결, 합성 메타데이터 |
| 기존 도구 수용 | 434차 [배치 보완 §5](evidence/ab-create-v424-20261118/README.md) | e0119f0a 원격 참조 일치. 배치57fefb0f·WSL8/8·기존6/6. 실제 배치/생성/SSH 변경 없음 |
| 이전 SSH/날짜판 | 433차 [SSH v2](evidence/sshfix-20261118/README.md) · [A/B v4.2.4](evidence/ab-create-v424-20261118/README.md) · [N03](evidence/n03-1117-2200/README.md) | dd50e906. 날짜/C/T0·SSH 관문 수용, 배치 기록은434차에서 보완 수용 |
| 이전 조사(로컬 범위 수용) | 436차 [원문 연결·P7 v2·쿨다운/알림/성적표](evidence/dec-reconcile-20261118/README.md) | c2a09f9c. XML48/8/53성공, P7 저장35입력 일치·추가 반례7개로 보완 전 |
| 운영 반영(수용) | [427차 결과](evidence/v172run-20261116/README.md) | backend0e1d2a02/2c5d8427·V96·frontend9d7f123a. U2 rc1·U3 지연 편차 보존 |

### 444차 결과 — 비용 기준 판정 불가 두 경계 보완·원문 보존 보충 (제출, 판정 전)

- **비용 경계 후보** `candidate/cost-basis-boundary-b861bdf9` **32d5dafd**(b861bdf9 기점, 로컬 — 원격 올리기는 권한 검사 거절로 미실행): 새 PG 시험 `CostBasisBoundaryPgTest`를 먼저 커밋, 기점 **39/54**(실패15 = 원천 없는 청산·매도 수수료 NULL) → **59/59**. 매도 대금/수수료/수량 NULL은 합에 0으로 넣지 않고 판정 불가, 주문 원천 없는 청산은 저장 손익 대신 판정 불가, 무거래 확정(체결0 확정 취소·DYNAMIC 예약 거절 빈 슬롯 `ENTRY_REJECTED` 새 표시)만 예외. 확정 원천 복구 뒤 다음 조회에서 자동 해제. 영향 H2 29클래스 **215/215**, PG 5클래스 **36/36**(기존 48단언 포함). 고정물 4클래스에 같은 손익 확정 주문 원천. 해제 범위표·배포 전 읽기 v2(제품 조회문 기계 추출, 로컬 문법만). [비용 경계](evidence/dec-costbasis-boundary-20261119/README.md)
- **원문 보존 보충:** 커밋 누락 실행 로그13개를 바이트 그대로 강제 추가(목록 해시13/13·저장 blob 13/13 일치), 성적표 `.pyc` 제외 목록 `SHA256SUMS.submitted`(74) 별도. 과거 목록 보존.
- **판정 필요:** `ENTRY_REJECTED` 열거값 추가, 과거 예약 거절 빈 슬롯(표시 없음)의 판정 불가는 배포 전 읽기 R1·R3로 확인.

### v1.192 — 443차 검토·비용 후보 두 경계 보완 지시

- f55f5af0의 N03 10:13:09 읽기 수용(목록23/23·커밋24파일 일치). 미분류3건은 뉴스3건, Claude 오류는 별도다. 추가 착수 확인 없이 비용 후보 후속을 계속한다. [보충](evidence/review-f55f5af0/README.md)

- 고정0bc5d295의 로컬 목록 해시는 일치. 독립429성공·커밋 범위3불일치(실행 로그13개·파생 캐시1개). 비용 후보의 원천 없는 청산/매도 수수료 NULL 경계를 확인해 PG 보완을 지시했다. 성적표 v3·원문 시험 고정은 수용, A+B+C 로컬 통합 방향과 후보 원격 보존 허용. 운영/실제 생성 별도. [판정/지시](evidence/review-0bc5d295/README.md)

### 443차 결과 — 비용 기준 후보·성적표 v3·시험 원문 고정·N03 (일부 범위 수용)

- **비용 기준 후보** `candidate/cost-basis-risk-0e1d2a02` **b861bdf9**(0e1d2a02 기점, 로컬): 회귀 시험을 먼저 커밋했다(기점 실패 33·통과 15). 손실 합 SQL 2개는 포지션별 확정 매수 단가(대금+매수 수수료 ÷ 수량)를 매도 수량만큼 배분한다. 연속 손실은 주문 원천의 비용 기준으로 바꿨다. LIVE_ALIGNED `riskPnl`은 비용 기준이 되어 R5 관례가 바뀐다. 원가 미상·팬텀은 신규 진입만 막는 **판정 불가**이며, 정지나 청산은 하지 않는다. 같은 시험으로 +1→−2.75, −103.70→−107.45, 0 경계, 연속 1/2회와 이익 끊김, 일/주/월 창, 부분 매도·정정·반복·롤백을 확인했고 후보는 48/48이다. 열린 판단은 판정 불가 해제 경로(30일 전면 차단 가능)와 기존 0.01% 손실률 해상도다. [근거](evidence/dec-costbasis-20261119/README.md)
- **성적표 v3:** v2 원문에 두 반례 2/2 실패 재현 → 정산 시각 미상의 수수료·매수 대금은 알려진 부분 합 + 미확정, 매도<매수 시각은 rc2. 60/60·변형14/14. 월 귀속 출처안(누적 행 vs 이벤트, 최초 시각 귀속 + 정정 미확정 표시) 제출. [근거](evidence/dec-scorecard-v3-20261119/README.md)
- **시험 원문 고정:** 손익 정의 PG `7750b547`(기점 0e1d2a02)·금액/BTC PG `d16ea176`(기점 aff357e2) test-only 커밋, 근거 사본과 블롭 해시 일치, 제품/빌드 입력은 각 기점과 같음. 두 가지와 dfe3b9f7 원격 보존. 실행 당시 사본 청정성의 사후 증명으로 쓰지 않는다.
- **정정:** 최소 알림 README "9클래스" → XML 8개(클래스 7, 중첩 2), 손익 정의 README LIVE_ALIGNED 평균가(체결가 S:1867)·C는 정적 함수 관측.
- **포함표:** A aff357e2·B dfe3b9f7·C b861bdf9를 로컬 사본(9cbbda62)에 쌓아 문자 충돌 0·patch-id 6/6 동일·컴파일 성공(시험 미실행). 필수 검증 순서 7단계 제안. [포함표](evidence/dec-inclusion-20261119/README.md)
- **N03:** 11-18 22:00 보고를 11-19 09:15:58 KST 회수(rc0, 🟢·ERROR0·새 P0 없음, DB는 보고 값). [근거](evidence/n03-1118-2200/README.md)

### v1.191 — 442차 검토와 비용 기준 보완 지시

- 고정16595682의 목록4/65/4/10·앞 총괄5개와 Git 블롭 일치, 독립328대조 성공. REAL 비용 뒤 손실 누락을 제품 보완 대상으로 판정했다. 비용 기준 독립 후보와 성적표 잔여2건·원문 고정/포함표를 지시했다. 최소 알림은 실제8클래스81성공이며 문서9클래스를 정정한다. [판정/지시](evidence/review-16595682/README.md)

### 442차 결과 — 손익 정의 영향·성적표 v2·금액/BTC PG·최소 알림·운용안 (로컬 범위 수용)

- **손익 정의:** 0e1d2a02 실제 경로·격리 PG 1회. 매수 7,500+3.75·순매도 7,501이면 LIVE·DYNAMIC REAL 저장 +1(비용 뒤 −2.75) → 손실 합 SQL 0·관문 승인·연속 손실 미발동, 같은 구간 DYN_PAPER는 연속 손실 발동. 실제 손실은 매수 수수료만큼 과소(−103.70 vs −107.45), 정적 손절 함수는 평균가 두 입력을 넣었을 때285.10에서 갈림(엔진 경로 확인은 후속). 가격/비용 기준 분리 최소안 제출(구현 안 함). [근거](evidence/dec-pnl-definition-20261119/README.md)
- **성적표 v2:** v1 원문에 네 반례 4/4 실패 재현 → 새 사본 보완(미실현 기초/기말/증감 분리·생애/이 달 실현 분리, 월 밖 입출금 rc2, 실자금/모의 분리·계좌는 실자금만, 금액/비율 낙폭 각각·입출금 보정, 정산 시각만 월 귀속, 매수 예약 평가). 52/52·변형10/10. [근거](evidence/dec-scorecard-v2-20261118/README.md)
- **금액/BTC PG:** aff357e2 그대로 JSONB 정렬·정수 저장, 재조회 지문 895ab6b8dc16 유지, 상한 10,000 예약·주문 행, BTC 제외·관문 입력 유지, 제외 코인 보유 REAL 매도 CLOSED. c70a8330·aff357e2 원격 보존 푸시. [근거](evidence/dec-amtbtc-pg-20261119/README.md)
- **최소 알림:** 후보 dfe3b9f7(0e1d2a02 기점, 로컬): `종류#ID`·구간 안 재기동 줄·꺼짐 때 미적재. 새 8 + 관련 기존 73 = 81/81. [근거](evidence/dec-min-alert-20261119/README.md)
- **운용안:** 고정1=SID203, 동적1=사전 규칙으로 COMPOSITE_BREAKOUT H1 초안·BTC 제외·가상 상한 10,000. 금액·최종 전략은 사용자 결정 대기. [근거](evidence/dec-ops-plan-20261119/README.md)

## 최근 완료 기록

| 차수 | 결과·판정 | 근거 |
|---|---|---|
| 문서(11-19) | 설정 문구 감사: START_HERE에서 날짜별 상태 제거(현재 계획·인계만 기준), 사용자 graphify 스킬 강조 문구 완화. 제품·운영 변경 없음 | [시작 안내](START_HERE.md) |
| v1.192 / 443 | 비용 기준 후보 b861bdf9·성적표 v3·시험 원문 고정·N03 22:00 회수 | [후보](evidence/dec-costbasis-20261119/README.md) |
| v1.191 / 442 | 비용 기준 위험 보완 후보·성적표 잔여2건, PG/알림 로컬 범위 수용 | [검토](evidence/review-16595682/README.md) |
| 441·440 | 금액/BTC·매도 PG·P7 v5 로컬 범위 수용(상세 절은 정리, 근거 링크 유지) | [금액/BTC](evidence/dec-amtbtc-20261118/README.md) · [P7 v5](evidence/dec-reconcile-v5-20261118/README.md) |
| v1.190 / 441·440 | P7 v5·매도 PG·금액/BTC 로컬 범위 수용, 성적표 보완·손익 정의 영향 후속 | [검토](evidence/review-f14ff757/README.md) |
| v1.189 | 438·439차 수용·시계 축/독립 후보/성적표 후속 | [검토](evidence/review-87126a31/README.md) |
| 439 | 매수 PG 현금흐름·시계 축·모드+쿨다운 로컬 범위 수용 | [PG](evidence/dec-pg-cashflow-20261118/README.md) |
| 438 | P7 v4 세 경계·PAPER9 날짜 파일 범위 수용 | [P7 v4](evidence/dec-reconcile-v4-20261118/README.md) |
| v1.188 / 437 | P7 v3 로컬 범위 수용·추가 경계/PG·날짜 재제출 | [검토](evidence/review-p7v3-20261118/README.md) |
| v1.187 / 436 | 원문/조사·쿨다운 로컬 범위 수용, P7 보완과다음 후보/성적표 | [검토](evidence/review-c2a09f9c/README.md) |
| v1.186 / 435 | 조사·모드 후보 로컬 범위 수용, 병행 대조/원본 연결 후속 | [검토](evidence/review-76387b25/README.md) |
| v1.185 | DYNAMIC 초안 정정·병행 점검/장부 전제 | [검토](evidence/review-dec-dynamic-20261118/README.md) |
| v1.184 | 12월 소액 실전·다이나믹 수익성 관측 준비 | [운용 준비](DECEMBER_SMALL_LIVE_PLAN.md) |
| v1.183 / 434 | 배치57fefb0f 수용·도구 보완 완료 | [review-e0119f0a](evidence/review-e0119f0a/README.md) |

## 마지막 운영 읽기와 인계

마지막 운영 읽기는 **11-19 10:13:09 KST** N03 10:00 보고·09:30 장부 rc0(🔴 미분류 ERROR 3 = 뉴스·LLM 외부 통신, 자정 TRADE_SUMMARY 텔레그램 1건 실패, SID203 CREATED·장부 balance_diff 0) — [N03 10:00](evidence/n03-1119-1000/README.md). 그 앞 11-19 09:15:58 KST — N03 11-18 22:00 보고 늦은 회수 rc0(22:00:04 전송 성공·🟢·ERROR 0, 설치 상태 HEAD 0e1d2a02·backend 재시작0), DB는 보고 값이다 — [N03 22:00](evidence/n03-1118-2200/README.md). 다음은11-19 22:00 보고 생성·전송 뒤22:12 이후 읽기다. 그 앞은 N03 10:00 보고·09:30 장부 예약 읽기 11-18 10:13:21 KST rc0(보고 🔴1=미분류 ERROR 기존 반복 tokenpost_rss 시간 초과, SID203 CREATED·밖 실자금0·정산 이상0, 장부 balance_diff0)다 — [N03 10:00](evidence/n03-1118-1000/README.md). 앞 22:00 보고는 08:18:23 늦은 읽기(DB는 보고 값, 직접 조회 없음). 22:12 예약은 개발 PC 절전으로 실행되지 않았고 11-17 17:18~11-18 08:18은 미관측이다. SSH 로그 10:40:59~10:42:07, 인증 방식 시도 11:22:55. [현재 인계](CURRENT_HANDOFF.md)

## 이력 보존과 갱신

- [분리 직전 전체 원문](PROGRESS_ARCHIVE_20261112.md)에397차를 포함한3,483줄을 보존했다. 앞 판정의 상세는 각 고정 evidence/커밋에서 찾는다.
- 그보다 이른 기록은 [old_progress](old_progress.md), [CHANGELOG](CHANGELOG.md)를 날짜·키워드로 찾는다.
- 새 결과는 최근 **5건 이내**의 짧은 요약/링크만 둔다. 개발 차수와 총괄 차수는 기존 번호를 잇고 실제 시각·미관측·제출/수용을 구분한다.
