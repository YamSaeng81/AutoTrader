# W4·W6 보완 — 주문 원천 누락(R1)·모듈 종료값 무시(R2) (총괄 v1.213 §4)

2026-11-21 KST, 개발 세션 제출(총괄 판정 전). 지시: [v1.213](../review-838029ab/README.md) §3·§4. 원본 [weekend-local-20261120](../weekend-local-20261120/README.md) 581개 파일과 체크섬은 바꾸지 않았다(보완 후 `sha256sum -c` 581/581 OK).
**모든 입력은 합성이다.** 운영·SSH·psql·DB·HTTP·거래소·Telegram 호출, 생성/시작 요청, 제품 Java·운영 SQL·설치본·RUNBOOK·read.sh 수정은 없었다. 기존 38사례·W6 4사례·PG·제품 회귀·빌드는 다시 돌리지 않았다.

## 1. 원본 재사용 방식

[candidate/orig.py](candidate/orig.py)가 원본 후보 파일을 해시로 확인한 뒤에만 불러온다(build_monthly 1b7739b3·run_offline 59b80aa5·pins 674e746e, 그 밖의 사례 생성기는 원본 SHA256SUMS 값). 원본 파일을 복사해 고치지 않았다. 고정 변환기 v4(f63a857f)·성적표 v5(b95acebd)·P7 v8·F3 v9도 원본 pins 를 거쳐 그대로 쓴다.

## 2. R1 — W4 주문 원천 ([build_monthly_r1.py](candidate/build_monthly_r1.py), w4-3)

| 입력 | 이전(w4-2) | 보완 |
|---|---|---|
| `orders: null`(원천 없음) | 종료10이지만 카드 실현·수수료·왕복·승패 0, `거래0`, 항등식 '불일치 989.5' | 세션은 산출. 주문 의존 값(실현·수수료·매수대금·왕복·승패·미실현 증감·항등식·거래 상태)을 **미확정(주문 원천 없음)**으로, `거래0` 집계에서 뺌. 경계로 확정된 월손익 989.5·묶음 합은 유지 |
| 참조 파일에 `orders` 키 없음 | **종료0 BUILT_CORE_CONFIRMED**, 확정0 카드 | 그 세션만 `ORDERS_KEY_MISSING` 거절(PARTIAL 20) |
| `orders` 가 목록 아님 | (`.get` 으로 통과 가능) | `ORDERS_TYPE_INVALID` 거절 |
| 명시적 `orders: []` | 거래0 확정 | 그대로(거래0 확정) |

세션이 거절되거나 변환기 계약 위반으로 빠지면, 그 세션이 속했을 묶음(돈 종류·종류·모드·전략/주기·코드)은 확정 월손익을 내지 않고 확정 부분 합과 제외 세션을 남긴다. 실자금 묶음이 미확정이면 계좌−실자금 차이도 '미확인'이다. 가짜 주문 행은 넣지 않는다. Markdown은 성적표 v5 `render_md` 원문으로 만든 뒤 원천 없는 세션 행의 수수료 칸만 '미확정(주문 원천 없음)'으로 고친다.

