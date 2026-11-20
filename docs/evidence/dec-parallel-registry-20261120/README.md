# 12월 준비 19 — 병행 등록표와 N03/F1/F3 계약 초안 (총괄 v1.201 §3-2)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전), **초안**이다. 설치본·고정 실행 출처 cd8bd76f·F 원문([RUNBOOK](../../LIMITED_LIVE_TEST_RUNBOOK.md))·N03 read.sh(41e4f337)·P7 v5(`reconcile.py` 2ca03476…, [440차](../dec-reconcile-v5-20261118/README.md))는 한 글자도 바꾸지 않았다. 운영·DB·거래소 접속은 없었다. 기준 제품은 d81c3f2f Git 원문이다.

## 1. 지금 고정 명령이 12월 병행과 충돌하는 곳

| 위치 | 현재 고정값 | 병행(LIVE 203 + DYNAMIC REAL n개)에서 |
|---|---|---|
| F 변수 `EG`/`EGS` | 활성 LIVE REAL **1**, 활성 DYNAMIC REAL **0**을 상수로 기대(`EGS='1|0|1|true|…|92|0'`) | DYNAMIC REAL을 하나라도 만들면 F1이 불일치로 멈춘다. 상수 대신 **등록표에서 기대값을 만든다** |
| `LIVE1`·`KEEP` | SID203 한 행만 확인 | 유지. 등록표의 다른 세션은 별도 행 검사로 더한다 |
| 장부(RUNBOOK 205~217행) | `20200 − Σ매수 + Σ매도`처럼 SID203 단독 식 | 세션별 식은 유지하고, 계좌 대조는 **모든 등록 세션 합**과 한다(§3) |
| P7 v5 등록표 | 항목 필드가 정확히 `REQUIRED`+선택 `effective_axis`(초과 필드는 `REGISTRY_INVALID`, 373행) | 아래 새 필드를 넣으려면 P7을 v6으로 고쳐야 한다. 이번에는 고치지 않았다 |
| 성적표 `flows` | 제품 원천 없음([as-of 계약](../dec-scorecard-asof-20261120/README.md) §2) | 등록표의 `capital_events`만 원천으로 쓴다 |

## 2. 등록표 v2 초안 — P7 v5 항목에 더할 것

P7 v5의 항목(kind·id·mode=REAL·strategy_type·timeframe·coin_pair·initial_capital·effective_from·rules·code_sha)과 `observation_source`는 그대로 둔다. 더할 것은 아래와 같다.

| 필드 | 수준 | 내용 | 쓰는 곳 |
|---|---|---|---|
| `ruleset_hash` | 세션 | 생성 직후 `position`/`ruleset_snapshot`에서 확인한 규칙 지문 | 성적표 provenance, F3 |
| `observe_from` | 세션 | 성적표·대조 관측 시작(첫 경계 포착 시각). `effective_from`(등록 효력)과 구분 | 성적표 start |
| `code_sha_history[]` | 세션 | `{sha, from}`. 기간 안 운영 배포마다 한 줄. N03/F1의 운영 SHA 관측과 대조 | 성적표 code_version 묶음 |
| `capital_events[]` | 표 | `{type: ALLOCATE/RECOVER, kind, id, amount, at, ref}`. **계좌 안 이동**이다 | 성적표 flows, 미배정 현금 |
| `account_flows[]` | 표 | `{type: DEPOSIT/WITHDRAW, amount, at, ref}`. 거래소 계좌 입출금만 | 계좌 월손익, 미배정 현금 |
| `unallocated_krw_base`·`non_session_holdings` | 표 | 등록 시점에 세션 밖에 있던 KRW·코인(예: 기존 보유) | 계좌 합산 |

DYNAMIC의 `coin_pair='DYNAMIC'` 규칙은 그대로 둔다. DYNAMIC은 BTC를 포함해 아무 코인이나 보유할 수 있다.

## 3. 계좌 합산 대조 계약(N03 장부·F3 공통)

