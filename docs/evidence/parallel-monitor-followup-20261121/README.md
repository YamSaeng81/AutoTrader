# 병행 감시 후속 보완 — G1 SQL·G2/G3 읽기 실행안·변환기→R2 합성 연결 (총괄 v1.216 §3)

2026-11-21 KST, 개발 세션 제출(판정 전). 기준 지시: [v1.216](../review-40a2fda5/README.md) · 기준 커밋 7b8b82a8.

**운영 SSH·DB·로그 접근, 설치·cron, 배포, 생성·T0, 실자금은 없었다.** 실제 pm-read는 보류 상태 그대로다. 기존 4경계·13사례·R2 6/6·제품 회귀·빌드·jar는 다시 돌리지 않았다. pm-state-1·2, pm-conv-3, pm-read-1 원문은 고치지 않았다.

## 결과 요약

| 작업 | 결과 | 근거 |
|---|---|---|
| 1. G1 SQL 사본 pm-state-3 + 격리 PG 1묶음 2경계 | **2/2** | [pg-run1](runs/pg-run1/pg_check_2.out.txt) |
| 2. 읽기 실행안 pm-read-2 + 가짜 SSH 5경계 8변형 | 1회차 0/8 → 2회차 8/8(예약 이름 결함 발견) → **3회차 8/8** | [run1](runs/fake-ssh-run1/out.txt) · [run2](runs/fake-ssh-run2/out.txt) · [run3](runs/fake-ssh-run3/out.txt) · [승인안](approval/README.md) |
| 3. 변환기→R2 합성 연결 3경계 4변형 | **4/4** | [link-run1](runs/link-run1/cases_link.out.txt) |

## 1. G1 — 저장된 DYNAMIC은 실자금 증거로 유지

[state-3.sql](sql/state-3.sql)(6d71df61)은 pm-state-2(4c04efff)의 최소 수정 사본이다. 바뀐 것은 모의 제외 조건 하나다.
- 모의로 빼는 행은 `session_kind='DYN_PAPER'`이면서 세션이 `trading_mode='PAPER'`로 **일치하는 경우만**이다. 이 행은 개수로만 남긴다.
- `DYNAMIC` 종류인데 세션 모드가 REAL이 아니면 행을 남기고 `KIND_MODE_MISMATCH`로 표시한다. `DYN_PAPER` 종류인데 PAPER가 아닌 경우도 같다.
- U·CNT에도 같은 조건을 쓴다. U·CNT의 비교는 NULL에 안전한 `IS NOT DISTINCT FROM`으로 했다. 작성 중 `=` 비교로 두면 모드가 NULL인 행이 개수에서 빠지는 것을 보고, 실행 전에 고쳤다.

변환기 [pm-conv-4](candidate/convert_state_4.py)(1416564e)의 변경은 세 가지다.
- SQL 판 표지를 pm-state-3으로 맞췄다.
- 소유 분류 `KIND_MODE_MISMATCH`를 `*_OWNER_KIND_MODE_MISMATCH`로 이름 그대로 낸다. pm-conv-3은 이 경우를 UNCLASSIFIED로 냈다. 두 판 모두 수집 불통과다.
- pm-conv-3의 출처 메모에 남아 있던 `pm-state-1` 문자열을 실제 판으로 고쳤다.

격리 PG 환경은 다음과 같다([setup](runs/setup/setup.txt)).
- 이미지: 로컬 `timescale/timescaledb:latest-pg15`(PG 15.13)
- 컨테이너: `--network none`, 데이터는 tmpfs. 끝난 뒤 삭제했다.
- 스키마: d81c3f2f V1~V96(95개 파일)을 고정 setup_pg.py(98b3eab0)로 적용했다.
- 호출: 실행안과 같은 표준 입력 `-f -`에 `PGOPTIONS` 보호값을 함께 줬다.

| 경계 | 결과 |
|---|---|
| G1a 미등록 REAL 세션 950에 DYNAMIC 주문 31·32와 포지션 31을 만든 뒤 모드만 PAPER로 변경 | psql rc 0, 표준 오류 0B. O 31·32와 P 31이 `KIND_MODE_MISMATCH`로 남음. U `1 1`, CNT `4 3 2 1 1`. 변환은 COLLECTION_NOT_PASSED(ORDER·POSITION_OWNER_KIND_MODE_MISMATCH). **대조:** 같은 DB에서 pm-state-2는 O `[1]`·P `[11]`·U `0 0`만 내 실자금 행과 표지를 숨김(G1 재현) |
| G1b 일치하는 DYN_PAPER/PAPER(901·902)와 정상 REAL(900)이 섞인 경우 | 모의는 개수로만 빠짐(2/2). REAL 주문 1·2와 포지션 11, U `1 1` 유지. CONVERTED → W1 SET_MATCH, W2 RISK_PRESENT(SETTLEMENT_UNRESOLVED) |

두 경계 모두 `SHOW` 결과가 `15s`·`2s`였다. 금액 표지는 출력 어디에도 0건이었다. P0 원천에는 저장된 p0-run1 Q1 결과 사본을 썼고, 해시 3ac1c1a0을 확인했다.

## 2. G2/G3 — 읽기 실행안 pm-read-2

