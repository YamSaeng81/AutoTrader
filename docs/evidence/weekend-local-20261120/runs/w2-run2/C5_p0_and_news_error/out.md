# 병행 N03 분류 후보 보고 (합성 입력)

- 판정: **위험 항목 있음** (위험 2건)
- 계좌 일치: QUOTED — P7 OK, account_sum 일치
- 모의 522~587: 전체 66 · 실행 63 · 비실행 3

## 실자금 분류

| 분류 | 세션 | 미종결 주문 | 열린/청산 대기 포지션 |
|---|---|---|---|
| 등록 LIVE | LIVE#203:RUNNING | - | - |
| 등록 DYNAMIC REAL | DYNAMIC#900:RUNNING | - | DYNAMIC#900/KRW-ETH OPEN |
| 미등록 REAL | - | - | - |

## 위험 항목(등록 여부와 별도)

- `P0` p0_code=DB_CONN_TIMEOUT, label=연결 획득 시간 초과(합성)
- `UNCLASSIFIED_ERROR` label=NewsCollector ERROR(합성 원문 표지), count=7, note=원문 표지 그대로 — 새 분류로 옮겨 정상 처리하지 않음

## 근거 연결

- docs/evidence/dec-parallel-20261118/README.md (§2-1 real_* 3키 오경보, §2-3 영향표)
- docs/evidence/dec-parallel-registry-20261120/README.md (§1 고정 명령 충돌, §4 N03 초안)

이 보고는 설치본 N03 를 대체하지 않는다. 옛 운영 SQL 을 재실행하지 않았다.
