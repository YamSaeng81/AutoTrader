# 445차 A+B+C 통합 — 포함표·교차·전체 회귀(rc=1, 실패 1)·원인 확인 (총괄 v1.193 §3·v1.194 §1)

2026-11-19 KST, 개발 세션 제출. 판정은 총괄이 한다. 통합 고정 SHA는 **60df3437**이다(가지 `integ/abc-aff357e2-dfe3b9f7-b408a223`, 사본 `D:/tmp/integ-abc`). 운영 접속·배포·생성은 하지 않았다.

## 1. 포함표 [integration-commits.txt](integration-commits.txt)

| 묶음 | 원 가지 끝 | 내용 |
|---|---|---|
| A 금액/BTC | aff357e2 | DYNAMIC maxOrderKrw·excludeCoins |
| B 최소 알림 | dfe3b9f7 | 종류#ID 표시·최소 알림 |
| C 비용 기준 | b408a223 | 판정 불가 계약·N1·N2 |
| 교차 시험 | 60df3437 | `AbcCrossPgTest` |

- 기점 0e1d2a02 위에 15커밋을 옮겼다. 원 커밋과 통합 커밋의 patch-id가 15/15 같다(파일 셋째 열 `same`).
- 교차 시험 [cross-r1](cross-r1/)·[cross-r2](cross-r2/)는 각각 새 DB에서 **19/19**다.

## 2. 전체 회귀 [full-60df3437/](full-60df3437/)

14:38:59~14:59:52, **rc=1**. HEAD 60df3437, 실행 전후 변경 0, 환경 관문 변수는 비웠다.

| 모듈 | 시험 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|---|
| core-engine | 298 | 291 | 0 | 7 |
| exchange-adapter | 11 | 11 | 0 | 0 |
| strategy-lib | 113 | 113 | 0 | 0 |
| web-api | 1444 | 1357 | **1** | 86 |

- 건너뜀은 환경 변수를 주지 않은 PG 클래스와 수동 실행기다(목록은 [summary.txt](full-60df3437/summary.txt)).
- XML 보존 대조([xml-copy-check.txt](full-60df3437/xml-copy-check.txt)): 생성 대 보존이 47/2/17/222로 같고, 해시 불일치는 0이다.
- NET-GUARD: 194클래스를 검사했고 외부 연결 시도 차단은 0이다.
- 실패: `DailySma20LiveOrderPathTest.reentersAfterClose`. "BUY 주문 1건 … 연속 손실 판정 불가 — … 다음 청산(posId=226)의 확정 비용을 구하지 못했다 … Expected size: 1 but was: 0".

## 3. 실패 원인 — 최소 영향 확인 [regression-cause/](regression-cause/)

같은 전체 회귀는 다시 돌리지 않았다. 실패 클래스 하나만 각 묶음의 단독 사본에서 돌렸다.

| 실행 | 사본 | 결과 |
|---|---|---|
| [sma20-A-aff357e2](regression-cause/sma20-A-aff357e2/) | A 단독 | 24/24 rc=0 |
| [sma20-C-b408a223](regression-cause/sma20-C-b408a223/) | C 단독 | **23/24 rc=1**, 같은 시험·같은 판정 불가 문구 |
| [sma20-fx-7e0143e1](regression-cause/sma20-fx-7e0143e1/) | 60df3437 + 시험 보정 1판 | 24/24 rc=0 (공통 `orders()`를 바꾼 판 — 아래 2판으로 대체) |
| [sma20-fx-09fa2d1c](regression-cause/sma20-fx-09fa2d1c/) | 60df3437 + 시험 보정 2판(v1.195 §2-2·3) | **24/24 rc=0** |

