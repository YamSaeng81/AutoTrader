# v1.145 §3-1 — DynamicStopRaceTest 대기 보완과 1회차 실패 원인 (2026-11-11)

총괄 v1.146(계획 §114)의 순서 3-1. 시험만 바꿨고 제품 코드는 바꾸지 않았다. 배포 후보 `af7ffa58`은 그대로다.
보완은 시험 가지 `candidate/sma20-paper-fwd-tests-af7ffa58`의 **658ba4af** (0f3a4b55 위)에 있고 원격에 푸시했다(`658ba4af.patch`가 그 커밋 전문이다).

## 1. 원래 실패가 무엇이었나

전체 시험 1회차(11-10 18:16:04~05, `sma20p-d4-af7ffa58/fulltest/run1-DynamicStopRaceTest.xml`)에서 ① "의도 커밋 → 정지 → claim"이
`state=INTENT_READY` 를 출력했고(18:16:04.99x), **그 뒤** 작업자 `order-exec-2`가 18:16:05.000~.001에 같은 주문 318의 `WITHDRAWN_STOPPED` 철회를 기록했다.
즉 제품은 정지를 보고 철회했고, 시험이 **작업이 끝나기 전에 읽었다.**

시험의 옛 대기 `awaitDrained()` 조건은 `큐 비었음 && active 0 && taskCount == completedTaskCount` 였다. `ThreadPoolExecutor` 는

- `getTaskCount()` = 끝난 수 + **실행 표시(lock)를 잡은 작업자 수** + 큐 길이
- `getActiveCount()` = 실행 표시를 잡은 작업자 수

로 계산한다. **놀고 있던 작업자가 `queue.take()` 로 작업을 꺼낸 뒤 `runWorker` 가 lock 을 잡기 전**에는 큐 0·active 0·taskCount == completed 가 모두 참이다. 그 창에서 읽으면 작업은 아직 실행 전이다.

반면 작업자가 없어 **새 작업자**가 첫 작업으로 받으면(`addWorker(firstTask)`), 작업자는 생성 상태(-1)부터 lock 잡힘으로 세어져 active 1이 되고 조건이 거짓이다.

| 실행 | 작업자 스레드 | 결과 | 경로 |
|---|---|---|---|
| 전체 시험 1회차(11-10) | `order-exec-2` | ① 실패 INTENT_READY | 앞선 시험 클래스들이 같은 컨텍스트의 `orderExecutor`에 작업자를 이미 만들어 둠 → 놀고 있는 작업자가 꺼냄 → **창 있음** |
| 전체 시험 2회차(11-10) | `order-exec-2` | 통과 | 같은 경로, 시점만 달랐음 |
| 단독 실행(11-10 22:15, 11-11 08:30 `alone-before.xml`) | `order-exec-1` | 통과 | ①이 첫 제출이라 새 작업자 생성 → active 1 → **창 없음** |

`order-exec-2`만으로 "놀고 있는 작업자가 꺼냈다"를 확정하지는 못한다(작업자 1개만 있었다면 2번이 새로 생길 수도 있다). 그러나 실패가 났다는 사실과 위 계산을 합치면 1회차는 놀고 있는 작업자 경로였다는 것이 유일하게 맞는 설명이다. 2회차·단독은 같은 조건이 거짓이거나 시점이 빨라 통과했다.

**제품 결함은 아니다.** 정지→claim 경로는 1회차에서도 철회(WITHDRAWN_STOPPED)로 끝났고 제출은 0회였다. 경계는 시험 대기 조건이다.

## 2. 고정 재현 — `OrderExecutorDrainPredicateGapTest` (3/3)

Spring 컨텍스트·DB·거래소 없이, 운영 `orderExecutor`와 같은 `ThreadPoolTaskExecutor`·같은 설정(core 2, max 4, queue 50)으로 latch 를 걸었다.

```
[① 꺼낸 뒤·실행 전] drained=true executed=false queue=0 active=0 tasks=0 completed=0
[② 새 작업자·실행 전] drained=false executed=false queue=0 active=1 tasks=1 completed=0
[③ 꺼낸 뒤·실행 전] oldPredicate=true future.isDone=false executed=false
```

