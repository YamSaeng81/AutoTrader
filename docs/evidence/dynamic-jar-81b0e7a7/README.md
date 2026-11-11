# v1.149 §2 — DYNAMIC 격리 후보 81b0e7a7 독립 실제 jar 대조·영향 범위·배포/복구안 초안 (2026-11-11)

총괄 v1.149가 허용한 범위입니다: 독립 실제 로컬 jar 대조, 영향 범위 검증, 배포·복구안 초안. **서버 실행·배포·고정 코인 후보와의 병합은 하지 않았고, 승인도 아닙니다.** 판정은 총괄 몫입니다.

## 1. 대상과 기점

| 구분 | 커밋 | 로컬 bootJar (sha256) | 항목 수 |
|---|---|---|---|
| 운영 기점 | `b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88` (깨끗한 작업 트리 `D:\tmp\o13n21-1899b49`) | `c704528f…f318` | 734 |
| 후보 | `81b0e7a7ead1e0ab856b639f143f824b8f105de8` (새 작업 트리 `D:\tmp\dyn-81b0e7a7`, 분리 HEAD) | `fd9a0bc0…35cb8` | 734 |

- 두 jar 모두 15:22에 `./gradlew :web-api:bootJar --offline`으로 로컬 빌드했습니다.
- 기점 jar는 **운영 이미지 52e12428 안의 jar가 아닙니다.** 같은 커밋의 로컬 빌드입니다.
- 기점 작업 트리는 컴파일 결과가 최신 상태로 재사용됐습니다. 그래서 클래스 파일 시각은 11-10입니다.
- 소스 차이(b5cd1d0 → 81b0e7a7)는 2파일, +339/−24입니다.
  - 제품: `DynamicTradingService.java` 1개
  - 시험: `DynamicBlackSwanCooldownCrossKindTest.java` 1개(jar에 들어가지 않음)

## 2. 실제 jar 대조 결과 (`jar-compare.json`, `nested-compare.json`, `inner-classes.txt`, `outer-members-diff.txt`)

- **항목 집합은 같습니다:** 734 = 734, 한쪽에만 있는 항목은 0입니다.
- **내용이 다른 항목은 10개**이며, 셋으로 나뉩니다.

| 묶음 | 항목 | 실제 차이 |
|---|---|---|
| 바꾼 클래스 | `DynamicTradingService.class` | 멤버 추가 4개뿐입니다: 필드 `paperBlackSwanBlockedAt`·`paperBlackSwanBlockedPrice`, 메서드 `blackSwanAt(String)`·`blackSwanPrice(String)`. 제거·서명 변경 0. 메서드 본문 차이는 패치와 같습니다(기록·관문·만료·복원 경로) |
| 같은 소스 파일의 내부 클래스 6개 | `$1` `$2` `$3` `$BlackSwanGateDecision` `$BuyCandidate` `$EmptyPositionOutcome` | **바이트코드 차이 0줄.** `javap -v`의 차이는 모두 줄번호표(LineNumberTable)입니다. 바깥 파일의 줄이 밀린 결과입니다 |
| 모듈 jar 3개 | `core-engine` `exchange-adapter` `strategy-lib` -0.1.0.jar | **내부 항목 내용 차이 0**(항목 수 73·29·48 같음). 묶음 안 항목 시각만 다릅니다(빌드 시각) |

- 그 밖의 `BOOT-INF/classes` 항목, 라이브러리 jar, 설정·리소스, Flyway 마이그레이션은 CRC·크기가 같습니다.
- **DB 변경(DDL)은 없습니다.**

## 3. 영향 범위

- **바뀌는 운영 동작은 하나입니다.**
  - 종전에는 DYN_PAPER(모의 동적)의 BLACK_SWAN 차단이 코인 키 공유 지도에 기록됐습니다. 그래서 같은 코인의 REAL(DYNAMIC) 신규 진입을 240분 막았고, 반대 방향도 같았습니다.
  - 후보에서는 각 종류가 자기 지도만 봅니다.
  - **따라서 배포 뒤 REAL 진입이 종전보다 덜 막힐 수 있습니다.** 막히던 경우는 "같은 코인에서 PAPER만 급락을 감지한 경우"입니다.
  - REAL 자신의 급락 보호(240분·진입가 가드·차단 기준)는 그대로입니다.
