# 주말 로컬 후보 W1~W6 — 병행 감시·성적표·DYNAMIC 계획의 파일 처리 구현 (총괄 v1.212 §2)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 지시: [v1.212](../weekend-work-order-20261120/README.md). 기준 커밋 9d8a012a/7e090d82.
**모든 입력은 합성 또는 보존된 로컬 파일이다.** 운영·SSH·psql·DB·HTTP·거래소·Telegram 호출, 생성/시작 요청 전송, 제품 Java·운영 SQL·설치본·RUNBOOK·read.sh·기존 evidence 수정은 없었다. 기존 P7/F3/조립기/변환기/성적표 시험·PG·제품 회귀·빌드는 반복하지 않았다.

## 1. 고정 원천 재사용

[candidate/pins.py](candidate/pins.py)가 [source-pins.json](../weekend-work-order-20261120/source-pins.json)의 sha256을 확인한 뒤에만 원천 모듈을 불러온다. 바이트코드는 쓰지 않는다. 판정식은 복제하지 않았다.

| 원천 | 해시 | 사용처 |
|---|---|---|
| P7 v8 `reconcile.py` | 156c3828 | W1·W2 `load_registry()`, W3 `evaluate()`, W4 등록표 검증, 공통 `load_file()` |
| F3 v9 `f3.py` | f825ca5e | W3 `judge()` |
| 조립기 `assemble.py` | ff854934 | W3 `verify_committed()`·`NOT_IN_BUNDLE` |
| 변환기 v4 `convert.py` / 성적표 v5 | f63a857f / b95acebd | W4 `convert()` → `build()`·`render_md()` |
| d81c3f2f `DynamicSessionRequest.java` / `DynamicEntryLimits.java` | b2a9311e / 94e54397 | W5. `git show`로 [fixtures/w5](fixtures/w5)에 추출, 도구가 해시 확인 후 필드·키·최소 주문을 읽음 |

## 2. 결과 (현재 버전 기준 실행)

| W | 파일 | 실행 | 결과 |
|---|---|---|---|
| W1 F1 비교기 | [registry_f1.py](candidate/registry_f1.py) | [w1-run2](runs/w1-run2/cases.out.txt) | **6/6 + 입력 계약 보조 2 = 8/8**. 정상(LIVE203+DYNAMIC900) 0, 개수 같고 ID 다름·미등록 추가·등록 누락·PAPER 혼입·고정 조건 불일치 20(누락/초과/고정 불일치를 종류+ID로 표시), 등록표 해시 불일치·금액 필드 30(값 미출력) |
| W2 N03 분류 | [parallel_report.py](candidate/parallel_report.py) | [w2-run2](runs/w2-run2/cases.out.txt) | **6/6**. 정상 병행 0(DYNAMIC900 ETH 매수=등록 DYNAMIC REAL), 미등록 REAL·등록 세션 UNKNOWN·CLOSING·P0+뉴스 ERROR 20, 금지 성과 필드 30(필드 이름만, 값 미출력). Markdown+JSON |
| W3 P7→F3 | [check_parallel.py](candidate/check_parallel.py) | [w3-run1](runs/w3-run1/cases.out.txt) | **5사례 6변형 6/6**. P0 정상 F3_PASS 0, 합산 일치·지문 미확인 / 잔고 이상+CLOSING F3_NOT_PASS 20, DB 부분 묶음만 NEEDS_MORE_SOURCES 10(검증 후, P7/F3 미호출), 미확정·해시 불일치 묶음 30 |
| W4 월 성적표 | [build_monthly.py](candidate/build_monthly.py) | [w4-run2](runs/w4-run2/cases.out.txt) | **6/6**, 기대 금액은 [cases_w4.py](candidate/cases_w4.py) 주석의 손 계산(예: 왕복 월손익·실현 989.50, 수수료 10.50 — 이중 차감 없음; 손실 신규 세션 −509.75). 값별 `source_map.json` |
| W5 DYNAMIC 계획 | [check_dynamic_plan.py](candidate/check_dynamic_plan.py) | [w5-run3](runs/w5-run3/cases.out.txt) | **5사례 7변형 + 실제 운용안 골격 1 = 8/8**, 요청 전송 0. 정상도 '로컬 형식 점검 완료·실제 실행 승인 전'. 현재 실제안은 NOT_READY |
| W6 통합 예행 | [run_offline.py](candidate/run_offline.py) | [w6-run1](runs/w6-run1/cases.out.txt) | **4/4**. 정상 병행 NEEDS_APPROVAL(5), 미등록 실자금·계좌 이상+보류 FAILED(20), 원가 미확정 NEEDS_DATA(10). 운영 준비 표시 0, W3 불통과 때 W2 계좌 인용도 P7 ANOMALY |

