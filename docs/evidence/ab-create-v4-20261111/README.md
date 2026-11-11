# A/B 생성·완성·arms.csv 도구 v4 (총괄 v1.151 §3 · v1.152 A·C) — 2026-11-11

**실제 생성 승인 전 도구다.** 운영 서버·운영 DB·실제 토큰에 연결하지 않았다. 판정은 총괄 몫이다.
v3 원문(`ab-create-v3-20261111/`)과 v1.150 연결 근거는 바꾸지 않았다. v4 는 새 폴더의 새 판이다.

## 1. 바꾼 것

| 도구 | v3 문제 | v4 |
|---|---|---|
| `create/ab_create.py` | POST `startedAt` 을 Python datetime 으로 바꾸며 마이크로초 아래를 잃음 | 원문 문자열 보존(`post_started_at_raw`, `created_at` 도 같은 값). 사후 GET 목록의 같은 ID `startedAt` 원문 보존(`api_stored_started_at`). 두 값이 저장 정밀도 계약(나노초→마이크로초, 0.5 올림)으로 맞지 않으면 API 대조 실패(종료 3) |
| `create/ab_complete.py` | DB `started_at` 을 보지 않아 다른 DB·다른 회차의 같은 ID 출력도 완성 | v4 초안만 받음(옛 초안 거절, DB 값 복사로 채우지 않음). 저장 후 API 시각 == psql `started_at` 을 UTC 나노초 정수로 정확 대조, 8개 모두. 연결 출처(`--db-source`)·초안/DB 출력/프로토콜 sha256 기록. 거절이면 파일 0개. 출력 경로가 있으면 거절(`open(..., "x")`) |
| `tool/ab_arms.py` (새) | arms.csv 를 구동기 안에서 손으로 만듦 | 완성본 v4 + 프로토콜 + **완성기가 읽은 DB 출력 원문**(sha256 이 완성본 기록과 같아야 함) → 정확히 8팔 arms.csv + `.meta.json`(입력/출력 해시). `params_json` 은 `json.dumps(sort_keys=True, separators=(",", ":"))`. 쓰기 전에 집계기(v3 원문) 설정 검사 통과 필요. 덮어쓰기 거절, 실패 시 출력 0개 |
| `chain_driver_v4.py` | — | v1.150 구동기 복사본에서 v4 도구·`ab_arms` 사용, `--psql-db`(다른 실제 DB 음성) 추가, 컨테이너 `v152-pg` |

- 시각 대조는 **같은 생성 회차의 행인지 확인하는 검사**다. DB 서버 신원 인증이 아니다(완성본 `db_check.note` 에도 적음).
- 초/분 허용 오차·현재 시각 대체·옛 초안에 DB 값 복사는 쓰지 않았다.
- 규칙 지문 체계·A/B 설정·42일·비용 기준은 바꾸지 않았다.

## 2. 시험

| 시험 | 결과 | 원문 |
|---|---|---|
| v4 전용 `tool/test_ab_v4.py` | **21/21** | `runs/test_ab_v4.final.txt` |
| v3 생성기 시험 22건을 v4 생성기에 그대로 | 21/22 → 1건은 v4 가 새 키 `precision_contract_failed` 를 더한 형식 차이. 그 단언만 새 키를 포함해 **같거나 더 엄격하게** 바꾼 뒤 **22/22** | `runs/v3-tests-on-v4-before-key-update.txt`, `runs/test_ab_create.v3-suite-on-v4.final.txt` |
| 1회차 실패 | 시험 도우미 결함 — 같은 임시 폴더에서 완성본을 반복 생성해 완성기가 덮어쓰기를 정상 거절(14건 실패). 도우미만 고침 | `runs/test_ab_v4.run1-helper-defect.txt` |
| 2회차 실패 | **도구 결함** — 같은 A 팔 둘(P1A·P2A)의 ID 를 맞바꾼 완성본을 arms 도구가 통과시킴. 완성본만으로는 ID↔팔 대응을 확인할 근거가 없음 → `--db-output` 원문 재대조를 추가 | `runs/test_ab_v4.run2-swap-ids-gap.txt` |

v4 전용 시험 범위: 표기 차이 동치(Z·+00·+09:00), 시간대 없음·형식 이상 거절, 반올림 경계(…4999·…5·초 넘김), 다른 DB 같은 ID 거절, 한 행 1µs 거절, 필드 누락, 옛 v3 초안 거절, 정밀도 계약 위반 초안, created_at≠원문, 출력 덮어쓰기, 빈 연결 출처, v3 거절 유지(팔 뒤바뀜) / arms: 정상 8팔·집계기 통과·같은 입력 같은 바이트·덮어쓰기 거절·다른 DB 출력 거절, 15가지 변형 거절(초안·옛 완성본·대조 근거 없음·7팔·팔 중복·ID 맞바꿈·하한 뒤바뀜·빈 값·키 누락·문자열 params·code_sha 형식·시간대 없는 T0·시작 시각 근거 불일치·프로토콜 sha·파라미터 값) — 모두 출력 0개 / 생성기: 원문 보존, 목록 한 행 시각 어긋남 → 종료 3·추가 POST 0.

실제 로컬 PG·HTTP 연결 결과는 `overnight-v152-20261111/README.md` §A.