- **재기동 복원:**
  - REAL 지도는 종전과 같은 질의·접두어·조회 구간으로 `DYNAMIC` 로그에서 복원합니다.
  - `DYN_PAPER` 로그는 이제 REAL 지도에 들어가지 않고 PAPER 지도로 복원됩니다.
  - 배포 직후 재기동 때, 최근 240분 안의 DYN_PAPER 차단이 있으면 종전에는 REAL을 막았고, 이제는 PAPER만 막습니다.
- **시험(이미 제출, `dynamic-isolation-20261111`):** 격리 6/6, 기존 REAL 복원 7/7, `Dynamic*` 27클래스 + 엔진 동등성 2클래스 = 29클래스 167건이며 실패·오류·건너뜀은 0입니다. 이번에는 다시 돌리지 않았습니다(코드 변화 없음).
- **고정 코인(SMA20/LIVE_ALIGNED) 후보와의 관계:**
  - 두 후보 모두 b5cd1d0 기점이고 경로는 겹치지 않습니다. DYNAMIC 후보는 `DynamicTradingService.java`만, aligned2는 PAPER 쪽 파일을 바꿉니다.
  - 그래도 **함께 내보내려면 합친 커밋을 새로 만들고, 그 SHA로 jar 대조를 다시 해야 합니다.** 이 표의 기대값은 81b0e7a7 단독 배포에만 맞습니다.

## 4. 배포·복구안 초안 (실행 금지 — 총괄 승인 전, 블록 형식 아님)

D4처럼 단계별 실행 블록으로 만들지 않았습니다. 승인되면 D4의 형식(멈춤·되돌림 문구 구분 포함, v1.149에서 수용한 정정 안내 반영)으로 따로 만듭니다.

1. **운영 기점 확인(읽기만):** HEAD b5cd1d0, 실행 이미지 52e12428, 설치 c669, cron 불변, Flyway 최고 V94, DYNAMIC RUNNING 세션 수와 최근 240분 BLACK_SWAN 로그(종류별 수)를 기록합니다.
2. **보존 태그:** 현재 이미지를 되돌림 태그로 보존합니다(D4와 같은 방식).
3. **체크아웃·빌드:** 81b0e7a7에서 bootJar를 만들고, 운영 쪽 jar와 위 §2를 대조합니다.
   - 기대값: 항목 734 = 734, 내용 차이는 DynamicTradingService 계열 7개와 모듈 jar 3개(시각만)입니다.
   - 다르면 멈추고 되돌림 없이 보고합니다(체크아웃 뒤 멈춤 → 그 멈춤 문구가 "되돌림"이라고 할 때만 되돌림).
4. **재기동:** 앱만 재기동합니다(DB 무변경). 기동 로그에서 BLACK_SWAN 복원 줄을 종류별로 확인합니다(REAL n건, PAPER m건).
5. **사후 관찰(읽기만):** 다음 DYNAMIC 회차에서 REAL·PAPER의 BLACK_SWAN 차단 사유가 각자 자기 기록에서만 나오는지 확인합니다. 연결 획득 실패·누수 0을 확인합니다.
6. **복구 기준(되돌림 필요):**
   - REAL 쪽 BLACK_SWAN 자기 보호가 사라짐: REAL 급락 기록 뒤 같은 코인 REAL 진입 허용
   - 기동 실패
   - DYNAMIC 회차 예외 반복
   - P0(연결 획득 시간 초과)는 보고하며, 되돌림 여부는 총괄이 판단합니다.
   - **복구는 보존 태그 52e12428로 앱만 되돌립니다.** DB 변경이 없으므로 데이터 되돌림은 없습니다.
   - 되돌린 뒤에는 종전 공유 지도 동작(교차 간섭 포함)으로 돌아갑니다.
7. **자동 되돌림은 없습니다.**

## 5. 파일

- `jar_compare.py`, `nested_compare.py`: 대조 스크립트(읽기만)
- `jar-compare.json`, `nested-compare.json`: 결과
- `inner-classes.txt`: 내부 클래스 6개의 바이트코드·줄번호표 차이 수
- `outer-members-diff.txt`: 바깥 클래스 멤버 서명 차이(`javap -p` 정렬 diff)
