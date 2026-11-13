# 문서 색인

현재 프로젝트 작업은 [START_HERE.md](START_HERE.md)부터 읽는다. 현재 상태·남은 작업은 [LIMITED_LIVE_TEST_PLAN.md](LIMITED_LIVE_TEST_PLAN.md)에 한 번만 관리한다. **이 색인에 있는 파일을 모두 읽을 필요는 없다.**

## 작업별 문서

| 필요한 것 | 문서 | 읽는 범위 |
|---|---|---|
| 현재 할 일·운영 대 후보 | [현재 계획](LIMITED_LIVE_TEST_PLAN.md) | 먼저 읽기 |
| 최근 결과와 수용 여부 | [PROGRESS](PROGRESS.md) · [현재 인계](CURRENT_HANDOFF.md) | 이전 세션 결과가 필요할 때 |
| 담당·완료 조건·N번호 | [11월 관리표](NOVEMBER_LIVE_UPGRADE.md) | 맡은 행 |
| 시작 가능 여부 | [준비 목록](LIVE_READINESS.md) | 현재 체크 항목 |
| F 명령 | [실행 절차](LIMITED_LIVE_TEST_RUNBOOK.md) | 필요한 F 변수/블록과 선행 조건 |
| 점검 설치·전환·결과 해석 | [OPS](OPS_DAILY_CHECK.md) | 필요한 §번호 |
| 새 코드 수용·허용 범위 | [최신 총괄 v1.165](evidence/review-9fd6c856/README.md) | 409차 독립 시험 보완·N05 후보판 수용, G1 제출 경계·회복 조사 |
| 엔진 공통 제약 | [ENGINE_PARITY](ENGINE_PARITY.md) | 변경하는 규칙/엔진의 항목 |

## 고정 계약·상세 설계 — 필요한 부분만

| 파일 | 성격·찾는 위치 |
|---|---|
| [LIVE_ENTRY_PREREG](LIVE_ENTRY_PREREG.md) | 실거래 비용·손실·선행 조건의 고정 계약. 현재 상태표와 구분 |
| [SUPERTREND_VALIDATION_PREREG](SUPERTREND_VALIDATION_PREREG.md) | 사전 등록+개입 이력. §3/4 통계·판정, §5 T0/종료, 개입번호로 검색. 전체 성과 중간 비열람 유지 |
| [LIVE_RISK_IMPLEMENTATION_REVIEW](LIVE_RISK_IMPLEMENTATION_REVIEW.md) | 10월부터의 누적 기술 검토. 첫머리 “미충족”은 당시 결론. §6.5 정지, §6.8 격리 등 인용된 절만 읽고 현재 판정과 대조 |
| [UNKNOWN_ORDER_STATE_DESIGN](UNKNOWN_ORDER_STATE_DESIGN.md) | 불명 주문·예약·정산 계약. §1.1 수동조정, §5 경로, §7 시험표. 코드가 인용하는 번호 유지 |
| [DESIGN](DESIGN.md) | 초기 설계 의도. §2 모듈, §3 스키마, §4 API, §5 주문. 현재 구현·설정은 해당 SHA의 코드/마이그레이션 우선 |
| [전략 안내](SINGLE_STRATEGIES_GUIDE.md) · [조합 전략](Strategy/COMPOSITE_STRATEGIES_GUIDE.md) | 관련 전략만 검색. 성과 승인·실전 권고로 해석하지 않음 |
| [프로젝트 구조](../PROJECT_STRUCTURE_ANALYSIS.md) | 필요한 모듈/패키지를 찾는 참조. 자동 전체 읽기 대상 아님 |

PREREG와 코드가 인용하는 설계 문서는 이번 정리에서 본문·경로·번호를 바꾸지 않았다. 정리하려고 사전 기준이나 시험 계약을 축약하지 않는다.

## 이력·분석·산출물

- `*_ARCHIVE_20261112.md`, [old_progress](old_progress.md), [CHANGELOG](CHANGELOG.md), [archive/](archive/README.md), [old/](old/README.md)는 **필요한 날짜·번호·키워드만 검색**한다. 현재 지시가 아니다.
- `docs/evidence/<결과>/README.md`는 그 결과의 근거다. README와 필요한 원문/해시만 읽으며 관련 없는 폴더는 열지 않는다. 과거 근거와 체크섬은 수정하지 않는다.
- 연구·후보 보고서는 자료 구분과 당시 종료 판정을 유지한다. 과거 Tier/배포 권고나 종료한 후보를 현재 할 일로 재사용하지 않는다.
- `graphify-out/` 같은 생성 분석물은 필요할 때만 읽는다. 토큰을 아끼려고 원본 증거를 지우지 않는다.

## 갱신 규칙

현재 상태는 계획, 담당은 관리표, 최근 결과는 PROGRESS, 명령은 실행/OPS 문서, 상세는 evidence에 둔다. 같은 판정 전문을 여러 관리 문서의 머리에 반복하지 않는다. 원래 색인과 분류·옛 안내는 [색인 보존본](DOCS_INDEX_ARCHIVE_20261112.md)에 남겼다. 전체 분량·중복·보존/추출 검증은 [문서 점검 기록](evidence/document-audit-20261112/README.md)을 참조한다.
