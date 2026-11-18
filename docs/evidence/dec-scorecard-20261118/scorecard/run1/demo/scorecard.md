# 월 성적표 2026-12-01T00:00:00+09:00 ~ 2027-01-01T00:00:00+09:00

포함 세션 7(거래0 2·손실 2·중단 1·미확정 1)

| 세션 | 모드 | 전략/주기 | 거래 | 월손익 | 실현(주문 원천) | 수수료(반영됨) | 낙폭 | 코드 | 지문 | 표시 |
|---|---|---|---|---|---|---|---|---|---|---|
| DYNAMIC#1 | REAL | COMPOSITE_BREAKOUT/H1 | 왕복 1 | 1,949 | 1,949 | 51 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | - |
| DYNAMIC#2 | REAL | COMPOSITE_BREAKOUT/H1 | 왕복 1 | -3,000 | -3,000 | 25 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | - |
| DYNAMIC#3 | REAL | COMPOSITE_BREAKOUT/H1 | 거래0 | 0 | 0 | 0 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | - |
| DYNAMIC#4 | REAL | COMPOSITE_BREAKOUT/H1 | 왕복 1 | 500 | 500 | 0 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | - |
| DYNAMIC#5 | REAL | COMPOSITE_BREAKOUT/H1 | 거래0 | -1,000 | 0 | 0 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | - |
| DYNAMIC#20 | REAL | COMPOSITE_BREAKOUT/H1 | 왕복 0 | 미확정 | 0 | 25 | 미확인(일별 평가 표본 없음 — 복원하지 않음) | 0e1d2a02 | aaaaaaaaaaaa | DYNAMIC#20 기말 포지션 11 CLOSING — 0원 아님, 매도 확정 전 미확정 |
| DYNAMIC#8 | REAL | COMPOSITE_BREAKOUT/H1 | 왕복 1 | 1,949 | 1,949 | 51 | -4,000 일별 표본 낙폭(장중 최대 낙폭 아님) | 0e1d2a02 | aaaaaaaaaaaa | - |

| 묶음 | 이름 | 월손익 | 확정 부분 합 | 미확정 세션 |
|---|---|---|---|---|
| all | 전체 | 미확정 | 398 | DYNAMIC#20 |
| engine | DYNAMIC | 미확정 | 398 | DYNAMIC#20 |
| strategy | COMPOSITE_BREAKOUT | 미확정 | 398 | DYNAMIC#20 |

계좌: 월손익 미확정 (미확인(계좌 기초/기말 없음)) · 계좌−세션 합 미확인(계좌 또는 세션 합계 미확정)

규칙: 월손익=기말−기초−순입금 / CLOSING·가격 없음은 미확정 / 수수료는 손익에 이미 반영 — 표시만 / 손실·거래0·중단 포함 / 일별 표본 없으면 낙폭 미확인 / 없는 출처는 미확인
