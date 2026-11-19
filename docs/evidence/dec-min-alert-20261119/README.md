# 12월 준비 13 — 최소 알림 독립 로컬 후보 (총괄 v1.190 §4-2)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 후보 **`dfe3b9f7d3f8b3950afc299806d48c5aae7494bf`**(가지 `candidate/min-alert-0e1d2a02`, 부모 0e1d2a02, 로컬 커밋만·푸시 안 함, patch-id `c8462b15…`). 운영·배포·실제 텔레그램 전송·DB는 쓰지 않았다. [차분](candidate-dfe3b9f7.diff) 6파일 +233/−23(제품 5파일 +87/−23, 시험 1파일).

## 1. 바꾼 것 ([12월 준비 9 §2](../dec-scorecard-20261118/README.md#2-최소-알림안-제출-구현-안-함)의 최소안 그대로)

| 빈틈 | 변경 |
|---|---|
| 같은 번호의 LIVE·DYNAMIC 매수가 `세션#ID` 한 묶음에 섞임 | `TelegramNotificationService.sessionLabel(kind, id)` = `종류#ID`(종류 없으면 `종류미상#ID`). 호출 5곳: 엔진 매수 `order.getSessionKind()`, DYNAMIC 매도 `pos.getSessionKind()`, LIVE 매도 `LIVE`, 모의 매수·매도 `PAPER` |
| 재기동하면 반나절 요약이 빠짐 | 서비스 기동 시각을 기억하고, 요약 구간(요약 시각을 KST 0/12시로 내린 뒤 12시간 앞) 안에 기동했으면 요약 머리(거래 없음 요약 포함)에 `HH:MM 재기동 — 그 앞 거래는 이 요약에 없음 · 원본은 DB 주문/정산` 한 줄. MarkdownV2 예약 문자를 새로 쓰지 않음. 버퍼 영속화 없음 |
| 알림 꺼짐일 때 버퍼가 계속 쌓임 | `telegram.enabled=false`면 적재하지 않고 개수만 센다. 요약 시각에 남은 것(켜져 있을 때 적재분 포함)을 버리고 개수를 로그로만 남긴다 |

요약 생성을 `buildDailySummaries(구간 이름, 시각)`로 분리하고 전송은 종전 `sendMarkdownAndLog` 그대로다. 요약 시각 표시는 실행 시각 대신 같은 `now`를 쓴다.

바꾸지 않은 것: 실제 전송·재시도·폴백, 즉시 체결 알림, 새 영속 큐/DDL, 정지·위험·장애 알림 정책, 휴대폰 수신 조건(전송 기록 `success`는 텔레그램 API 응답까지만 뜻함).

## 2. 시험 ([run1](run1/))

| 시험 | 결과 | 범위 |
|---|---|---|
| 새 `TelegramMinimalAlertTest` | **8/8** | 표시 함수, 같은 ID5 LIVE/DYNAMIC 두 요약 분리, 구간 경계(늦게 돈 정오 회차), 구간 안 재기동 줄(거래 있음·없음, 다음 구간엔 없음, 구간 시작 정각은 없음), 재기동 줄 MarkdownV2 안전, 꺼짐 1,000건 미적재·개수 0 복귀, 켜진 뒤 꺼짐 정리, 호출 5곳 원문 검사 |
| 기존 텔레그램 `TelegramMarkdownFallbackTest`·`TelegramCriticalRetryTest`·`TelegramTestIsolationTest`(2) | 6/6·12/12·1/1·1/1 | 전송·폴백·격리 불변 |
| 버퍼 호출을 검사하는 기존 `AlignedV147FixesTest`·`AlignedV148FixesTest`·`EngineParityTest` | 12/12·14/14·27/27 | 모의 매수/매도 버퍼 호출 횟수·엔진 동등성 불변 |

합계 **XML 8개(시험 클래스 7개 — `TelegramTestIsolationTest`의 중첩 2개 포함) 81/81 성공**·실패/건너뜀 0. *(정정 2026-11-19, 총괄 v1.191 §1: 처음 제출의 "9클래스"는 셈 오류였다. 저장된 XML은 8개다.)* 첫 실행에서 새 시험 1건이 실패했는데, 기존 MarkdownV2 처리가 세션 표시의 `#`를 `\#`로 바꾸는 것을 기대값에 넣지 않은 시험 쪽 오류였다(제품 동작은 종전과 같음). 기대값만 고쳤다. 전체 회귀·jar·PG는 돌리지 않았다.

```
./gradlew :web-api:test --tests '*TelegramMinimalAlertTest' --tests '*TelegramMarkdownFallbackTest' --tests '*TelegramTestIsolationTest' --tests '*TelegramCriticalRetryTest'
./gradlew :web-api:test --tests '*AlignedV147FixesTest' --tests '*AlignedV148FixesTest' --tests '*EngineParityTest'
```

Windows, JDK 17.0.12. 체크섬 [SHA256SUMS](SHA256SUMS).

## 3. 한계·통합 메모

- 모의(`PaperTradingService`) 표시는 `PAPER#ID`로 통일했다. 일반 모의와 LIVE_ALIGNED를 다른 이름으로 나눌지는 정하지 않았다(같은 표에서 id가 겹치지 않음).
- 기동 시각은 서비스 빈 생성 시각이다. 실제 재기동 원인·빠진 거래 수는 알리지 않는다(원본 DB를 보라는 표시만).
- 최종 포함표 정리 때 금액/BTC·모드/쿨다운 통합 후보(aff357e2)와 같은 파일(`DynamicTradingService`·`OrderExecutionEngine`)을 건드리므로 결합 충돌·회귀를 그때 한 번 확인한다.