**결과** [r1-run2](runs/r1-run2/cases.out.txt) **4경계·5변형 5/5**: B1 원천 없음 10 · B2a 키 없음 / B2b 타입 20 · B3 빈 주문+다른 정상 세션 0(LIVE203 거래0·DYNAMIC900 −509.75, 실자금 −509.75 확정) · B4 한 세션 변환 실패+정상 세션 20(LIVE203 989.50·실현 989.50·수수료 10.50·항등식 일치 유지, 실자금 묶음 미확정·확정 부분 합 989.5·제외 DYNAMIC#900, LIVE 묶음은 확정). 기대 금액은 원본 cases_w4 주석의 손 계산. B4가 제출 때 미실행으로 적은 세션 격리 경로다.

## 3. R2 — W6 종료값 계약 ([run_offline_r2.py](candidate/run_offline_r2.py), w6-2)

모듈마다 **실제 프로세스 rc = JSON `exit` = 모듈 종료값 표[verdict]** 를 확인한 뒤에만 원본 `classify()` 로 분류한다. 표는 원본 후보 5개의 `EXIT` 를 해시 확인 뒤 읽어 대조한다. JSON 없음/읽기 실패는 `OUTPUT_INCOMPLETE`, 불일치는 `EXIT_CONTRACT_MISMATCH` 로 FAILED. 깨진 JSON은 실패 원문으로 남기고, 계약이 깨진 W3의 P7 출력은 W2 계좌 인용으로 넘기지 않는다(p7.json 미생성). 모듈별 stdout·stderr·rc를 남긴다. W1·W2·W3·W5는 원본 파일 경로를 그대로 부르고 W4만 R1 보완을 부른다.

**결과** [r2-run2](runs/r2-run2/cases.out.txt) **3경계·5변형 + 정상 통합 1회 = 6/6**. 실패 종료값은 함수 값 수정이 아니라 실제 OS 프로세스로 만들었다(사례 폴더의 감싸기 스크립트가 원본 모듈을 실행한 뒤 종료값/JSON만 바꿈 — 실행기 명령줄에는 모듈 경로 변경 옵션이 없고 시험용 인자로만 넘김).

| 사례 | 결과 |
|---|---|
| E1a W1 SET_MATCH + 실제 rc42 / E1b W3 F3_PASS + 실제 rc40 | FAILED(EXIT_CONTRACT_MISMATCH), 전체 FAILED 20. E1b는 W2에 P7 인용 없음·인용 OK 표시 없음 |
| E2a 실제 rc0 + JSON MISMATCH / E2b 실제 rc0 + JSON 없음 | FAILED(EXIT_CONTRACT_MISMATCH / OUTPUT_INCOMPLETE) |
| E3 원본 W3 + DB 부분 묶음 → 계약대로 종료10 | **NEEDS_DATA**(FAILED 로 올리지 않음), 전체 10 |
| N1 정상 통합(R1 W4 포함) | NEEDS_APPROVAL 5, 모든 모듈 계약 OK, W2가 W3 P7 인용, 운영 준비 false |

## 4. 개발 중 시험 결함 (실패 원문 보존)

| 실행 | 결함 | 수정 |
|---|---|---|
| [r1-run1](runs/r1-run1/cases.out.txt) 4/5 | B1 Markdown 검사가 표시 칸 문구 '거래0 아님'을 금지 문자열로 잡음(도구 산출은 정상) | 칸 번호(거래·실현·수수료)로 대조 |
| [r2-run1](runs/r2-run1/cases.out.txt) 5/6 | E3에 묶음 폴더 대신 `bundle.json` 파일 경로를 넘김 → W3 BUNDLE_REJECTED 30(실행기는 계약 일치로 FAILED 판정) | 폴더 경로로 수정 |

두 결함 모두 시험 입력/검사 쪽이며 보완 도구 코드는 바꾸지 않았다.

## 5. 한계와 다음

- W2는 P7 인용이 없어도 자기 위험 분류만으로 NO_RISK_ITEMS 를 낼 수 있다(E1b·E3). 계좌 일치는 W3 결과로만 판단하며 전체 분류는 W3에서 막힌다.
- 고정 성적표 v5 자체는 주문 원천 없음을 모르므로, 성적표 v5를 단독으로 쓰는 경로에는 이 보완이 적용되지 않는다. 월 성적표는 build_monthly_r1 경로로만 만든다.
- 운영 단계별 다음 승인은 원본 [README](../weekend-local-20261120/README.md) §5와 같다(d81 배포·N03 ID 읽기 설치·P7 전체 관측·금액/전략 결정·시작 각 1건). 이 보완 완료는 운영 설치 승인이 아니다.

체크섬: [SHA256SUMS](SHA256SUMS)(이 폴더 전체, SHA256SUMS 자신 제외).
