# d81c3f2f 배포·복구안 초안 + 446차 원문 보존 보충 검증 (총괄 v1.197 §1·§3)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). **초안이며 실행 승인이 아니다.** 운영 접속·DB 읽기/쓰기·SSH·빌드·시험·PG는 하지 않았다. 아래 확인은 Git 고정 객체, 저장 근거 파일, 로컬 대역 스크립트만 썼다.

## 1. 원문 보존 보충 (v1.197 §1) — 커밋 **20104a69**

- `docs/evidence/dec-final-abc-20261119/.gitattributes` = `* -text`를 새로 넣었다. 기존 근거 폴더(ab-create-*, aligned-* 등)와 같은 방식이다.
- 로컬 원문 302개를 바이트 그대로 다시 등록했다(`git add --renormalize`). 누락 로그 11개는 `*.log` 무시 규칙 때문에 `git add -f`로 넣었다. 경로는 [review-27cffb99 관측](../review-27cffb99/observations.json)의 `missing_committed_logs`와 같다.
- 바꾸지 않은 것: 기존 커밋 27cffb99, SHA256SUMS, 원문. LF 기준 체크섬은 새로 만들지 않았다. 권한 설정 파일(`.claude/settings.local.json`)도 넣지 않았다.
- 검증 결과
  - 커밋 전 색인 블롭: **355/355** 일치.
  - 커밋 20104a69에서 꺼낸 파일: `dec-final-abc-20261119/SHA256SUMS` 대비 **355/355**, 총괄 `review-e97ca31b/SHA256SUMS` 대비 **5/5**.
  - 변경 수: 추가 12(로그 11 + 속성 1), 수정 302.
  - `origin/main`에 일반 push했다(27cffb99..20104a69).
- 새 속성 파일은 기존 SHA256SUMS 목록에 없다. 별도 제출 항목으로 [SHA256SUMS](SHA256SUMS)에 적는다.

## 2. 새 고정 출처에 연결하는 값 (§3-5 1)

| 항목 | 값 | 근거·이번 확인 |
|---|---|---|
| 후보 | **d81c3f2f91c0239aacb0e1176dde9b898f4d7466** (origin `integ/final-abc-60df3437`) | v1.197 §2 로컬 포함 범위 수용. 코드는 바뀌지 않았으므로 회귀·PG·jar·프런트를 다시 실행하지 않았다 |
| 제품 경로 | 0e1d2a02 대비 **14파일** (+665/−86). resources·migration·frontend 변경 **0** | [checks/product-scope.txt](checks/product-scope.txt). 60df3437 대비 제품 변경은 OrderSettlementService 하나 |
| JARM | **33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509** (898항목) | v1.197 §2 수용 값. 운영 LF 기준 대비 변경35·추가3·삭제0 |
| 관문 4판 | `GS=e97ca31b23dec54db7c962395d2f8e60cc92afb0`, `GTP=docs/evidence/dec-costbasis-followup-20261119/rollback-gate-v4-draft`, `GATESQL=b24239f82f09a6fcfcf36026a10614d3d7e7df3687fd720e0336fe1b84d77da0`, `GATESH=0b391e58180d334f1f0d38ac88f7186ae03a6b233c945b54df19eaba8aa16906` | e97ca31b는 main의 조상이다. 이 커밋과 현재 HEAD의 해시가 같다 |
| 배포 전 읽기 | `PFP=…/predeploy-v3/predeploy_read.v3.sql`, `PFSUM=ca6e7c8d6cc67ee967ac21b7847f7230cc859dc3cb9dc895da6f7694dfe26aa3` | 아래 §4 |

## 3. 원판·변수 초안 — V172D4 U변수 대비 바뀌는 곳만

원판은 현재 운영이다: **0e1d2a02 / backend 2c5d8427 / frontend 9d7f123a / DB V96**.
- 예전 b5cd1d0·52e12428·837db515는 이번 배포의 직전판으로 쓰지 않는다. 보존 태그로만 둔다.
- 블록 순서와 R1~R3·postlog 보완 등 V172D4 함수는 그대로 둔다.
- 원문 블록을 그대로 다시 실행하는 것이 아니다. 블록 생성은 이 초안의 판정 뒤에 한다.

