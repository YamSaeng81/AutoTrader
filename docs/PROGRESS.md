# 최근 작업 이력

현재 할 일은 [현재 계획](LIMITED_LIVE_TEST_PLAN.md), 문서 선택은 [시작 안내](START_HERE.md)를 따른다. 이 파일은 최근 결과의 색인이며 정책·운영 핀을 별도로 복제하지 않는다.

## 최신 제출과 수용을 구분한다

| 구분 | 기준 | 상태 |
|---|---|---|
| 최신 총괄 판단 | [v1.190](evidence/review-f14ff757/README.md) | P7 v5·매도 PG·금액/BTC 로컬 범위 수용. 성적표 네 반례와 손익 정의 보호식 영향 후속, 운영 사용/생성 전 |
| **최근 개발 제출(총괄 판정 전)** | 442차 [손익 정의 영향](evidence/dec-pnl-definition-20261119/README.md) · [성적표 v2](evidence/dec-scorecard-v2-20261118/README.md) · [금액/BTC PG](evidence/dec-amtbtc-pg-20261119/README.md) · [최소 알림](evidence/dec-min-alert-20261119/README.md) · [운용안](evidence/dec-ops-plan-20261119/README.md) | REAL 저장 손익 정의가 손실 관문·연속 손실·손절을 바꿈(PG 1/1). 성적표 v2 52/52·변형10/10. 금액/BTC PG 1/1. 알림 후보 dfe3b9f7 81/81. 운용안 가상 자본 초안 |
| 최근 개발 결과(로컬 범위 수용) | 441차 f14ff757 [금액/BTC](evidence/dec-amtbtc-20261118/README.md) · [매도 PG](evidence/dec-pg-sell-20261118/README.md) · [성적표](evidence/dec-scorecard-20261118/README.md) | 금액/BTC·매도 PG 범위 수용, 성적표는 시제품 제출만 수용하고 판정 도구 사용은 보완 뒤 |
| 앞 개발 결과(로컬 범위 수용) | 440차 acd420df [P7 v5](evidence/dec-reconcile-v5-20261118/README.md) | 시계 축 263/263·변형12/12 연결, 운영 앱/거래소 축 관계 미확인 입력은 보류 유지 |
| 수용된 최근 개발 결과 | 439차 [격리 PG 현금흐름](evidence/dec-pg-cashflow-20261118/README.md) · [모드+쿨다운 통합](evidence/dec-modecd-20261118/README.md) | 0e1d2a02 실제 엔진 LIVE·DYNAMIC 예약→부분 체결→취소 환급·정정3·반복·거래0 대조 30/30, 조정 잔차 0. DB−수집기 시계 +1.37~1.50초·앱 시각 축 거짓 이상 재현. 통합 ac52715e 12클래스 101 성공 |
| 앞 개발 결과(로컬 범위 수용) | 438차 [P7 v4 U1~U3](evidence/dec-reconcile-v4-20261118/README.md) · [PAPER9 11-19 제안](evidence/ab-create-v424-20261119/README.md) | v3 red 109중12실패→v4 109/109, 기존94 판정 동일, 새 변형11/11. 11-19 단계/배치 날짜 차분·좁은 시험15/5/6. PG·실제 생성 없음 |
| 직전 개발 결과(로컬 범위 수용) | 437차 [P7 v3](evidence/dec-reconcile-v3-20261118/README.md) | **294645f1** 커밋·푸시(230파일 블롭 일치). 207/207·저장87입력 일치/CLI7 연결, 합성 메타데이터 |
| 기존 도구 수용 | 434차 [배치 보완 §5](evidence/ab-create-v424-20261118/README.md) | e0119f0a 원격 참조 일치. 배치57fefb0f·WSL8/8·기존6/6. 실제 배치/생성/SSH 변경 없음 |
| 이전 SSH/날짜판 | 433차 [SSH v2](evidence/sshfix-20261118/README.md) · [A/B v4.2.4](evidence/ab-create-v424-20261118/README.md) · [N03](evidence/n03-1117-2200/README.md) | dd50e906. 날짜/C/T0·SSH 관문 수용, 배치 기록은434차에서 보완 수용 |
| 이전 조사(로컬 범위 수용) | 436차 [원문 연결·P7 v2·쿨다운/알림/성적표](evidence/dec-reconcile-20261118/README.md) | c2a09f9c. XML48/8/53성공, P7 저장35입력 일치·추가 반례7개로 보완 전 |
| 운영 반영(수용) | [427차 결과](evidence/v172run-20261116/README.md) | backend0e1d2a02/2c5d8427·V96·frontend9d7f123a. U2 rc1·U3 지연 편차 보존 |

