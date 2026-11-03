# N10 두 곳 보완 — 빈 초과 칸·66세션 총수 분리 (2026-11-03, 계획 §5-9 41)

총괄 재검토(§5-9 40, [`evidence/n10-review-dd41660/`](../n10-review-dd41660/README.md))가 직전 후보 `dd41660`에서 재현한 실패 5종을 고친 실행 원문이다.

- 변경 파일: `scripts/ops/daily_check.sh`(파싱 두 곳), `scripts/ops/test_daily_check.py`(시험 7개 추가).
- `daily_checks.sql`은 바꾸지 않았다. sha256 `653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8`로 설치판 `8435935`과 같다.
- 보완판 `daily_check.sh` sha256(LF, 커밋 블롭과 같은 바이트): `2135e95314edeea68f6b9dfc31a1c0b0034f84b9d3cbfd52be7ee82afab9cc60`.
- 직전 후보 `dd41660`: `05889ecfc34741fd76e0e378b8ad204bbb807427ed25b7f7aebec04cc799d977`. 설치판 `8435935`: `a6ddc54994355066b940c276d98d7396e501aad3cce31004475ca35574d4d657`.
- 이 근거를 담은 커밋의 SHA는 이 파일에 적지 않는다(N06).

## 고친 곳

1. **칸 수는 분리 전 원문으로 센다.** `IFS='|' read -r k a b`는 끝의 빈 칸을 지워 `k|0|`를 `k|0`과 구별하지 못했다. 이제 줄을 통째로 읽어 첫 `|` 앞을 키로 본다.
   - 단일 값 키: 값 뒤에 `|`가 하나라도 더 있으면 내용이 비어도 ⚪ `칸 수 초과`(BTC 키 포함).
   - `tg_fail`: `tg_fail|` 뒤 원문을 그대로 넘긴다. 기존 `유형|정수` 검사가 빈 넷째 칸을 거부하고, 그 행은 🟡 건수로 세지 않는다. 정상 행이 여러 개인 것은 그대로 허용한다.
2. **66세션 총수 검사를 목록 검사 밖으로 옮겼다.** `s66_not_running`이 빈 값이나 중복이어도 유효하게 읽은 `s66_total`이 66이 아니면 🔴를 낸다. 목록을 모르므로 세션별 추론(지정 종료·재시작)은 건너뛴다. 총수 자체가 무효면 그 키는 ⚪로만 남는다.

## 실행 결과

### `test-new.txt` — 보완판 점검 시험 167/167

기존 160개는 기대값을 바꾸지 않고 통과했다. 새 7개:

- 빈 초과 칸: `sid_unknown_orders|0|`, `btc_h1_ok|true|`, `tg_fail|CUSTOM|1|`(+ 그 행을 🟡 CUSTOM으로 세지 않음)
- 목록 빈 값 + 총수 65, 목록 중복 + 총수 65: ⚪ 머리글·종료 1과 `🔴 검증 세션 수 65(원래 66)` 함께
- 대조군: 총수만 65(목록 정상) → 🔴 하나, ⚪ 없음

시험은 로컬 시험 DB(`cat-rt-pg`)와 모의 텔레그램만 썼고, 새 사례는 실제 시험 DB 출력의 한 줄만 `sed`로 바꿨다.

### `test-old-dd41660.txt` — 같은 시험 파일, 셸만 `dd41660` 원본 161/167

실패 6건은 모두 새 사례다(지적 5종, `tg_fail`은 하위 확인 2건). 대조군 '총수만 65'는 두 판 모두 통과했다.

### `replay/` — 총괄 실행기 20사례

[`reproduce-run1.py`](../n10-review-dd41660/reproduce-run1.py)를 그대로 쓰되 대상 셸만 작업 사본 보완판으로, 중복 사례 줄은 [`reproduce-run2.py`](../n10-review-dd41660/reproduce-run2.py)의 정정된 줄로 바꿨다. 입력·기대 판정식은 총괄 것 그대로다.

| 사례 | dd41660(총괄 근거) | 보완판 |
|---|---|---|
| `empty_third_column` | 🟢 / 0 | ⚪ / 1 — `형식 이상(sid_unknown_orders 칸 수 초과)` |
| `btc_empty_third_column` | 🟢 / 0 | ⚪ / 1 — `형식 이상(btc_h1_ok 칸 수 초과)` |
| `tg_empty_fourth_column` | 🟢 / 0, CUSTOM 1건 🟡 | ⚪ / 1 — `텔레그램 실패 행 형식 이상(CUSTOM\|1\|)`, 🟡 없음 |
| `blank_s66_total65` | ⚪ / 1, 총수 🔴 없음 | ⚪ + `🔴 검증 세션 수 65(원래 66)` / 1 |
| `duplicate_s66_total65`(run2 입력) | ⚪ / 1, 총수 🔴 없음 | ⚪ + `🔴 검증 세션 수 65(원래 66)` / 1 |

- 나머지 15사례(기존 10 + 대조군 5)도 기대대로다. `review.json`의 `meets_expected`는 20/20, 차단 대역 호출은 0이다.
- `valid_none_dash`는 총괄 README대로 잔액 치환이 적용되지 않은 none 상태 대조군이다. 잔액 `-`는 점검 시험 `n10_status_none`이 본다.
- `review.json`의 `source_sha`는 커밋 전이라 `working-tree-candidate`이고 `shell_sha256`은 위 보완판 해시다.

### `replay/prod_1000/`

운영 11-03 10:00 보고의 DB 부분 35줄(`n10-fix-parser/replay/prod_1000/input.txt`와 바이트 동일)을 같은 실행기로 넣었다. 🟢·종료 0, ⚪ 없음(`review-prod.json`). 앱·로그·경보는 대역이라 운영 보고를 다시 만든 것이 아니다.

## 범위

- 운영 서버·운영 DB·앱·cron·장부·F1~F6·최종 S·backend는 건드리지 않았다. 서버 접속도 없었다.
- 로그 분류(뉴스 Connection reset 미분류 🔴 포함)는 바꾸지 않았다.
- 시험 출력의 임시 경로와 실행 시각은 로컬 실행 값이다. 실행기 사본은 저장소에 넣지 않았다.
- 운영 설치는 후보 검토 뒤에 한다.