| 변수 | V172D4 | 이번 초안 | 상태 |
|---|---|---|---|
| `OLD` / `NEW` | b5cd1d0d… / 0e1d2a02… | **0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d** / **d81c3f2f91c0239aacb0e1176dde9b898f4d7466** | 확정 |
| `IMGOLD` | 52e12428… | **sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f** | 대상 ID는 N03 install_state 기록 값이다(예: [n03-1117-1000](../n03-1117-1000/install_state.txt)) |
| `IMGOLDM` / `IMGOLDC` | ca440b84… / 0344757e… | **빈칸** | 수집 조건: 승인된 읽기에서 imgchain `api`·save 원문 해시로 2c5d8427의 linux/amd64 manifest·config를 확인한다 |
| `IMGPREV9` (신설) | — | 52e12428… (예전 #27, 보존·변경 금지) | 확정. 이번 되돌림 대상이 아니다 |
| `FETOLD` / `FEMOLD` / `FECOLD` / `FEIMGOLD` | 837db515… 등 | **FETOLD=sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e**, 나머지 **빈칸** | 프런트 차분 0이므로 프런트는 교체하지 않는다. U0는 대응만 확인한다. manifest·config·컨테이너 `.Image`는 승인된 읽기로 채운다 |
| 보존 태그 | autotrader-backend:b5cd1d0 등 | 새 태그 `autotrader-backend:0e1d2a0` → 2c5d8427 | 지금 있는지 모른다. U1 `tagkeep`의 네 갈래(있음·같음 / 부재 / 다른 대상 / 불명)로 판정한다 |
| `JARM` / `JARN` | b6587953… / "(끝) 881 895" | **33a06f0e…** / **빈칸** | JARN 형식 값은 U1 계산기 규칙으로 898항목 목록에서 산출한다. 운영 jar 항목 수가 필요하므로 미확정이다 |
| 변경 목록(RARX 등) | 변경48·추가14 | 변경35·추가3·삭제0 (원천 14파일) | v1.197 수용 목록에서 기계 생성한다 |
| `v96()` · V96F/B/SUM | V96 | **그대로** | DDL이 없으므로 마이그레이션 단계를 넣지 않는다. 복구도 V96을 유지한다 |
| `GS`·`GTP`·`GATESQL`·`GATESH` | 6385e1d5·3판 | **§2의 4판 값** | 확정. 3판으로 복구를 판정하지 않는다([N3 근거](../dec-n3-oldbinary-20261119/README.md)) |
| `PFP`·`PFSUM` | 손실 조회 v2 | **§2의 v3 값** | 확정 |
| `LS`·`CK`·`CRON`·`NB*`·`NH*` | 고정 | 그대로 | 운영 값은 배포 전 읽기에서 다시 대조한다 |
| `M`·`UB` | ~/maint-v172 · V172D4 | ~/maint-d81 · D81D4 (가칭) | 이름만 |

## 4. 복구 관문 경계 (§3-5 3) — 대역 [gate4-standin/](gate4-standin/)

**경계 확인.** 저장되는 열거값은 `position`·`paper_trading.position`의 `exit_reason` 하나뿐이다(`EnumType.STRING`, length 20).
- 엔티티 매핑은 0e1d2a02·b5cd1d0d 모두 d81과 같다.
- d81의 새 값은 **ENTRY_REJECTED 하나**다. 두 구판의 8값에 더해진 것으로, 4판의 8값 목록이 이 값을 잡는다.
- 다른 새 저장 리터럴은 없다.
- 근거: [checks/enum-boundary.txt](checks/enum-boundary.txt).

**대역 방식.** 가짜 `psql`([fake_psql.sh](gate4-standin/fake_psql.sh))로 GATE 줄을 지정한다. 4판 `rollback_gate.sh` 원문은 바꾸지 않고 그대로 실행했다([run.sh](gate4-standin/run.sh)). 결과는 **11/11 기대와 일치**한다([result.tsv](gate4-standin/result.tsv)).

| 사례 | 판정 / 종료값 | 장부 |
|---|---|---|
| S01 ENTRY_REJECTED 있음 | STOP_INCOMPATIBLE / 3 | 없음 |
| S02 있음 + 미종결 주문·회계 반영 대기 | STOP_INCOMPATIBLE / 3 (HOLD보다 먼저) | 없음 |
| S03 있음 + 정리 대기·미해결 정산·실계좌 CLOSING | STOP_INCOMPATIBLE / 3 | 없음 |
| S04 있음 + 장부 경로 이미 있음 | STOP_INCOMPATIBLE / 3 (장부보다 먼저) | 미리 만든 폴더뿐, 관문 쓰기 없음 |
| S05 없음·깨끗함 | ROLLBACK_GATE_OK / 0 | 완결 |
| S06 없음 + 미종결 주문 | HOLD_PENDING / 2 | 없음 |
| S07 없음 + 회계 반영 대기·정리 대기 | HOLD_PENDING / 2 | 없음 |
| S08 조회 실패(psql rc≠0) | STOP_UNKNOWN / 3 | 없음 |
| S09 새 키 없는 3판 형식 줄 | STOP_UNKNOWN / 3 | 없음 |
| S10 장부 쓰기 실패 | LEDGER_REFUSED / 4 | LEDGER_FAILED만 |
| S11 없음 + 장부 경로 이미 있음 | LEDGER_REFUSED / 4 | 미리 만든 폴더뿐 |

**대역의 범위.**
- 이 대역은 셸 판정 순서만 확인한다. `gate.sql` 집계 본체는 앞서 실제 PG에서 확인한 결과(예행·N3 구판 2/2)를 출처와 해시로 연결하며, 다시 시험하지 않았다.
- 관문이 거절하면 현재 바이너리를 유지한다. 자동 복구, 행 삭제, 사유 NULL 치환은 하지 않는다.
- STOP_INCOMPATIBLE은 기다려도 풀리지 않는다. 호환 바이너리를 쓸지 운영자가 따로 복구를 판단할지는 **미결정**으로 남긴다.

## 5. 배포 전 읽기 SQL v3 (§3-5 4) — [checks/sql-v3-regen.txt](checks/sql-v3-regen.txt)

- `OrderRepository.java` 블롭은 b408a223·60df3437·d81c3f2f에서 같다.
- 같은 생성기로 d81에서 다시 만든 SQL은 고정 v3와 **머리 SHA 주석 한 줄만** 다르다. b408a223에서 다시 만든 SQL은 v3와 바이트까지 같다.
- 60df→d81의 제품 변경은 OrderSettlementService 하나이고, 그 파일은 조회문을 담지 않는다. 따라서 96/96 대조([근거](../dec-predeploy-parity-20261119/README.md))를 d81에 연결한다.
- **배포 전 승인된 읽기 결과로 판단할 항목.** 운영의 과거 표시 없는 빈 청산, NULL 비용, 미확정 원가가 새 진입을 막는지 확인한다(R1 전역 30일, R3 세션 newest_rank).
  - LIVE는 30일 창 밖의 빈 청산도 막는다(P8).
  - 지금 운영 DB를 읽거나 과거 행을 보정하지 않는다.
  - 판정 불가나 거절이 나오면 시작을 보류하고 원문을 보고하는 것이 기본이다. 기존 보유의 청산 경로는 유지한다.

## 6. 유지하는 계약과 이번에 볼 부분 (§3-5 5)

- **그대로 유지:** 이미지/컨테이너 대응(imgchain 네 조건), 보존 태그 `tagkeep`/`namecls`, 재시작 전 확인, P0·로그 미완료 시 중단(holdobs 0·1·3 밖이면 멈춤, 두 번째 로그 포함), 단계 종료값과 원문 보존, 운영자 확인 위치. V172D4 [§2](../v172d4-20261116/README.md) 그대로다.
- **이번 변경으로 다시 검증할 곳**
  1. U0·U되돌림이 4판 해시로 꺼낸 관문의 `STOP_INCOMPATIBLE`(3)을 멈춤으로 처리하는지 확인한다(3판에는 이 판정이 없었다).
  2. U1의 프런트 교체 단계를 빼고 대응 확인만 하는지 확인한다.
  3. `v96()`가 유지되고 마이그레이션 단계가 없는지 확인한다.
  4. 블록 생성 뒤 옛 값 b5cd1d0·52e12428·3판 해시가 직전판 자리에 남지 않았는지 기계 검사한다.
- **실행 전에 필요한 것:** 위 빈칸(manifest·config·JARN·보존 태그 상태)을 채울 운영 관리자 읽기 승인, 블록 생성과 대역, 고정 출처 검토, **별도 운영 실행 승인**.

## 7. 한계

- 블록 원문은 아직 만들지 않았다. 이 문서는 변수와 경계를 정리한 초안이다.
- 대역은 가짜 psql로 셸 분기만 확인했다. 실제 PG 집계와 서버 도커 동작을 보장하지 않는다.
- 2c5d8427·9d7f123a는 대상 ID 기록 값이다. manifest·config 대응은 운영에서 확인하지 않았다.
- 매수 요약 버퍼 메모리 유실, 휴대폰 미확인, 원가 판정 불가 해제 범위, 손실률 반올림 한계는 닫지 않는다.

체크섬 [SHA256SUMS](SHA256SUMS).
