# docs/ 색인

> 2026-09-15 작성. 이 저장소의 문서는 6개월치가 쌓이면서 **현행 문서와 과거 기록이 한 폴더에
> 섞여** 있었다. 실제로 그 때문에 구조 리뷰에서 낡은 수치를 근거로 서로 다른 결론이 나왔다.
> 이 색인은 각 문서를 **지금 믿어도 되는가** 기준으로 분류한다.

## 가장 먼저 볼 것

| 알고 싶은 것 | 문서 |
|---|---|
| 지금 무엇을 해야 하나 | [`NEXT.md`](NEXT.md) |
| 현재 운영 상태 | [`PROGRESS.md`](PROGRESS.md) |
| 저장소 구조 전체 | [`../PROJECT_STRUCTURE_ANALYSIS.md`](../PROJECT_STRUCTURE_ANALYSIS.md) |
| 코드를 고치기 전 제약 | [`ENGINE_PARITY.md`](ENGINE_PARITY.md) |

---

## 1. 현행 — 최신 상태를 반영한다

| 문서 | 내용 |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | 최근 작업 이력 · 보류 항목 · 운영 맥락 |
| [`NEXT.md`](NEXT.md) | 다음에 할 일 |
| [`ENGINE_PARITY.md`](ENGINE_PARITY.md) | 4엔진 정합성 매트릭스와 사고 이력 |
| [`KILL_CRITERIA.md`](KILL_CRITERIA.md) | 전략 중단 기준 (`KillCriteriaConfig.java` 가 인용) |
| [`CHANGELOG.md`](CHANGELOG.md) | 무엇이 언제 어떻게 바뀌었는지 — **"이거 고쳐졌나?" 의 답은 여기** |

## 2. 참조 — 유효하지만 시점·범위 한계가 있다

각 문서 최상단에 그 한계를 배너로 적어 두었다. 열면 바로 보인다.

| 문서 | 한계 |
|---|---|
| [`DESIGN.md`](DESIGN.md) | 2026-03-05 초기 설계. 설계 *의도* 용도 |
| [`SINGLE_STRATEGIES_GUIDE.md`](SINGLE_STRATEGIES_GUIDE.md) | 2026-09-15 보강 — 전략 14종 전부 수록 |
| [`Strategy/`](Strategy/) | 2026-09-15 보강 — 등록 프리셋 14종 전부 수록. `COMPOSITE_BREAKOUT.md` 의 `_VD` 는 실존하지 않는 전략 |
| [`Deploying operating servers.md`](Deploying%20operating%20servers.md) | 2026-03 작성. 실제 구성은 `docker-compose.prod.yml` 우선 |
| [`DESIGN-short-futures.md`](DESIGN-short-futures.md) | 숏/선물 **미구축** 설계 스케치 |

## 3. 코드가 인용하는 과거 문서 — 옮기거나 이름을 바꾸지 말 것

낡았지만 **소스 코드 주석이 안정적 식별자로 참조**하고 있어 제자리에 둔다.

| 문서 | 인용 형태 | 주의 |
|---|---|---|
| [`20260415_analy.md`](20260415_analy.md) | `Tier N §M` — 소스 **20개 파일** | 지적 18건은 **전부 해소됨**. 현재 문제로 읽지 말 것 |
| [`20260415_sharpe_audit.md`](20260415_sharpe_audit.md) | `MetricsCalculator.java` | 지적된 Sharpe 오류는 **수정 완료** |

## 4. 이력 — 현재 동작의 근거로 쓰지 말 것

| 위치 | 내용 |
|---|---|
| [`old_progress.md`](old_progress.md) | 2026-08-06 이전 상세 이력(472KB). **본문이 스스로를 현행 PROGRESS 라 칭하므로 주의** |
| [`archive/`](archive/) | 2026-09-15 에 정리한 과거 분석·리뷰 9건 → [`archive/README.md`](archive/README.md) |
| [`old/`](old/) | 초기 개발 파이프라인 산출물 34건 → [`old/README.md`](old/README.md) |

---

## 문서를 쓸 때의 규칙

이번 정리에서 실제로 문제가 됐던 것들이다.

1. **수치를 주석·문서에 박지 않는다.** 줄 수, 개수, 클래스 수는 반드시 낡는다. 필요하면 그때
   세고, **어느 기준으로 셌는지 함께 밝힌다**(전체 줄과 공백 제외는 크게 다르다).
2. **"이게 문제다" 문서에는 해소 여부를 적는다.** 해소된 지적이 현재형으로 남으면, 이미 고친
   것을 다시 고치거나 정상 코드를 의심하게 된다.
3. **문서를 은퇴시킬 때는 파일명만 바꾸지 않는다.** 본문 첫 문단이 여전히 자신을 현행이라
   주장하면 파일명은 읽히지 않는다 (`old_progress.md` 가 그랬다).
4. **코드가 인용하는 문서는 경로·번호가 API다.** 옮기기 전에
   `grep -ra "<파일명>" --include=*.java` 로 확인한다.