- **A1 코인별:** 거래소 보유(가용+잠김) = Σ 등록 세션의 열린 포지션 수량(LIVE+DYNAMIC, **BTC 포함**) + `non_session_holdings`. BTC를 빼거나 LIVE만 대조해서 맞추지 않는다.
- **A2 KRW:** 거래소 KRW(가용+잠김) = Σ 등록 세션 `available_krw` + Σ 미체결 매수 `reserved_krw` + 미배정 현금.
- **A3 배정은 입금이 아니다:** 미배정 현금 = `unallocated_krw_base` + Σ DEPOSIT − Σ WITHDRAW − Σ ALLOCATE + Σ RECOVER. 배정은 계좌 월손익에 영향이 없고 세션 성적표의 flows에만 들어간다.
- **A4 미등록 실자금:** 등록 밖 (kind,id)의 REAL 세션·주문·포지션이 하나라도 있으면 합산을 맞추려 하지 않고 이상으로 낸다. 이는 P7 v5의 `UNREGISTERED_REAL_SESSION_ACTIVE`(880행)와 같은 판정이다. 거래소 완료 주문 중 DB에 없는 것은 [P7 v4 §3](../dec-reconcile-v4-20261118/README.md) 수집안으로 잡는다.
- 미체결·정산 대기·불명 주문이 있는 코인과 세션은 P7 v5 규칙대로 보류한다. 수수료 반올림 허용 차는 P7 v5 값을 그대로 쓴다. 이 초안에서 새 허용 차를 정하지 않았다.

[registry_sum.py](registry_sum.py)로 A1~A4를 가상 값에 적용했다. 결과는 [run1.out.txt](run1.out.txt)이며 **4/4 통과(rc0)**다. 경우는 P1 정상(LIVE·DYNAMIC 모두 BTC), P2 LIVE만으로 BTC 대조(불일치 검출), P3 배정을 입금으로 셈(KRW 불일치 검출), P4 등록 밖 DYNAMIC#94(이상)이다.

## 4. N03/F1/F3 변경 초안(설치 전, 문안만)

1. **F1:** `EGS` 상수를 등록표에서 만든 기대값으로 바꾼다. 기대값은 활성 LIVE REAL id 집합 = 등록 LIVE, 활성 DYNAMIC REAL id 집합 = 등록 DYNAMIC이다. 개수만이 아니라 **id 집합 일치**를 본다. 비상 정지 목록(157…200 / 92)과 `capital_stage_baseline` 검사는 유지한다. 등록표 파일 해시는 F 변수로 고정하고 F1 시작 때 대조한다.
2. **F3(RUNNING 첫날):** SID203 검사에 더해 등록 세션마다 `KEEP`에 해당하는 행 고정 검사(전략·주기·자본·생성 시각)와 `ruleset_hash`를 확인한다. 이어서 A1~A4 계좌 합산을 한다.
3. **N03:** 보고 회수는 그대로 둔다. 장부 회수에 등록 세션 전체의 세션 행·열린 포지션·미체결 매수 예약을 더한다. 월 경계(매월 1일 10:00 회차)에는 [as-of 계약](../dec-scorecard-asof-20261120/README.md)의 경계 포착을 같은 회차로 묶는다.
4. P7 v6: `REQUIRED`에 §2 필드를 더하고 A3 계산을 넣는다. 실패를 먼저 고정한 뒤 고친다. 착수는 총괄이 이 초안을 판정한 뒤에 한다.

## 5. 보류(결정 의존)와 하지 않은 것

- 총원금, 월 손실 상한, DYNAMIC 세션 수·전략·배정액이 정해지지 않았다. 그래서 등록표 실제 값, `EGS` 새 기대값, F 변수 해시는 비워 두었다.
- 미배정 현금과 기존 보유(`non_session_holdings`)의 실제 값은 승인된 계좌 읽기가 필요하다. 이번에는 읽지 않았다.
- RUNBOOK·read.sh·P7 v5·제품 코드 수정, 실제 생성·토큰·배치·운영 조회는 하지 않았다.

체크섬은 [SHA256SUMS](SHA256SUMS)에 있다.