- 원인은 C 계약과 시험 입력이 맞지 않는 데 있다. 시험은 주문 행이 없는 CLOSED 포지션(매도·실현손익 없음)을 만들고 "정상 청산 뒤 재진입"을 기대한다.
- C 계약(v1.192 §2)은 주문 원천이 없는 청산을 판정 불가로 보고 신규 진입을 막는다. 따라서 이번 거절은 계약대로 나온 결과다. 시간·경합으로 생긴 실패가 아니다.
- 이 클래스는 C의 영향 클래스 선정(종전 29 + 소비자 6)에 들어 있지 않았다.
- 보정은 가지 `fixture/dailysma20-reentry-60df3437`의 **09fa2d1c**다(7e0143e1 → 09fa2d1c, [fixture-commits](regression-cause/fixture-commits.txt)). 60df3437 대비 바뀐 것은 시험 1파일 +49/−1이고 제품은 바꾸지 않았다([fixture.diff](regression-cause/fixture.diff)).
  - 앞선 청산에 같은 LIVE 세션 ID와 포지션 ID로 연결된 확정 매수·매도를 둔다. 매수 0.001 = 대금 15,000 + 수수료 7.5, 매도 0.001 = 15,100 − 7.55, 매도 반영 시각은 청산 시각이다. 수량과 비용은 포지션(15,000,000 × 0.001)과 맞는다.
  - 새 매수 전에 `costBasisEntryBlock`이 null(판정 가능)이고 서킷 브레이커가 미발동임을 단언한다.
  - 기존 재진입 단언은 그대로 둔다: BUY 접수 정확히 1회, SUBMITTED, `sma20-new-` 주문번호, 거래소 호출 1회. 앞선 거래의 원천 행(`sma20-closed-*`)은 이 시험 안에서만 이번 접수에서 뺀다.
  - 공통 `orders()` 도우미와 다른 시험의 입력은 바꾸지 않았다(1판은 공통 도우미를 바꿔서 2판으로 대체했다).
  - 원천 없는 청산이 거절되는 C 음성 시험(`CostBasisBoundaryPgTest`)은 그대로다.
  - 직접 넣은 원천은 시험 준비 데이터이며, 실제 엔진 왕복 체결의 증거가 아니다.
- **60df3437은 바꾸지 않았다.** v1.195 §2-2는 이 방향의 포함을 승인했고, 최종 고정 SHA 구성은 따로 정한다.

## 3-1. 추가 발견 — 회귀에서 건너뛴 `LiveLossWindowPgTest`도 C에서 깨진다 (미보정, 판단 필요)

REAL 매수 요약 영향 실행 중에 발견했다. 전체 회귀에서는 `LW_PG_URL`이 없어 이 클래스가 건너뜀으로 끝났다. 각각 새 DB를 썼다(`v152-pg` 15437).

| 실행 | 사본 | 결과 |
|---|---|---|
| [lw-base-0e1d2a02](regression-cause/lw-base-0e1d2a02/) | 운영 기점 | 7/7 rc=0 |
| [lw-C-b408a223](regression-cause/lw-C-b408a223/) | C 단독 | **1/7 rc=1** |
| [lw-base-60df3437](regression-cause/lw-base-60df3437/) | 통합 | **1/7 rc=1**, 실패 문구는 C 단독과 같다 (매수 요약 후보 596ed4b0 결과도 같다) |

- 원인: 시험이 매수 주문 없이 포지션을 직접 만들고 그 위에 실제 매도만 확정한다.
- C 계약에서는 매수 원가 미상 → "판정 불가 — 30일 안 LIVE 세션 매도/청산 1건의 확정 비용…을 구하지 못했다"가 되고, 그 손실은 합에 들어가지 않는다(기대 2,001 → 실제 0).
- DailySma20과 같은 종류의 준비 데이터와 계약 충돌이다. C의 영향 클래스 선정과 PG 묶음에 이 클래스가 빠져 있었다.
- v1.195 §2-3("다른 시험의 입력을 일괄 변경하지 않는다")에 따라 시험을 고치지 않았다.
- 같은 방식의 보정(같은 세션·포지션에 확정 매수 원천을 두는 시험 전용 커밋)을 할지, 그리고 비슷한 직접 생성 고정물이 있는 다른 PG 클래스까지 조사할지는 총괄 판단으로 둔다.

## 4. 별도 발견 — REAL 매수 체결 요약 누락

공유 풀 REAL 시장가 매수는 원자 정산 경로로만 회계가 반영되고, 기존 매수 요약 버퍼를 부르지 않는다. 교차 시험 X2에서 관측했다. 보완 후보는 [dec-real-buy-summary-20261119](../dec-real-buy-summary-20261119/README.md)에 별도 가지로 있고, 60df3437에는 넣지 않았다.

체크섬 [SHA256SUMS](SHA256SUMS).
