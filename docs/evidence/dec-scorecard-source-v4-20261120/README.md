# 12월 준비 26 — 성적표 S1·S2 보완 (총괄 v1.204 §2·§3-2, v1.206 §4-1) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [v1.206 지시](../review-read-gate-v3-20261120/README.md) §4-1에 따라 [v1.204](../review-43d729db/README.md) §2의 S1·S2를 새 사본에서 고쳤다. 원본 [v3 변환기·v4 성적표](../dec-scorecard-source-v3-20261120/README.md)와 run1(24/24)은 그대로 둔다. **DB·PG·제품 시험·빌드·운영 접속은 하지 않았다.** 기존 24건은 다시 돌리지 않았다.

| 파일 | 원본 | 변경 |
|---|---|---|
| [convert.py](convert.py) (변환기 v4) | v3 `convert.py` | **S1** `session_input`: `created_at`(app 축)과 기간 축의 관계(`related`)가 없으면 기간 안 생성 여부를 `미확정`으로 둔다. 새 세션·기초0을 확정하지 않고 기초 포착도 쓰지 않는다. 성적표 입력은 이 상태를 표현하지 못하므로 그 세션의 `created_at`·`start`를 넘기지 않는다. 그래서 기초·기간 손익만 미확정이 된다. 보고서 `creation`에 원래 시각과 사유를 남긴다. 거래·다른 세션은 계속 계산한다. 관계가 있으면 v3와 같다 |
| [scorecard/scorecard.py](scorecard/scorecard.py) (성적표 v5) | v3 폴더 `scorecard/scorecard.py`(v4) | **S2** `trade_metrics`: 매도 정산 시각이 없어 월 소속을 모르는 거래는 `round_trips_unconfirmed`에 넣는다. 그래서 전체 왕복·승·패는 `None`이 된다. `*_known_part`는 확인된 다른 거래의 합을 그대로 보존한다. 확정 매도 수량이 매수보다 적으면 미종료가 확정이므로 미확정에 넣지 않는다 |

## 시험 ([cases.py](cases.py) → [run1/cases.out.txt](run1/cases.out.txt): 합계 8 통과 8 실패 0)

- RED 2건: 원본이 총괄 탐침의 S1(축 관계 없음인데 `started_in_period=true`·기초0)과 S2(실현 미확정인데 왕복0·승0 확정)를 그대로 내는 것을 먼저 고정했다.
- GREEN 5건: S1 미확정·세션 수 유지·같은 축이면 기존 동작, S2 미확정·부분 합 보존·미종료 확정은 미확정에 넣지 않음.
- 대조군 1건: 보존 정상 원천에서 새 세션 카드가 v3 run1 `scorecard.json`의 `sessions`와 같다.
- 한계: 원천 표본은 세션 1개다. "다른 세션 계속"은 세션 수 유지만 확인했다. 실제 축 관계·시계 원천이 미확정인 칸은 그대로 미확정이다. 공통 시계를 거짓으로 선언하거나 임의 숫자를 넣어 통과시키지 않았다.

사용: `python -I cases.py --out <새 폴더>` (기존 폴더면 멈춤). 체크섬은 [SHA256SUMS](SHA256SUMS).
