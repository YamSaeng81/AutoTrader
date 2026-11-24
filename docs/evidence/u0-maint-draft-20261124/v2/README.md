# U0 유지보수 보정 2판 — 필수 기록 실패 보완 (총괄 v1.240 §3-1, 초안)

2026-11-24 KST, Claude Code 로컬 작업이다. **U0·U1·U2는 실행하지 않았다.** 실제/가짜 SSH, 생산 별칭, PATH 치환, PG는 0회다. 1판([U0-maint.sh](../U0-maint.sh))과 원래 U0는 바꾸지 않았다. **이 사본은 실행 출처가 아니다.** `MAINT4`·`M4GATE`는 빈칸이다.

## 1. 먼저 — 진단 읽기 결과가 바꾼 판단

[1446 진단](../../r1-diag4-read-20261124-1446/README.md)에서 R1 DYNAMIC 9건의 마지막 만료 경계가 **11-27 08:30:10.038Z(다음 분 08:31Z = 17:31 KST)**로 나왔다. 앞서 쓴 상한 12-01보다 4일 이르다. 그래서 유지보수 예외로 얻는 시간이 약 3일로 줄었다.

**권고: 자연 만료 경로를 기본으로 한다.** 11-27 08:31Z 뒤의 새 재확인 읽기(별도 승인)로 R1=0을 관측한다. 그다음 원래 U0를 쓴다. 이 2판은 재확인에서 R1이 남거나 일정상 만료 전 배포가 꼭 필요할 때의 대비안으로만 둔다. 실행 출처는 총괄이 정한다.

## 2. 보정 2판 — [U0-maint2.sh](U0-maint2.sh) `756ba396`

[생성기](make_u0_maint2.py)는 원래 U0(`91ea9342`)의 106~107행과 109행만 바꾼다. 차이는 [원래 대비](U0-vs-U0-maint2.diff)·[1판 대비](U0-maint-vs-U0-maint2.diff)에 있고, [bash -n](bash-n.txt) rc0이다.

1판의 틈과 2판의 처리:

| 필수 기록 | 1판의 틈 | 2판 |
|---|---|---|
| predeploy-verdict.txt | 리디렉션이 실패하면 종료 1이 되어 "보수적 기준 미충족"(비용 미확정)으로 읽힌다. **원래 U0도 같다** | pf3ok 종료 0/1에 맞는 `판정 |` 줄이 파일에 없으면 **판정 기록 실패**로 멈춘다 |
| r1-gate.txt (pf3ok 0 경로) | `echo | tee` 실패를 명시적으로 잡지 않는다(set -e에 기댐) | 쓴 뒤 다시 읽어 내용이 같아야 한다. 아니면 기록 실패로 멈춘다 |
| r1-diag-4.tsv/.stderr | 출력 파일 열기 실패가 "조회 실패"로 읽힌다 | 파일이 만들어지지 않았으면 기록 실패(조회 실패·대상 0 아님) |
| 형태 검사 출력 | 실패가 "형식 이상"으로 읽힌다. `-s`는 같은 이름의 디렉터리에도 참이다 | 일반 파일이고 비어 있지 않아야 한다. 아니면 기록 실패 |
| r1-gate.txt (유지보수 경로) | 판정기 출력 실패가 종료 1 = "조건 미충족"으로 읽힌다 | 종료값에 맞는 첫 낱말(MAINT_DEPLOY_ONLY/STOP/FORMAT)이 있어야 한다. 아니면 기록 실패 |
| 화면 표시·ok-U0 | 확인 없음 | `cat` 실패, ok-U0 생성 뒤 부재 → 멈춤 |

기록 실패는 모두 "ok-U0를 만들지 않는다(U1 금지)"로 멈춘다. 비용 미확정으로 취급하지 않는다. `|| true`는 없다. 한계: 출력 도중의 쓰기 오류(디스크 가득 등)는 psql이 비영 종료하므로 "조회 실패"로 분류된다. 그 경우에도 ok-U0는 없다.

## 3. 파일 대역 4건 — [runs/file-4e](runs/file-4e/summary.json) 4/4

