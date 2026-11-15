# N15 ④ LATE 거래소 호출 1 — 원인 입증과 시험 보완 (2026-11-16 08:50~08:57 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.169 / 418차](../review-d5790c1c/README.md) §2에 따라 **별도 시험 조사 가지** `investigate/n15-late-20261116`(0e1d2a02 위, 원격 푸시)에서 조사했다. 제품 코드 변경 없음. **고정 후보 0e1d2a02·V168D4에는 합치지 않았다.**

| 커밋 | 내용 |
|---|---|
| ee6e4729 | 고정 장치(`N15PIN=1`일 때만, 기본 꺼짐) — ① LATE KRW-CRO 확정 주문의 비동기 제출을 **claim 커밋·제출 직전 사본 읽기(`findById`) 뒤, `createOrder` 전**에 최대 3초 붙잡고 ④ LATE 판정 직전에 놓는다. `OrderRepository`를 `@SpyBean`으로 바꿔 사본 읽기에만 걸었다(주문 실행기 스레드·그 세션·SUBMITTING일 때). [diff](ee6e4729-pin.diff) |
| **e9d982ab** | 보완(시험만) — ① 정리 전 `ExecutorBarrier.awaitPriorTasks(orderExecutor, 15초)`로 이 사례가 넘긴 제출의 실제 완료를 기다린다(시간 초과는 실패). ② `createOrder` 대역이 응답마다 새 uuid의 접수(`wait`) 응답을 돌려준다(종전 null → `recordSubmitOutcome` NPE). 기대값·단언 그대로. [diff](e9d982ab-fix.diff) |

## 원인 (입증)

- 경로: `liveBoundary`①의 확정 주문(KRW-INJ·KRW-CRO, EARLY·LATE 각 2건)은 `submitPreparedOrder`(@Async `order-exec-*`)로 넘어가고 ①은 그 완료를 기다리지 않는다. 사례가 끝나면 정리가 주문 행을 지우고 스프링이 `@MockBean UpbitOrderClient`를 초기화한다. 제출이 이미 claim·사본 읽기를 마쳤다면 그 뒤 `createOrder`를 부르고, 그 호출은 **초기화된 대역의 다음 사례 호출 수**로 잡힌다. `recordSubmitOutcome`은 행이 없으면 바로 돌아가 NPE 기록도 남지 않는다.
- 고정 재현(보완 전, `runs/pin4-before-fix`, HEAD ee6e4729·차분 없음): 붙잡음 `order-exec-1 주문#5 세션8` → ④ LATE 판정 직전 놓음, **주문 행 없음(정리됨)** → ④ LATE 대역 호출 `createOrder [KRW-CRO, bid, null, 5000, price, cat-c30466a0…]` **식별자 일치 true** → `LIVE 접수 LATE expected 0 but was 1`. 실행 순서는 ⑤ → ② → ① → ⑥ → ④ → ③이다. 실패 사례·값이 자연 관측 2회와 같다. `runs/pin3`은 같은 장치를 20초 고정으로 둔 첫 실행이고 결과가 같다.
- 자연 관측과의 대조: 보존 원문 [p-1115-205003](../v168-final-20261115/probe/runs/p-1115-205003/)·p-1115-225010의 이 클래스 출력에는 `order-exec` NPE가 3건뿐이다. 통과한 보완 사본 12회도 3건이다. 즉 ① LATE 제출 2건은 보통 정리 뒤 사본이 비어 `createOrder`에 가지 않는다. 늦은 호출은 행이 없어 NPE를 남기지 않으므로, 실패 실행에서 NPE 수가 그대로인 것과 맞는다. 다만 자연 실패 때 어느 주문이 호출했는지는 식별자가 기록되지 않아 **직접 확인하지 못했다**. 고정 재현으로 경로가 실제로 있음을 입증했고 관측 특징이 일치한다.

## 보완 검증 (e9d982ab 내용, 한 클래스 10사례)

| 실행 | 조건 | 결과 |
|---|---|---|
| pin4-before-fix | 고정 장치 켬, 보완 전 | **실패1**(④ LIVE LATE, 호출 1, 식별자 일치) |
| ctl1 | 장치 끔, 보완 전 | 실패0 (order-exec NPE 5) |
| pin5-after-fix | 고정 장치 켬, 보완 후 | **실패0** — 정리 장벽이 기다리는 동안 3초 고정이 스스로 풀려 ① 안에서 호출, 놓을 때 **주문 행 있음**, ④ LATE 호출 0 |
| fix-ctl1~3 | 장치 끔, 보완 후 | **3/3 실패0**, order-exec NPE 0 |

보완 후 실행의 작업 사본 차분 sha256 `1533790f…` = `git diff ee6e4729 e9d982ab`. 전체 회귀·다른 클래스는 이 가지에서 돌리지 않았다. 고정 배포의 선행 조건으로 삼지 않는다(v1.169 §2). 원문은 `runs/*/`(meta·summary·gradle.log·N15 XML)와 [`run.sh`](run.sh)에 있다.
