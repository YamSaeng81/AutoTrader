# v1.145 §3-5 — DYNAMIC 쿨다운 종류별 격리 후보 · D4 안내 정정 (2026-11-11)

총괄 v1.146의 순서 3-5입니다. 판정은 총괄 몫이며 "수용 완료"가 아닙니다. 운영·서버·DB·세션은 건드리지 않았습니다.

## 1. DYNAMIC BLACK_SWAN 쿨다운 종류별 격리 후보

- **가지:** `candidate/dynamic-cooldown-per-kind-b5cd1d0` = **81b0e7a7**ead1e0ab856b639f143f824b8f105de8.
  - 운영 `b5cd1d0` 위 1커밋이고 원격에 올렸습니다. 전문은 `81b0e7a7.patch`입니다.
  - v1.145 결정대로 고정코인(SMA20/LIVE_ALIGNED) 후보와 섞지 않았습니다.
- **바뀐 경로(2개):**
  - `DynamicTradingService.java`(제품)
  - `DynamicBlackSwanCooldownCrossKindTest.java`(시험, 0f3a4b55에서 가져와 격리 계약으로 바꿈)

### 1-1. 바꾼 것

| 항목 | REAL(DYNAMIC) | PAPER(DYN_PAPER) |
|---|---|---|
| 쿨다운 지도 | 기존 `blackSwanBlockedAt`/`blackSwanBlockedPrice` 그대로(코인 키). 이제 REAL 전용 | 새 `paperBlackSwanBlockedAt`/`paperBlackSwanBlockedPrice` |
| 차단 기록·관문 조회·만료 삭제 | 자기 지도 | 자기 지도 |
| 재기동 복원 | `DYNAMIC` 로그 → REAL 지도. 질의·접두어·조회 구간은 종전과 같습니다 | `DYN_PAPER` 로그 → PAPER 지도(신규). REAL 복원에 넣지 않습니다 |
| 쿨다운 240분·진입가 가드·차단 기준 | 그대로 | 같은 함수(`evaluateBlackSwanGate`, `BlackSwanGuard.entryGate`) |

시장 전체 급락 보호 기준은 완화하지 않았습니다. 각 종류가 자기 차단만 기억합니다.

### 1-2. 확인 (같은 입력: KRW-XRP 마지막 H1 봉 −10% 급락 → 회복 봉, 항상 BUY 대역 전략)

| 시험 | 결과 |
|---|---|
| PAPER→REAL 무간섭 | PAPER 차단은 PAPER 지도에만 남고, REAL 지도에는 없습니다. 회복 봉 REAL BUY 로그에 BLACK_SWAN 사유가 없습니다. 같은 회복 봉의 새 PAPER는 자기 쿨다운으로 막힙니다 |
| REAL 자체 보호·REAL→PAPER 무간섭 | REAL 급락에서 "BLACK_SWAN_GUARD 발동"이 나고, 쿨다운과 기준가가 기록됩니다. 회복 봉 REAL은 "BLACK_SWAN 쿨다운 … 해제까지 240분"으로 막히고, 같은 봉 PAPER는 막히지 않습니다 |
| 대조(PAPER 틱 없음) | REAL 회복 틱에 BLACK_SWAN 없음(종전 시험 그대로) |
| 복원 — PAPER 기원 | 재기동 흉내(지도 비움 → 운영 `restoreBlackSwanCooldown`) 뒤 PAPER 지도와 기준가가 복원되고, REAL 지도에는 없습니다 |
| 복원 — REAL 기원 | REAL 지도가 복원되고, PAPER 지도에는 없습니다 |
| 재기동 전후 같은 결과 | 회복 틱 차단 여부: PAPER true→true, REAL false→false |

- **실행:** `after/` 기준 `DynamicBlackSwanCooldownCrossKindTest` 6/6, 기존 REAL 복원 시험 `DynamicBlackSwanCooldownRestoreTest` 7/7(만료·진입가 가드·접두어 한정 포함).
- **관련 묶음(`related/`):** `Dynamic*Test` 27클래스 + `EngineParityTest`·`EngineConstantParityTest` = 29클래스 167건, 실패 0입니다. 전체 회귀는 아닙니다.
- **변경 전 코드로 같은 새 시험 실행(`before/`):** 제품 파일만 b5cd1d0으로 되돌려 실행했습니다. 6건 중 5건이 실패합니다.
  - "재기동 전후 같은 결과" 1건은 의미상 실패입니다. PAPER 차단이 재기동 뒤 사라졌습니다.
  - 4건은 PAPER 지도가 없어 실패한 구조적 실패입니다. 의미상 간섭은 3f6afb90·0f3a4b55의 재현이 보여 줍니다.
  - 대조 1건은 통과합니다.
  - 그 실행용으로 지도 접근을 반사로 바꾼 시험본은 `before/CrossKindTest.before-variant.java`입니다. 후보에는 원래 시험본이 들어 있습니다(복구 뒤 `after/after2-CrossKind.xml` 6/6).
