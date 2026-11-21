# 오프라인 통합 예행 — E3_w3_contract_exit10 (합성)

- 전체: **FAILED** · 로컬 파일 검사 성공 False · 운영 준비 False

| 모듈 | 판정 | 분류 | 종료값 |
|---|---|---|---|
| w1 등록표 F1 비교 | SET_MATCH | LOCAL_VERIFIED | 0 |
| w2 병행 N03 분류 | NO_RISK_ITEMS | LOCAL_VERIFIED | 0 |
| w3 P7→F3 연결 | BUNDLE_REJECTED | FAILED | 30 |
| w4 월 성적표 | BUILT_CORE_CONFIRMED | LOCAL_VERIFIED | 0 |
| w5 DYNAMIC 계획 검증 | FORMAT_OK_NOT_APPROVED | NEEDS_APPROVAL | 0 |

## 운영 단계별 다음 승인(각 1건)

| 단계 | 다음 승인 | 근거 |
|---|---|---|
| d81 배포 | d81c3f2f 운영 배포(설치·재기동 단계별 확인) 1건 | runs/w5-run3/C5_observed_code_0e1d2a02 — 0e1d2a02 는 DynamicEntryLimits 없음·모드 비엄격(W5 DEPLOY_DOES_NOT_SUPPORT) |
| 병행 감시 설치 | 종류+ID 단위 세션·주문·포지션 상태(금액 없음) 읽기의 N03 신규 설치 1건 | candidate/parallel_report.py 입력 계약 — 설치본 real_* 는 ID 없는 합계(dec-parallel-20261118 §2-1) |
| 계좌 읽기 | P7 전체 관측 1회 수집(지문 전후·거래소 전후·코드 SHA) 승인 1건 | runs/w3-run1/C4_db_partial_bundle_only — DB 부분 묶음만으로는 추가 원천 필요 |
| 실제 생성 | 총원금·월 손실 상한·DYNAMIC 전략/배정액 사용자 결정 1건(→ 등록표 v3 실제 값·W5 승인 참조) | runs/w5-run3/R0_actual_plan_skeleton — NOT_READY(VALUE_UNDECIDED·APPROVAL_VALUE_UNDECIDED) |
| 시작 | 생성 뒤 W1 집합 일치 + W3 F3 통과 결과를 붙인 시작 승인 1건 | W1 SET_MATCH 는 F1 전체 통과 아님(별도 확인 항목 6개), W3 F3_PASS 만 계좌 통과 |
