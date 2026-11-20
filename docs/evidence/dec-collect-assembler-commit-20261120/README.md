# 12월 준비 33 — 조립기 기록 확정 보완 (총괄 v1.209 F1) — 파일 입력만, 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [v1.209](../review-a926727c/README.md) §3-1·3-2에 따라 옛 조립기 7717c667의 **기록 확정 순서만** 새 사본에서 고쳤다. SQL 9818db54·파서 0f6fe827·P7/F3·제품 원천은 바꾸지 않았다. 옛 조립기의 결함 재현은 [총괄 주입 결과](../review-a926727c/write-failure.json)에 연결하며 반복하지 않았다.

## 1. 바꾼 것

[assemble.py](assemble.py) sha256 **ff8549346014095c5cd33ba297f187966fee39f449a6b953755b8ae905447472**. 옛 사본과의 차이는 `finish()`·`verify_committed()`·`--verify` 진입점·상수·문서 주석뿐이다.

- **확정 순서(마지막 확정 방식):** `bundle.json.uncommitted` 기록 → 폴더 전체 해시로 `SHA256SUMS` 기록(미확정 파일은 `bundle.json` 이름으로 계산) → **마지막 단계 `os.replace`로 `bundle.json` 생성**. 이 앞의 어느 쓰기가 실패해도 `bundle.json`은 생기지 않고 `bundle.json.uncommitted`가 미완료 흔적으로 남으며 종료값은 42다. 실패 사유를 다시 쓰는 방식에 기대지 않는다.
- **소비 조건:** 다음 단계는 `python -I assemble.py --verify <폴더>`(0 소비 가능 / 44 거절) 또는 `verify_committed()`를 통과한 묶음만 쓴다. 조건: 미확정 흔적 없음, `bundle.json`·`SHA256SUMS` 존재, 체크섬 목록 = 폴더 파일(자기 제외)이고 모든 해시 일치, `bundle.json`의 `commit` 규칙·`rc 0`·`db_extract_partial`·`db_part_only`·세 확정 표시 false. 종료값이나 `bundle.json` 내용만으로 판단하지 않는다.
- 출력 폴더는 옛 사본과 같이 `os.mkdir`로 새로 만들고 기존 경로는 40으로 아무것도 쓰지 않는다. 자동 삭제·재사용 없음.
- **남은 한계:** `bundle.json.uncommitted` 안에는 확정 예정 내용(rc 0이면 rc 0)이 그대로 있다. 이름과 소비 조건으로 거절되며 그 파일을 직접 성공 묶음으로 읽는 경로는 계약 밖이다.

## 2. 직접 영향 3건 — 3/3 (첫 실행)

[tests_commit.py](tests_commit.py)(sha256 a163ce1d…0bce71). 입력은 옛 [run1/inputs/normal](../dec-collect-assembler-20261120/run1/inputs/normal/) 보존 원문(시계는 합성)만 썼다. 결과: [run1/results.json](run1/results.json). 출력 경로 `run1`은 호출 전에 정했고 처음 한 번에 3/3이었다(삭제·재실행 없음).

| 사례 | 주입 | rc | 확인 |
|---|---|---|---|
| 정상 대조 | 없음 | 0 | 원문·파서 결과 9파일이 옛 [run1/normal](../dec-collect-assembler-20261120/run1/normal/)과 바이트 동일, `bundle.json`은 `commit` 키 추가 외 동일. 미확정 흔적 없음, `--verify` 0 |
| 체크섬 쓰기 실패 | `SHA256SUMS` 쓰기 PermissionError(총괄과 같은 지점) | 42 | `bundle.json` 없음(직접 열기 FileNotFoundError), `bundle.json.uncommitted` 남음, `--verify` 44 |
| 마지막 확정 실패 | `bundle.json`으로의 `os.replace` PermissionError | 42 | 체크섬은 써진 뒤 실패. `bundle.json` 없음, 흔적 남음, `--verify` 44 |

실패 주입은 프로세스 안의 함수 대체이며 실제 디스크 고갈·운영 실행이 아니다. 기존 7경계·PG·psql·제품 회귀·빌드·운영 접속은 반복하지 않았다.

## 3. 458차 첫 실행 기록 정정

458차 [조립기 README](../dec-collect-assembler-20261120/README.md)의 첫 4/7 실행은 **경위 설명만 있고 사례/콘솔 원문은 보존되지 않았다.** 그 뒤 run1을 지우고 다시 실행했으므로 run1에는 최종 7/7만 남는다. 첫 회차를 재구성·재실행으로 대신하지 않는다. 이번부터 호출 전 고유 경로를 정하고 실패 회차도 지우거나 덮어쓰지 않는다.

조립기 준비는 운영 읽기 승인이 아니다. 다음 단계 입력 사용 여부는 총괄 판정을 따른다.
