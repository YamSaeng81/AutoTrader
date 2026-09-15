# docs/old/ — 초기 개발 기록

> **⚠️ 전부 과거 기록입니다. 현재 동작의 근거로 쓰지 마십시오.**
> 현재 상태는 [`docs/PROGRESS.md`](../PROGRESS.md), 구조는
> [`PROJECT_STRUCTURE_ANALYSIS.md`](../../PROJECT_STRUCTURE_ANALYSIS.md) 를 보십시오.

이 폴더는 프로젝트 초기(설계~초기 구현 단계)의 산출물 모음이다. `.claude/` 개발 워크플로우
파이프라인(SparkAI → PLAN → Design → Do → Check → Report)이 남긴 문서와, 그 시기의 전략
분석·리뷰 기록이 섞여 있다. 오래 방치되어 무엇이 왜 여기 있는지 설명이 없었으므로
2026-09-15 에 이 색인을 추가했다.

## 대략의 구성

| 갈래 | 파일 |
|---|---|
| 파이프라인 산출물 | `IDEA.md` · `PLAN.md` · `CHECK_RESULT.md` · `REPORT.md` · `REPORT_TECHNICAL.md` · `report_executive.md` |
| 단계별 구현 가이드 | `PHASE_PROCESS/` (백엔드·프런트엔드 Phase 문서) |
| 개발 현황 리뷰 | `DEV_STATUS_REVIEW.md` · `DEV_STATUS_REVIEW_v2.md` |
| 프로젝트 분석 | `project_analysis.md` · `_v2` · `_v3` · `crypto_autotrader_critical_analysis.md` |
| 전략 분석 | `strategy_analysis.md` · `_v2.1` · `_v3` · `crypto_auto_trading_strategy_review.md` · `CompositeStrategy.md` |
| 백테스트 기록 | `BACKTEST_RESULTS.md` |
| 아이디어 메모 | `someMoreIdea.txt` · `추가사항.md` · `오류사항.md` · `AI들이 말한 복합전략 수정사항.txt` |
| 기타 | `api-spec.yaml` · `손익계산식.md` · `index.ts` |

## 주의할 파일

- **`odl_pregress.md`** — `old_progress.md` 의 오타 파일명이다. 내용은 더 이른 시점의 진행
  기록. 현행 진행 상황은 [`docs/PROGRESS.md`](../PROGRESS.md).
- **`CHECK_RESULT.md`** — [`docs/archive/CHECK_RESULT.md`](../archive/CHECK_RESULT.md) 에
  **같은 이름의 다른 내용** 파일이 있다. 둘은 서로 다른 시점의 검증 결과이므로 혼동하지 말 것.

## 관련

- 2026-09-15 정리에서 `docs/` 에서 옮긴 문서: [`docs/archive/`](../archive/README.md)
- 문서 전체 분류: [`docs/README.md`](../README.md)