### 442차 제출 — 손익 정의 영향·성적표 v2·금액/BTC PG·최소 알림·운용안 (총괄 판정 전)

- **손익 정의:** 0e1d2a02 실제 경로·격리 PG 1회. 매수 7,500+3.75·순매도 7,501이면 LIVE·DYNAMIC REAL 저장 +1(비용 뒤 −2.75) → 손실 합 SQL 0·관문 승인·연속 손실 미발동, 같은 구간 DYN_PAPER는 연속 손실 발동. 실제 손실은 매수 수수료만큼 과소(−103.70 vs −107.45), 5% 손절선은 285.10에서 갈림. 가격/비용 기준 분리 최소안 제출(구현 안 함). [근거](evidence/dec-pnl-definition-20261119/README.md)
- **성적표 v2:** v1 원문에 네 반례 4/4 실패 재현 → 새 사본 보완(미실현 기초/기말/증감 분리·생애/이 달 실현 분리, 월 밖 입출금 rc2, 실자금/모의 분리·계좌는 실자금만, 금액/비율 낙폭 각각·입출금 보정, 정산 시각만 월 귀속, 매수 예약 평가). 52/52·변형10/10. [근거](evidence/dec-scorecard-v2-20261118/README.md)
- **금액/BTC PG:** aff357e2 그대로 JSONB 정렬·정수 저장, 재조회 지문 895ab6b8dc16 유지, 상한 10,000 예약·주문 행, BTC 제외·관문 입력 유지, 제외 코인 보유 REAL 매도 CLOSED. c70a8330·aff357e2 원격 보존 푸시. [근거](evidence/dec-amtbtc-pg-20261119/README.md)
- **최소 알림:** 후보 dfe3b9f7(0e1d2a02 기점, 로컬): `종류#ID`·구간 안 재기동 줄·꺼짐 때 미적재. 새 8 + 관련 기존 73 = 81/81. [근거](evidence/dec-min-alert-20261119/README.md)
- **운용안:** 고정1=SID203, 동적1=사전 규칙으로 COMPOSITE_BREAKOUT H1 초안·BTC 제외·가상 상한 10,000. 금액·최종 전략은 사용자 결정 대기. [근거](evidence/dec-ops-plan-20261119/README.md)

### v1.190 — 440·441차 검토와 후속

- 제출152/21/28/66개와 앞 총괄6개 체크섬·블롭 일치, 독립844대조 성공. P7 v5·매도 PG·금액/BTC 후보를 로컬 범위 수용했다. 성적표 네 반례를 재현했고 REAL 손익의 매수 수수료 제외가 위험 입력에도 연결됨을 소스로 확인했다. 영향 시험·성적표 v2·좁은 후보 PG·최소 알림·운용안을 이어 간다. [지시](evidence/review-f14ff757/README.md)

### 441차 결과 — 금액/BTC·매도 PG 범위 수용, 성적표 보완 필요