①이 총괄 `DrainWitness` r2와 같은 창이다(총괄은 생 `ThreadPoolExecutor`, 여기는 Spring 실행기 종류). ②가 단독 실행이 통과한 이유다. ③은 보완한 대기가 기대는 신호가 그 창에서 아직 완료가 아님을 보인다.

## 3. 보완 — `dispatchAndAwait(intent)`

- 프록시를 벗긴(`AopTestUtils.getUltimateTargetObject`) `submitPreparedOrder` 를 같은 `orderExecutor` 빈에 `submit()` 해 **Future** 를 받고 `get(20s)` 로 실제 종료를 기다린다. `@Async` 프록시는 같은 실행기에 다시 넘기기만 하므로 claim·철회 논리는 같다.
- 시간 초과는 `AssertionError`(미완료를 정상 종료로 보지 않음), 작업 예외도 실패다.
- 핵심 작업자를 미리 띄워(`prestartAllCoreThreads`) 전체 시험과 같은 "놀고 있는 작업자" 경로를 쓰게 했다. 단독 실행의 새 작업자 경로만 검사하지 않기 위해서다.
- sleep 없음, 기대값 변경 없음, 반복해서 녹색 얻기 없음, 제외 없음. `awaitDrained()`는 이 시험에서 지웠다.
- 기존 단언은 그대로다: 정지가 size=0 포지션을 닫지 않음, FAILED/WITHDRAWN_STOPPED, 거래소 호출 0, 고아 정리가 예약 3,000을 정확히 한 번 복원, ③의 SUBMITTED 유지·늦은 체결 반영·7058.53.

## 4. 결과

| 실행 | 시각(KST) | 결과 |
|---|---|---|
| 보완 전 단독 1회(진단, 작업자 경로 확인) | 08:30:21 | 6/6, `order-exec-1` — `alone-before.xml` |
| 보완 후 1회 | 08:33:26 | DynamicStopRaceTest 6/6 · Gap 3/3 — `after-r1-*.xml` |
| 핵심 작업자 미리 띄움 추가 후 1회 | 08:34 | 6/6 · 3/3 — `after-r2-*.xml` |

```
[DYNAMIC 정지→claim] state=FAILED code=WITHDRAWN_STOPPED
[DYNAMIC claim→정지→체결] order=FILLED pos=OPEN/9.80000000 available=7058.53
```

전체 회귀는 돌리지 않았다(v1.145: 최종 후보가 안정되기 전 반복하지 않음).

## 5. 남은 경계(이번에 고치지 않음)

같은 대기 조건(`큐 비었음·active 0·taskCount == completed`)을 쓰는 시험 파일이 **21개** 더 있다(`same-predicate-tests.txt`, `AbstractSma20SameInputFixture` 포함). 같은 창이 있으므로 전체 시험에서 간헐 조기 읽기가 날 수 있다. 각 시험의 제출 경로가 달라(afterCommit 디스패치, 실시간 이벤트 등) 일괄 치환은 하지 않았고, 최종 후보 통합 때 시험별 완료 신호로 바꿀 대상으로 남긴다. 이번 지시 범위는 `DynamicStopRaceTest`다.

## 6. 파일

- `658ba4af.patch` — 시험 가지 커밋 전문(DynamicStopRaceTest 변경 + 새 Gap 시험).
- `patch_wait.py` — DynamicStopRaceTest 치환에 쓴 스크립트(핵심 작업자 미리 띄움 한 줄은 그 뒤 따로 넣었다, patch 참조).
- `alone-before.{log,xml}`, `after-r1*.{log,xml}`, `after-r2*.{log,xml}` — gradle 출력과 JUnit XML 원문.
- `same-predicate-tests.txt` — 같은 조건을 쓰는 시험 파일 목록.

서버·DB·세션·설정은 건드리지 않았다. 네트워크 주문·알림은 MockBean/대역이다.
