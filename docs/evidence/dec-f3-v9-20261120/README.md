# 12월 준비 29 — F3 v9 형식 결함 오류 처리 (총괄 v1.207 §3-1) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 총괄 [v1.207 §2](../review-170395d3/README.md)의 좁은 보완만 했다. 이번에 PG·DB·제품 회귀·빌드·운영 접속은 하지 않았고, 기존 15건·8건이나 성적표/P7 전체 시험도 다시 돌리지 않았다.

## 1. 바꾼 것

[f3v9/f3.py](f3v9/f3.py)는 v8([원문 보존](../dec-registry-p7v8-20261120/p7v8/f3.py))의 사본이다. 바꾼 곳은 두 군데다.

1. `shape()`에 중첩 `exchange_coverage.window`가 객체인지 확인하는 항목을 더했다.
2. `judge()`는 `shape()`가 결함을 하나라도 찾으면 **뒤 필드에 접근하기 전에** `(False, 사유)`를 반환한다.

예외를 통과나 빈 목록으로 바꾸는 전체 `try/except`는 넣지 않았다. CLI 종료값은 그대로다: 0 통과, 1 불통과(유효한 JSON의 형식 결함 포함), 2 읽기/JSON 실패.

부수 효과: 형식 결함이 있는 입력의 사유 목록에는 형식 사유만 남는다. 예를 들어 `anomalies` 비어 있지 않음·`overall` 모순은 그대로 나오지만, v8이 뒤에 덧붙이던 "전체 판정 …" 줄은 빠진다. 불통과 판정 자체는 같다.

## 2. 직접 파일 대조

[probe_v9.py](probe_v9.py)는 P7 v8 정상 P0 출력(`cases_v8.ev(V8, P0)`)을 기준으로 삼는다. 이 기준과 총괄의 결함 9종을 각각 JSON 파일로 쓰고 `python -I f3.py --p7 <파일>`로 판정한다. 손상 파일 3종도 같은 방식으로 넣었다. 결과는 [probe-v9.out.json](probe-v9.out.json)에 있고 **13/13**이다.

| 입력 | v8 (총괄 대조) | v9 종료값·결과 |
|---|---|---|
| 정상 P0 | 통과 | 0 통과, 사유 없음. v8 `judge` 결과와 같음 |
| `code`·`exchange_coverage`·`account`·`account_sum` = `["x"]` | AttributeError | 1 불통과, "<필드> 누락 또는 타입 불일치(dict 필요)" |
| `exchange_coverage.window` = `["x"]` | AttributeError | 1 불통과, "exchange_coverage.window 누락 또는 타입 불일치(dict 필요)" |
| `anomalies`·`holds`·`observation`·`observation_stable` 결함 4종 | 불통과 | 1 불통과(형식 사유) |
| JSON 아님 / UTF-8 아님 / 파일 없음 | — | 2 읽기 실패 |

이 대조는 파일 내부의 일관성만 검사한다. 수작업으로 꾸민 파일의 진위나 운영 계좌 일치를 보증하지 않는다. PG 기록은 여전히 3 PASS / 1 FAIL이다.

## 3. 보존

`.gitattributes`(`* -text`)와 [SHA256SUMS](SHA256SUMS)를 두었다. 기존 v8 폴더는 바꾸지 않았다.
