# 12월 준비 32 — DB 추출 부분 묶음 조립기 (총괄 v1.208 §3-2) — 파일 입력만, 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 실제 수집 **전에** 쓸 파일 조립기만 준비했다. psql·SSH·네트워크를 부르는 기능은 없다. 결과는 **DB 추출 부분 묶음**이며 P7 전체 관측·F3 통과 파일·계좌/월말 확정이 아니다(`bundle.json`의 `is_p7_observation`·`is_f3_input`·`account_or_month_end_confirmed`가 모두 false). 계약 보충은 [보충 메모](../dec-collect-contract-supp-20261120/README.md)를 따른다.

## 1. 조립기

[assemble.py](assemble.py) sha256 **7717c6671de76d8a93ac0697e1d490152f7cb0f9628746687fb08cfc3985b1bf**.

- 입력: 등록표·SQL 출력(stdout)·stderr·psql 종료값·수집기 시각 기록(`collect-clock-1`: 벽시계 `c_start,c0,c1,c_end` + 단조 ns)·고정 출처(SQL 9818db54, 파서 v3 0f6fe827 — 해시가 다르면 파서를 부르지 않음).
- 출력 폴더는 `os.mkdir`로 새로 만들고, 이미 있으면 아무것도 쓰지 않고 **40**.
- 있는 원문은 실패여도 바이트 그대로 보존 → 파서 v3를 원래 인자로 실행 → `parse_rc.txt`·`parsed.json`·stdout/stderr 보존 → 시계 검사 → `bundle.json`·`SHA256SUMS`.
- 시계 검사: 단조 순서, 벽시계 순서, 세 구간의 벽시계/단조 경과 차이 ≤ P7 `MONO_TOL` 500µs. 통과일 때만 `db_extract` 구간과 P7 형식 표본 `{pair:['db','collector'], step:'db_extract', c0, d, c1, mono_rtt_ms}`를 낸다. d는 파서의 `clock_read_at`(META 4열)이며 `now()`·과거 시각으로 외삽하지 않는다.
- 묶음에 없는 원천(`db_fp_pre/post`·`exchange_before/after`·앱 코드 SHA·거래소·등록 자본/`cash_base`·`ruleset.confirmed`)은 `not_in_bundle`로 적고 채우지 않는다.
- 종료값: 0 부분 묶음(`downstream: db_part_only`), 10 STOP, 13 파서0+시계 미확정, 20 psql 실패, 30 파서 형식 실패, 41 필수 기록 실패(원문·시각 기록 없음/형식 오류·고정 출처 불일치), 42 보존 쓰기 실패. **0 이외는 성공이 아니며 `downstream: blocked`** — 금액 변환·성적표·계좌 판정 입력을 만들지 않는다. 파서 0 결과(`parsed.json`)는 13/41에서도 원문 보존으로 남기되 차단 표시를 단다.

## 2. 직접 영향 7경계 — 7/7

[tests_assemble.py](tests_assemble.py)(sha256 3cf8d53d…8093). 보존 원문 [A_V3](../dec-read-gate-v3-20261120/run1/A_V3/)(정상)·[R3_V3](../dec-read-gate-v3-20261120/run1/R3_V3/)(STOP)만 쓰고, 수집기 시각은 **합성 값**이다. 결과: [run1/results.json](run1/results.json).

| 경계 | 입력 | rc | 확인 |
|---|---|---|---|
| 정상 | A_V3 | 0 | 표본이 P7 v8 `v_clock` 형식 검사 0건, `step=db_extract`. 계좌·월말 확정 아님 |
| STOP | R3_V3 | 10 | blocked, parsed에 세션·주문·포지션 키 없음 |
| psql 실패 부분 출력 | A_V3 앞 3행 + rc 3 | 20 | 부분 원문 보존, blocked, 표본 없음 |
| 파서 형식 실패 | A_V3 META 열 1개 제거 | 30 | blocked, 금액 키 없음 |
| 시계 이동 | c0→c1 벽시계 +2ms | 13 | 축 관계 미확정, 표본·구간 미출력 |
| 시각 기록 없음 | clock 파일 없음 | 41 | 성공 아님, blocked |
| 기존 출력 폴더 | 미리 만든 폴더 | 40 | 기존 내용 그대로, 아무것도 쓰지 않음 |

모든 사례에서 입력 바이트 = 보존 바이트, 각 묶음 SHA256SUMS 자기 대조 일치. 첫 실행은 4/7이었다: 조립기가 cp949 콘솔에 한글 JSON을 찍다 실패(출력을 ASCII 이스케이프로 수정)했고, 시험 판정식이 파서 0인 13/41 사례에서도 금액 키 부재를 요구했다(파서 rc≠0일 때만 요구하도록 수정). 수정 뒤 run1을 지우고 한 번 다시 실행했다.

PG·psql·제품 시험·빌드·운영 접속은 하지 않았다. 정상 원천 PG 결과는 계속 **3PASS/1FAIL + 별도 보충 비교**다. 조립기 준비는 운영 읽기 승인이 아니다.