실행: `python -I candidate/cases_wN.py --run <새 이름>`(기존 폴더면 멈춤). 각 사례의 입력·출력·종료값은 `runs/<이름>/<사례>/`에 있다.

## 3. 개발 중 발견해 고친 결함 (실패 원문 보존)

| 실행 | 결함 | 수정 |
|---|---|---|
| [w4-run1](runs/w4-run1/cases.out.txt) 4/6 | 월손익만 확정이면 실현·수수료·왕복이 미확정이어도 `BUILT`(전부 확정) | w4-2: 핵심 금액 칸 전체가 확정일 때만 `BUILT_CORE_CONFIRMED`. 일별 곡선·비교 가격은 `not_provided_columns`로 따로 표시 |
| [w5-run1](runs/w5-run1/cases.out.txt) | 미정 값(null)을 형식 위반으로 섞어 실제 운용안 골격을 REJECT | w5-2: 키가 있고 null이면 VALUE_UNDECIDED(NOT_READY), 키 누락·잘못된 값은 REJECT |
| [w5-run2](runs/w5-run2/cases.out.txt) 7/8 | 상한 금액이 미정이면 포지션당 상한 출처 누락 사유도 빠짐 | 상한 출처 누락은 금액과 무관하게 SERVER_CAP_UNKNOWN |

w1-run1·w2-run1은 요약 줄·근거 경로 표기만 다른 이전 버전 실행이다(판정 동일).

## 4. 확인한 제품 사실 (d81c3f2f, 0e1d2a02 Git 원문)

- `tradingMode`는 정확히 REAL/PAPER만 받는다(DynamicTradingService.java:506-511). 0e1d2a02는 `"PAPER".equals(...)`가 아니면 REAL로 저장하고(:481) `DynamicEntryLimits`가 없다. 그래서 운영 0e1d2a02에는 이 계획을 보낼 수 없다.
- `investRatio`는 `normalizeRatio`(:3836)에서 1 초과면 ÷100, 1 이하면 그대로 비율로 쓴다. 그래서 80과 0.80은 같지만 DTO 주석(1~100)과 달리 **1은 100%, 0.5는 50%**이다. W5는 1 이하 값을 의도 확인 필요로 낸다.
- `maxOrderKrw`·`excludeCoins`는 DTO 필드가 아니라 `strategyParams` 키다. 함께 명시해야 하며, 정수 원·5,000 이상·포지션당 상한 이하여야 한다. 모르는 요청 필드는 서버가 조용히 무시하므로 W5는 미확정으로 남긴다.

## 5. 남은 경계 (W6 다음 승인 각 1건)

| 단계 | 다음 승인 | 근거 |
|---|---|---|
| d81 배포 | d81c3f2f 운영 배포 1건 | W5 C5(0e1d2a02 미지원) |
| 병행 감시 설치 | 종류+ID 단위 상태(금액 없음) 읽기 N03 신규 설치 1건 | 설치본 real_*는 ID 없는 합계 — W2 입력을 만들 수 없음 |
| 계좌 읽기 | P7 전체 관측 1회 수집(지문 전후·거래소 전후·코드 SHA) 1건 | W3 C4: DB 부분 묶음만으로는 추가 원천 필요 |
| 실제 생성 | 총원금·월 손실 상한·DYNAMIC 전략/배정액 사용자 결정 1건 | W5 R0 NOT_READY |
| 시작 | 생성 뒤 W1 집합 일치 + W3 F3 통과를 붙인 시작 승인 1건 | W1 SET_MATCH는 F1 전체 통과가 아님(별도 확인 6항목 유지) |

한계: W4의 세션 단위 격리(한 세션 계약 위반 시 나머지 계속, PARTIAL)는 구현했지만 6사례에 포함하지 않아 따로 시험하지 않았다. 통합 W4는 12월 성적표 영역이라 W1~W3의 P0(11월) 등록표와 같은 파일을 쓰지 않는다. 합성 시계 선언·승인 참조·포지션당 상한 20000은 시험값이며 운영 입력으로 쓰지 않는다. 마지막 실제 N03 읽기는 11-20 10:31 KST 회수 그대로이며, 이번 작업 중 운영 관측은 없었다.

체크섬: [SHA256SUMS](SHA256SUMS)(이 폴더 전체, SHA256SUMS 자신 제외).