- **금액/BTC:** 새 DYNAMIC 세션 `strategy_params.maxOrderKrw·excludeCoins`(함께 명시·저장 전 거절·정렬 저장), 요청액 = min(기존, 상한) 뒤 최소 주문 보정, 예약 보호선 불변, 제외 코인은 감시 목록·선택·회차 승인·최종 진입에서만 막고 BTC 관문·보유 매도는 유지. 키 없는 세션 지문 ea1ed76bc345 그대로. 후보 가지는 로컬. [근거](evidence/dec-amtbtc-20261118/README.md)
- **성적표·알림:** 파일 시제품(월손익=기말−기초−순입금, CLOSING/가격 없음 미확정, 수수료 표시만, 손실·거래0·중단 포함, 일별 표본 없으면 낙폭 미확인). 알림은 `종류#ID` 통일·재기동 구간 표시 최소안(구현 안 함). [근거](evidence/dec-scorecard-20261118/README.md)
- **매도 PG:** 0e1d2a02 실제 매도 경로로 v5 24/24, 음성10 검출. 새 시계 메타데이터(단계 시작/끝·앞뒤 표본·단조 왕복) 첫 기록: DB−수집기 −40→−64ms 변동, 단계 간격 없던 1차는 경계 보류 2건. REAL 평균가 300(매수 수수료 미포함)이라 저장 실현손익이 주문 원천보다 매수 수수료만큼 큼(PAPER와 정의 다름, 제품 미수정). [근거](evidence/dec-pg-sell-20261118/README.md)

### 440차 결과 — P7 v5 시계 축 (로컬 범위 수용)

- v4 사본에서 시각 비교 7곳만 범위 판정으로 바꿨다. 축(수집기·DB·앱·거래소·등록 선언)과 관계(같은 연결 왕복 표본, 같은 시계 선언)를 관측 메타데이터에서만 받고, 옮길 시각을 앞뒤로 감싸는 표본 범위만 쓴다. 저장 PG 표본에서 한 관측 안 차이도 몇 ms씩 움직임을 확인했다. 앱·거래소 시계 관계가 없는 운영 입력은 그 비교만 보류된다. [근거](evidence/dec-reconcile-v5-20261118/README.md)

### v1.189 — 438·439차 로컬 범위 수용과 후속

- 고정87126a31의 제출 목록241/28/53/16개와 앞 총괄5개 블롭 일치, 독립1,019대조 성공. P7 v4·PAPER9 날짜 파일·매수 PG 현금흐름·모드/쿨다운ac52715e를 범위 수용했다. 시계 축 보완을 승인하고 금액/BTC·월 성적표/최소 알림안과 매도 PG 보충을 병행한다. P7 운영 사용·실제 생성은 승인 전이다. [판정/지시](evidence/review-87126a31/README.md)

## 최근 완료 기록

| 차수 | 결과·판정 | 근거 |
|---|---|---|
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

마지막 운영 읽기는 N03 10:00 보고·09:30 장부 예약 읽기 **11-18 10:13:21 KST** rc0(보고 🔴1=미분류 ERROR 기존 반복 tokenpost_rss 시간 초과, SID203 CREATED·밖 실자금0·정산 이상0, 장부 balance_diff0)다 — [N03 10:00](evidence/n03-1118-1000/README.md). 앞 22:00 보고는 08:18:23 늦은 읽기(DB는 보고 값, 직접 조회 없음). 22:12 예약은 개발 PC 절전으로 실행되지 않았고 11-17 17:18~11-18 08:18은 미관측이다. SSH 로그 10:40:59~10:42:07, 인증 방식 시도 11:22:55. [현재 인계](CURRENT_HANDOFF.md)

## 이력 보존과 갱신

- [분리 직전 전체 원문](PROGRESS_ARCHIVE_20261112.md)에397차를 포함한3,483줄을 보존했다. 앞 판정의 상세는 각 고정 evidence/커밋에서 찾는다.
- 그보다 이른 기록은 [old_progress](old_progress.md), [CHANGELOG](CHANGELOG.md)를 날짜·키워드로 찾는다.
- 새 결과는 최근 **5건 이내**의 짧은 요약/링크만 둔다. 개발 차수와 총괄 차수는 기존 번호를 잇고 실제 시각·미관측·제출/수용을 구분한다.