변경 전 pm-read-1(7f07c470)의 정적 공백은 v1.216이 지적한 세 가지다. 이번 시험은 pm-read-1을 실행하지 않았다.
- 문서에 적은 HEAD·이미지·Flyway 대조가 코드에 없었다.
- read와 post가 각각 마지막에 `exit 0`을 써서, 앞 단계 실패가 성공으로 보일 수 있었다.
- UNTIL이 PC 시계에서 나오는데 결과에는 server_utc로 표시됐다.

보완 후의 동작, 시험 표, 제안 값은 [approval/README.md](approval/README.md)에 있다.

**1회차 실패와 보완:** [fake-ssh-run1](runs/fake-ssh-run1/out.txt)은 0/8이었다. 원문과 당시 실행안·시험기 사본(`*.at-run1.sh`)을 보존했다.
- 실행안 결함은 1건이다. 로그 전에 멈추면 local-only가 비어 `sha256sum -- *`가 실패했고, 그래서 정상적인 멈춤 5건이 기록 실패 종료로 바뀌었다.
- 시험기 결함은 2건이다. Windows sha256sum의 `*` 표기를 기대하지 않았고, V8의 실제 쓰기 오류 표준 오류를 실패로 셌다.

세 가지만 고쳐 2회차 8/8을 얻었다. 이어서 커밋할 때 post 파일 `aux.rc`·`aux.stdout`을 git이 열지 못하는 것을 발견했다. `AUX`가 Windows 예약 장치 이름이기 때문이다. 이름만 `aux-writer.*`로 바꿨고(실행안 fa767e7d), **3회차 8/8이 최종**이다. 1·2회차의 해당 8개 파일은 로컬에만 보존했다([해시](runs/windows-reserved-names.txt)).

## 3. 변환기→R2 합성 연결 pm-link-1

[link_r2.py](candidate/link_r2.py)(327227d2)의 동작은 다음과 같다.
- pm-conv-4 결과가 CONVERTED이고 consumable이며 출력 해시가 일치할 때만 R2(e203ea6c)를 1회 호출한다.
- 시나리오에는 W1·W2만 넣는다. W3/P7·W4·W5와 생성·시작은 붙이지 않는다.
- 소비할 수 없는 결과면 R2 호출은 0회이고 상태는 NOT_LINKED다.
- 등록표는 합성 registry-3(14599ba6)이다. 실제 ID나 승인 참조를 꾸미지 않았다.

| 경계 | 결과 |
|---|---|
| L1 CONVERTED 정상 | R2 1회. W1 SET_MATCH와 W2 NO_RISK_ITEMS 모두 LOCAL_VERIFIED, 계약 OK. R2 전체는 미연결 W3~W5 때문에 NEEDS_DATA(10). 연결 0 |
| L2a P0_CHECK_INCOMPLETE(저장된 Q3) | 변환 COLLECTION_NOT_PASSED(AUX_P0_CHECK_INCOMPLETE). **R2 0회**, r2 폴더 없음 |
| L2b COLLECTION_NOT_PASSED(pg-run1 G1a 저장 변환 결과) | **R2 0회** |
| L3 P0_PRESENT(저장된 Q2) | CONVERTED → W2 RISK_PRESENT[P0] → R2 w2 FAILED, 전체 FAILED(20). 연결 LINKED_NOT_VERIFIED(20) |

모든 경계에서 operational_ready=false다.

**L1 정상 입력의 출처:** 수기로 만든 합성 pm-state-3 형식 TSV다(cases_link.py의 NORMAL_TSV). PG 출력이 아니다. 기존 PG 결과의 정산 표지를 고쳐 만든 것도 아니다. G1b의 실제 출력은 정산 표지 때문에 W2 위험이 된다.

## 판단 요청

1. pm-state-3에서 저장된 DYNAMIC을 실자금 증거로 유지하고, 모드가 어긋나면 KIND_MODE_MISMATCH 불통과로 처리하는 방식, 그리고 pm-conv-4가 이를 이름 그대로 내는 방식을 수용할지.
2. pm-read-2의 핀·종료값·기록·서버 UTC 처리와 local-only 보존 방식, 그리고 [제안 값](approval/README.md#5-승인자에게-내는-제안-값제안일-뿐-운영-읽기-승인-아님)을 재검토할지.
   - 특히 운영 `docker compose exec -T -e PGOPTIONS` 전달은 격리 검증 형식(`docker exec -e`)과 다르다.
3. pm-link-1의 연결 계약을 수용할지. 정상은 W1·W2 LOCAL_VERIFIED일 때만이고, 불통과면 R2 0회, P0면 실패로 남는다.

## 파일과 보존

- 체크섬: [SHA256SUMS](SHA256SUMS). 이 폴더에서 커밋한 파일 전체를 담았다. Windows 예약 이름 파일 8개는 [별도 목록](runs/windows-reserved-names.txt)에 있다.
- 가짜 SSH 회차의 `pm-read-*/local-only/`는 목록과 커밋에서 뺐다. 실행안이 만든 `.gitignore` 동작 그대로이며, 내용은 p0-run1 Q1 합성 로그(359350ef) 사본과 빈 표준 오류다. 각 폴더의 `local-only-SHA256SUMS`에 해시가 있다.
- 권한 파일 `.claude/settings.local.json`은 건드리지 않았고 커밋하지 않았다.
