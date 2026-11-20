# 12월 준비 27 — P7 v8·F3 판정기 v8 (총괄 v1.204 §2 P1·F1~F3, v1.206 §4-1) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [v1.204](../review-43d729db/README.md) §2의 P1과 F1~F3을 [v7](../dec-registry-p7v7-20261120/README.md)의 새 사본에서 고쳤다. v7 원문과 run-v7(12/12)은 보존한다. **DB·PG·제품 시험·빌드·운영 접속은 하지 않았다.** v7 12건과 옛 형식 260건은 다시 돌리지 않았다.

| 파일 | 변경 |
|---|---|
| [p7v8/reconcile.py](p7v8/reconcile.py) | **P1** `account_sum`: 보류를 두 종류로 나눈다. 기준·이벤트 시각/경계 보류는 미배정 계산 자체를 모르게 하는 보류다. 부분 체결 예약·CLOSING 보류는 합산 비교만 막는 보류다. 앞의 것만(`ublk`) 음수 이상을 막는다. 확정된 미배정 음수는 예약 보류가 있어도 `ACCOUNT_SUM_NEGATIVE_UNALLOCATED` 이상으로 남고, 보류도 같이 남는다 |
| [p7v8/f3.py](p7v8/f3.py) | **F1~F3** 판정기에 `shape()` 검사를 더했다. 검사 항목: `anomalies`·`holds`가 실제 빈 list인가, 필수 필드 8개의 타입, `overall`과 목록이 서로 맞는가, `code.base/now`가 16진 7~40자이고 서로 같은가, `base_ref/now_ref`가 있는가. 하나라도 어긋나면 불통과다. 이 검사는 파일 내부 일관성만 본다. 꾸민 파일의 진위는 보증하지 않는다 |

## 시험 ([p7v8/cases_v8.py](p7v8/cases_v8.py) → [p7v8/run-v8/cases.out.txt](p7v8/run-v8/cases.out.txt): 합계 15 통과 15 실패 0)

- 대조군 2건: P0(시험용 가상 정상 입력)에서 v8은 OK이고 F3도 통과한다. v8 출력 전체가 v7 출력과 같다.
- P1 4건: RED는 v7이 미배정 −10000을 예약 보류로 가리는 것이다. GREEN은 세 가지다. v8은 이상과 보류를 함께 낸다. 예약이 없을 때 v7·v8이 같다. 미배정 계산 자체가 불명이면 음수 이상을 확정하지 않는다. 마지막 입력은 기준 포함 선언에서 이벤트를 빼서 만든 `ACCOUNT_SUM_BASE_BOUNDARY`다. 시험 입력은 모든 축이 가상 동일 시계여서 시각 축 불명으로는 만들 수 없었다.
- F3 9건: 출력 파일 변형 8종(이상 추가, 보류 추가, code.now 변경, anomalies 누락, holds 문자열, observation_stable 문자열, now_ref 없음, base 형식 오류)을 v8이 모두 불통과시킨다. RED 1건은 v7이 앞의 3종을 통과시키는 것을 고정한다. 참고로 v7은 8종 중 7종을 통과시켰다(observation_stable 문자열만 불통과).

사용: `python -I p7v8/cases_v8.py --out <새 폴더>` (기존 폴더면 멈춤). 체크섬은 [SHA256SUMS](SHA256SUMS).