- **남은 것:** 이 후보는 SMA20 후보와 별도로 검토됩니다. 두 후보를 함께 배포할지, 순서를 어떻게 할지는 총괄 결정입니다. 실제 jar 대조와 D 계열 배포안은 배포가 결정된 뒤에 만듭니다.

## 2. D4 README §4 안내 정정 (원문 `sma20p-d4-af7ffa58/README.md`는 고치지 않음)

원문의 "U1에서 멈추면 U1되돌림을 쓴다(재기동 없음). U2 뒤에 멈추면 U되돌림을 쓴다"는 **너무 넓었습니다.** 실제 블록은 멈춘 자리마다 처리가 다릅니다(`d4-stop-lines.txt`에 U1~U4의 멈춤 81줄을 원문 그대로 뽑았습니다). 안내를 다음으로 바로잡습니다.

1. **U1 체크아웃 전 멈춤 → U1되돌림 금지.**
   - 아직 바꾼 것이 없습니다.
   - 해당 멈춤: U0 미통과, U0 30분 초과, :14~:24 창, HEAD·작업 트리 불일치, freshness 회차 진행, 보존 태그 실패 등입니다.
   - 블록 문구에도 "U1-되돌림 하지 않는다"고 적혀 있습니다. U0부터 다시 하거나 보고합니다.
2. **U1 체크아웃 뒤 멈춤 → 그 멈춤이 "U1-되돌림"이라고 말할 때만 U1되돌림.**
   - 해당 멈춤: 체크아웃 결과 불일치, `start_66.py`·시험 파일 불일치, 문법 확인 실패, 빌드 실패, 새 이미지 없음, jar 차이 기대 불일치입니다.
   - "🔴 실행 중 backend가 바뀌었다"는 되돌림이 아니라 보고입니다.
   - U1되돌림 블록 자체가 U2 시작 흔적, HEAD 불일치, 추적 변경, 실행 이미지·cron 불일치에서 바꾸지 않고 멈춥니다.
3. **U2 이후 → 운영 되돌림(U되돌림)은 멈춤 문구가 "되돌림 필요"일 때, 또는 총괄 지시가 있을 때만.**
   - 다음 멈춤은 되돌림이 아니라 보고입니다. 각 문구에 "바로 되돌리지 말고 보고"라고 적혀 있습니다.
     - BTC 입력 미충족·판정 불가
     - 🔴 P0 연결 획득 시간 초과(되돌림 여부는 총괄 판단)
     - N15 만료 사유 기록 실패
     - 뉴스 출처 ERROR(미분류 🔴 그대로)
     - BTC 입력 조회 실패(1분 뒤 그 블록만 다시)
4. **자동 되돌림은 없습니다.** 어느 블록도 다른 블록을 스스로 부르지 않습니다. 되돌림 블록은 사람이 위 조건을 확인한 뒤 실행합니다.

**새 SHA가 고정되면 D 계열 다음 판을 다시 만듭니다.** 대상은 변경 경로 수, 실제 로컬 bootJar 대조(항목 수·차이 줄), 기대 출력, 실행 블록, 복구 기준입니다. af7ffa58의 기대값(12경로·7줄·881→883)을 새 후보에 그대로 쓰지 않습니다. 지금 후보(aligned2 `864f070d`, DYNAMIC `81b0e7a7`)는 아직 고정 후보가 아니므로 D4 후속판은 만들지 않았습니다.

## 3. 파일

- `81b0e7a7.patch`: DYNAMIC 격리 후보 커밋 전문입니다.
- `before/`, `after/`, `related/`: JUnit XML과 gradle 로그입니다.
- `d4-stop-lines.txt`: D4 U1~U4 블록의 `stop "…"` 줄 원문(행 번호 포함)입니다.