[test_u0m2.py](test_u0m2.py)가 U0-maint2에서 `# ── v1.240` 줄부터 ok-U0 줄까지만 잘라 로컬 bash로 돌린다. 앞에 붙이는 것은 다음뿐이다.
- U0의 `stop()`
- 실행 폴더 U변수.sh의 `h256()`·`pf3ok()` 원문
- DB 호출 대역 `DC=stubdc`(정해진 출력 파일을 cat)
- 윈도 보정 둘: 출력 인코딩 UTF-8, python3 출력의 CR 제거

`MAINT4`·`M4GATE` 두 줄만 시험용 값(64a34eec·그 커밋의 판정기 해시)으로 바꾼다.

| 사례 | 결과 |
|---|---|
| 정상 예외 — pf3ok DYNAMIC 9 + **실제 1446 운영 출력** | 종료 0, ok-U0 있음, `MAINT_DEPLOY_ONLY … KNOWN 9 … 마지막 만료 경계 2026-11-27T08:30:10.038383Z` |
| r1-gate 쓰기 실패 — pf3ok 0 경로, 그 자리에 디렉터리 | 종료 1 `r1-gate 필수 기록 실패(pf3ok 0 경로)`, ok-U0 없음 |
| 출력 기록 실패 — predeploy-verdict.txt 자리에 디렉터리 | 종료 1 `배포 전 판정 기록 실패`, "기준 미충족"으로 읽지 않음, ok-U0 없음 |
| 새/불명 분기 — 격리 PG minute_edge 출력(KNOWN 2·OTHER 1) | 종료 1 `MAINT_STOP`(P3 OTHER ≠ 0) → 조건 미충족, ok-U0 없음 |

**앞 회차 보존(환경 원인, 결과로 쓰지 않음):**

| 회차 | 내용 |
|---|---|
| [file-4](runs/file-4/summary.json) | 윈도 python 표준 출력이 cp949라 pf3ok가 🔴를 쓰다 예외로 끝났다. 2판은 이를 판정 기록 실패로 멈췄다. 원래 U0였다면 종료 1이 "기준 미충족"으로 읽혔을 경우다 |
| [file-4b](runs/file-4b/summary.json) | 인코딩은 고쳤다. 그러나 윈도 python이 CRLF로 써서 판정기가 pf3ok 줄 형식을 거절했다(종료 3). 서버 리눅스는 LF다 |
| [file-4c](runs/file-4c/summary.json) | CR 제거 함수에 CR 문자를 직접 넣었다. Git Bash가 스크립트를 해석하며 그 문자를 지워 효과가 없었다 |
| [file-4d](runs/file-4d/summary.json) | 시험기 수정이 적용되지 않은 채 같은 조건으로 한 번 더 돌았다(4c와 같은 결과). 작업자 실수다 |

판정기·U0-maint2 원문은 이 회차들 사이에 바뀌지 않았다. 각 summary의 u0m2_sha256이 같다. 바뀐 것은 시험기의 환경 보정뿐이다.

## 4. KNOWN 분류 조이기 — 차이만 제출(PG 미검증, 실행 안 함)

v1.240 §1대로 지금 KNOWN은 주문 0·exit_reason NULL을 요구하지 않는다. 필요하면 r1-diag-5에서 KNOWN 술어(r1-diag-4.sql `tg` CTE의 P 분기)에 두 줄을 더한다.

```diff
                   AND c.t >= x.known_lo AND c.t < x.known_hi
+                  AND NOT EXISTS (SELECT 1 FROM "order" z WHERE z.position_id = c.pid)
+                  AND (SELECT p2.exit_reason FROM position p2 WHERE p2.id = c.pid) IS NULL
                   AND NOT EXISTS (SELECT 1 FROM sa WHERE sa.pid = c.pid), false)
```

META 식별자도 `r1-diag-5`로 바뀐다. 나머지 출력 계약과 검사기 관계식은 그대로다.

최소 검증 범위(격리 PG, 새 승인 필요):
1. 확정 체결 없이 미확정 BUY 행만 있는 과거 NO_SOURCE 포지션 → OTHER
2. exit_reason이 있는(예: ENTRY_REJECTED·size≠0) 과거 NO_SOURCE 포지션 → OTHER
3. 기존 normal·minute_edge 기대값 불변

**그래도 동일 행 증거는 아니다.** 자연 만료를 택하면 이 조이기는 필요 없다. 그래서 지금은 작성하지 않았다.

## 5. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
