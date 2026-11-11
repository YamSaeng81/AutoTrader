# main 의 미배포 V95 → V97 번호 이동 계획·차분 (초안, 적용하지 않음) — 총괄 v1.150 §4

**이 문서는 계획과 차분 초안입니다. main 에 적용하지 않았고, 운영 DDL 승인도 아닙니다.** 적용 순서는 운영 변경 전 최종 실행 출처에서 확정합니다.

## 1. 현재 상태 (2026-11-11 읽기)

| 위치 | Flyway 최고 버전 | V95 적용 |
|---|---|---|
| 운영(마지막 수용값, 이번에 다시 읽지 않음) | V94 | 없음 |
| 고정 코인 후보 `candidate/sma20-paper-fwd-aligned2` (5b17018b) | V94 + **V96**(런타임 2열) | 파일 없음 |
| main `e820b88b` | V94 + **V95**(`V95__paper_session_slippage.sql`, 세션별 슬리피지) | 미배포 |
| 로컬 개발 DB(도커 `crypto-auto-trader-db-1`, 오래된 사본) | V49 | 없음 |
| 이번 격리 시험 DB(`v150-pg`) | V96(후보 코드로 이행) | 없음 |

- V95 를 적용한 환경은 확인한 범위 안에 없습니다. 그래서 번호를 바꿔도 이미 적용된 이력(체크섬)과 충돌하지 않습니다.
- 운영은 이번에 읽지 않았습니다.

## 2. 왜 옮기는가

V96 을 담은 후보가 먼저 운영에 적용되면, 그 DB 의 이력은 94 → 96 입니다. 그 뒤 main 의 V95 를 담은 바이너리가 오면 Flyway 는 다음처럼 동작합니다(기본값 `outOfOrder=false`, `ignoreMigrationPatterns=*:future`).

- 95 는 "해결됐지만 적용되지 않은 과거 버전"이 됩니다.
- validate 가 실패해 기동이 거절됩니다.
- 이 거절을 피하려고 `outOfOrder` 를 켜는 것은 운영 계약 완화이므로 쓰지 않습니다.

## 3. 차분 (`v95-to-v97.rename.patch`)

- 파일 이름만 바꿉니다: `V95__paper_session_slippage.sql` → `V97__paper_session_slippage.sql` (similarity 100%, 내용 무변경).
- 확인: main 작업 트리에서 `git apply --check -v` 를 통과했습니다. 실제로 적용하지는 않았습니다.
- 주석 속 "V95" 표기는 바꾸지 않습니다. 설명용 문구이며 Flyway 동작과 무관합니다. 통합 때 함께 고칠지는 별도로 정합니다.
  - 대상 주석: `PaperTradingStartRequest`, `VirtualBalanceEntity`, `PaperTradingService`, `RulesetRegistry`, `schema-h2.sql`

## 4. 통합 순서 (제안)

1. 고정 코인 후보(V96 포함)를 main 에 통합하는 커밋에서 위 이름 변경을 함께 넣습니다.
   - 결과 순서는 V94 → V96(런타임 2열) → V97(슬리피지)입니다.
2. 통합 SHA 로 다음을 수행합니다.
   - 빈 PG 에 V1 부터 끝까지 이행·validate
   - V94 기점 DB 에 V96·V97 이행·validate
   - `V96MigrationPgTest` 와 같은 방식으로 기존 행 불변 확인
3. **V96 을 V97 로 옮기는 방식만으로는 해결되지 않습니다.**
   - 그 경우 V95 가 나중 번호보다 늦게 적용될 수 있어 같은 거절이 생깁니다.
   - 운영 변경 전 최종 실행 출처에서 Flyway `info` 의 대기 목록과 순서를 확정합니다.
4. 슬리피지 기능을 이번 후보에 넣지 않는다는 결정(v1.150)은 그대로입니다. 이 차분은 번호만 다룹니다.
