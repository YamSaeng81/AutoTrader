# DYNAMIC BLACK_SWAN 쿨다운 — 기동 복원 비대칭 실행 재현 (야간 D 보완)

2026-11-10 · 개발 제출 · 총괄 v1.144 D. [D 차이표](../dynamic-diff-20261110/README.md) #9는 소스 확인만 했었다. 이번에 실행으로 재현했다.

- 시험 가지 `candidate/sma20-paper-fwd-tests-af7ffa58` `0f3a4b55`, 시험 `DynamicBlackSwanCooldownCrossKindTest` **4/4**.
  - 앞선 2건(공유 간섭·대조)도 이 실행에서 다시 통과했다.
- 재기동 흉내는 이렇게 했다: 서비스 전역 지도를 비운 뒤 운영 기동 처리기 `restoreBlackSwanCooldown()`을 그대로 부른다.
  - 이 처리기는 `strategy_log`에서 `session_type = 'DYNAMIC'`이고 `blocked_reason`이 `BLACK_SWAN_GUARD 발동`으로 시작하는 행만 읽는다.

| 사례 | 재기동 전 | 재기동 뒤 |
|---|---|---|
| PAPER(DYN_PAPER)가 급락 봉에서 차단 | 전역 지도에 KRW-XRP가 있다. 이 상태로 REAL BUY를 막는다(D 재현) | **복원되지 않음** |
| REAL(DYNAMIC)이 같은 차단 | 지도에 있음 | **복원됨** |

- 따라서 같은 PAPER 차단이 REAL 진입을 막는지는 재기동 여부에 따라 갈린다. 재기동 전에는 막고, 재기동 뒤에는 막지 않는다.
- 고치지 않았다. 쿨다운을 종류별로 나눌지, 시장 상태로 보고 복원에도 PAPER 기록을 넣을지는 진입 계약 변경이며 총괄 결정이다.
- 외부 호출은 0이다(주문·시세·WS·공용 유니버스는 목). 배포 후보 `af7ffa58`은 바뀌지 않았다.
