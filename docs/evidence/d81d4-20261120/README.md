# D81D4 — d81c3f2f backend 전용 실행 블록 로컬 작성 (총괄 v1.198 §3)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). **초안이며 실행 승인이 아니다.** 운영 접속·SSH·운영 DB·Docker·빌드·제품 시험·PG는 하지 않았다. 입력은 고정 Git 객체, 저장 근거 파일, 이미 있는 jar 두 개(읽기만)다. 지시는 [v1.198](../review-7c62a7db/README.md) §2·§3이다.

## 1. 결과 요약

| 항목 | 값 |
|---|---|
| 블록 | [d4/blocks-d81/](d4/blocks-d81/) 9개 — U변수·U0·U1·U1되돌림·U2·U3·U4·U되돌림·U뒤관측. **UF2 없음**, U2확인은 SQL 계약만 옮김. 해시 [blocks-d81.sha256](d4/blocks-d81.sha256) |
| 생성기 | [d4/mkd81.py](d4/mkd81.py) — ce6df2fa 원문 9블록에서 지정한 곳만 바꾼다. 바꿀 곳마다 일치 횟수를 단언하고 하나라도 어긋나면 아무것도 쓰지 않는다. 보고 [gen-report.txt](d4/gen-report.txt) |
| 실제 jar 대조 | [jarcmp-real/](jarcmp-real/) — U1 비교기 원문으로 운영 0e1d2a02 서버 jar 대 d81 LF jar: **38줄(변경35·추가3·삭제0)·`(끝) 895 898`**, JARM 옛 b6587953 / 새 33a06f0e |
| 대역 | [standin/](standin/) 최종 r3 **201/0**·금지 명령 0([원문](standin/runs/scen-r3.txt)). r2 199/2(시나리오 쪽 2, §4) 보존 |
| 실행 틀 | [runner/](runner/) 11해시 고정. 가짜 ssh 자체 시험 **30/30**([원문](runner/runner-selftest.txt)). 고정 출처 **e21c777f**에서 `extract.py` 11/11 같음([blocks.json](runner/extract-e21c777f/blocks.json)), 그 추출물로 run.sh U0 가짜 ssh rc0. d593e1dd의 extract.py는 한글 블록 이름 ls-tree 따옴표 처리 누락으로 rc1 — e21c777f에서 그 한 줄만 보정(블록·해시 무변경) |

## 2. 고정 값과 출처

| 변수 | 값 | 출처 |
|---|---|---|
| `OLD` / `NEW` | 0e1d2a021ab2… / d81c3f2f91c0… | v1.197·v1.198 수용 |
| `M` / `UB` | `~/maint-d81` / `D81D4` | 새 판·새 기록 경로. 옛 `~/maint-v172`는 U0의 원판 기록 읽기에만 쓴다 |
| `IMGOLD`·`IMGOLDM`·`IMGOLDC` | 2c5d8427… · b3a42cd2… · 4016cc38… | 427차 보존본 `maint-v172/image-new-chain.txt`(11-16 관측 기대값) |
| `FETOLD`·`FEMOLD`·`FECOLD`·`FEIMGOLD` | 9d7f123a… · 13343943… · 11d9a954… · 9d7f123a… | 같은 보존본 `image-fe-new-chain.txt`·`svc-fe.txt` |
| `IMGPREV9`·`FEPREV9`(신설) | 52e12428… · 837db515… | 옛 b5cd1d0 보존 태그. 보존만 하고 복구 대상이 아니다 |
| `DIFFN` | 43경로(제품 Java 14·시험 29) | `git diff --name-only OLD NEW`를 생성기가 만든다 |
| `RARX` / `JARN` / `JARM` | 38줄 / `(끝) 895 898` / 33a06f0e… | 7c62a7db의 jar 차이 목록을 비교기 출력 순서(항목 이름순)로 정렬했다. 실제 비교기 출력과 같다 |
| `GS`·`GTP`·`GATESQL`·`GATESH` | e97ca31b · rollback-gate-v4-draft · b24239f8… · 0b391e58… | 관문 4판 |
| `PFP`·`PFSUM` | predeploy_read.v3.sql · ca6e7c8d… | 배포 전 읽기 v3 |
| `v96h()`(신설) | `1\|96:true:-424836241`만 통과 | U2확인 d456788f(1c9eab35)의 행수+내용 단일 SELECT·NULL 명시 SQL과 같은 문자열(생성기가 대조) |
| F·G | F 26d39269… · G 4d7ff02a… | 7c62a7db. F는 ce6df2fa 판과 `NEW` 핀 한 줄만 다르고 U변수가 다시 지정한다 |

현재 서버 값은 새로 읽지 않았다. 값이 다르면 U0가 멈추며 기대값을 자동으로 교체하지 않는다.

## 3. V172D4 대비 바뀐 흐름

**backend만 교체한다.**
- U0: frontend 실행 컨테이너·이미지 이름 대응을 기록만 한다. frontend 보존 태그 판정은 없다.
- U1: frontend 빌드·태그·단계 기록을 뺐다. backend 빌드 뒤 frontend 이름·실행 컨테이너가 U0 대응 그대로인지만 본다.
- UF2: 만들지 않았다.
- U2·U3·U4: frontend 실행 대응을 다시 확인한다. U4는 `svc-fe.txt`·`ok-UF2` 대신 **시작 기록 `svc-before.txt`의 backend 밖 4줄**(frontend·db·redis·db-backup의 이미지·기동 시각)과 직접 비교한다.
- U1되돌림·U되돌림: frontend를 바꾸는 명령이 없다. frontend가 U0 대응과 다르거나 조회 불명이면 아무것도 바꾸지 않고 멈춘다.

**V96 기적용을 전후로 확인한다.** 옛 `v94()`와 `96:t:` 기대는 없앴다.
- U0, U2 재기동 전, U2 재기동 뒤, U3, U4에서 `v96`(최고 버전 96)과 `v96h`를 확인한다.
- 원문은 `v96-before`·`v96-pre-deploy`·`v96-deploy`·`v96-u3-<종류>`·`v96-after30`에 남긴다.
- U2의 재기동 전 확인은 `t-deploy` 기록보다 앞에 둔다. 실패하면 재기동 시작 기록이 남지 않는다.
- U3은 deploy·rollback 모두 마이그레이션 줄이 있으면 멈춘다. DDL 단계는 없다.

**시각 기준은 원래 기동 기록을 쓴다.**
- 운영자 확인 1회는 U1 끝(U2 재기동 직전)에 둔다.
- U3의 5분 하한, U4의 30분, U뒤관측은 모두 `t-deploy.utc` 기준이다.
- U3이 10분을 넘겨 실행되면 "늦은 실행 편차" 줄만 남기고 기준을 바꾸지 않는다.

**보존 태그를 판정한다.**
- 새 보존 태그 `autotrader-backend:0e1d2a0`은 V172D4 R1 네 갈래 그대로 판정한다. 명시적 부재일 때만 U1이 만든다.
- 옛 `b5cd1d0` 두 태그는 새 `oldtag()`로 판정한다. 있음·같음이면 통과, 명시적 부재면 기록만 하고 만들지 않는다. 다른 대상이거나 조회 불명이면 멈춘다.
- 옛 8개 태그는 V172D4와 같이 대상 일치를 요구한다.

**복구는 0e1d2a02를 대상으로 한다.**
- U되돌림은 관문 4판으로 판정한다.
- `STOP_INCOMPATIBLE`이면 별도 문구로 멈춘다. 구판이 읽지 못하는 청산 사유가 있다는 뜻이며, 이것을 복구 한계로 명시한다. 호환 바이너리 생성·DB 값 변경·자동 복구는 하지 않는다.
- HOLD·STOP_UNKNOWN·LEDGER_REFUSED도 현재 바이너리를 유지하고 멈춘다.

**배포 전 읽기 v3를 U0에서 판정한다.**
- v3에는 판정 줄이 없다. 그래서 새 `pf3ok()`가 형식을 확인하고 개수만 출력한다.
- 진행 가능 조건: R1(30일 판정 불가)이 LIVE·DYNAMIC 모두 0이고, R3(RUNNING REAL 세션 청산 행)에 판정 불가 행이 0.
- R3 판정 불가는 보수적으로 정했다. 무거래 확정이 아니면서 아래 중 하나라도 해당하면 판정 불가로 본다.
  - 체결 BUY 0 또는 확정 SELL 0
  - 원가 미상 BUY 또는 필수값 미상 SELL
  - 합계 없음
  - 매도 수량 < 매수 수량
- R2 손실 합은 기록만 한다.
- 🟡 **총괄 확인 필요:** 연속 손실 한도 안의 행만 막는 제품보다 넓게 멈춘다. 이 기준을 그대로 쓸지 판단이 필요하다.

## 4. 대역 — 바뀐 연결만

**장치.** V172D4·U2확인 r3의 대역 장치(`v172d4s/sim`)를 옮겼다. 원본 대비 차분은 [harness/v172d4s-to-d81d4s.diff](standin/harness/v172d4s-to-d81d4s.diff)에 있다.
- 시작 상태는 현재 운영이다: HEAD 0e1d2a02, backend·frontend 11-16 컨테이너, V96 적용됨, 옛 보존 태그 10개, `~/maint-v172/image-new.txt`.
- F·G는 7c62a7db 원문을 쓴다.
- jar는 실제 두 개를 읽기만 한다. 서버 0e1d2a02 jar(5c53c9e3)와 d81 LF jar(eae7e1d2)이며, JARM 거절용으로 d81 jar에서 클래스 하나만 1바이트 늘린 사본을 따로 만들었다.
- 가짜 docker는 배포 전 읽기 v3 응답, 관문 집계·장부 실패 주입, 재기동 뒤에만 바뀌는 V96 이력 응답을 더했다.
- 가짜 git은 관문 4판·v3 경로만 바꿨다.
- 사례 폴더는 회차마다 새로 만든다(`runs/<회차>-<사례>`). 원문 압축 [standin-runs-r1r2r3.tar.gz](standin/runs/standin-runs-r1r2r3.tar.gz)에 jar를 뺀 15,107파일이 있고, 해시 목록은 [runs-r1r2r3.sha256](standin/runs/runs-r1r2r3.sha256)이다.

**r3 201/0.** 각 사례는 종료값·멈춤 문구에 더해 파일·장부·태그·컨테이너 상태와 변경 명령 수까지 단언한다.

| 사례 | 확인 |
|---|---|
| n01 정상 | U0→U1→U2→U3→U4→U뒤관측 rc0. 변경 명령 순서는 정확히 `tag 0e1d2a0` → `checkout NEW` → `build backend` → `up backend`이다. frontend 변경 명령 0, frontend 컨테이너·기동 시각·이름은 그대로다. 옛 태그 10개 불변, 새 태그 = 원판. V96 기록 5개는 모두 `1\|96:true:-424836241`이다. UF2·frontend 단계 기록은 없다. U1 끝 문구에 운영자 확인 위치가 있다. 재실행은 거절된다 |
| l01 | U3를 15분 뒤 실행하면 rc0이고 "늦은 실행 편차" 줄이 남는다 |
| f01~f05 | 아래 경우 모두 변경 없이 멈춘다: U1 전 frontend 이름 변경, U2 전 이름 변경(재기동 0·`t-deploy` 없음, U1되돌림도 변경 0), 이름 조회 불명(U1되돌림·U2), U4 전 frontend 재기동, U3 전 frontend 컨테이너 교체 |
| v01~v09 | U0는 false·NULL 혼합·단독 NULL·추가 행·조회 실패·최고 버전 95에서 ok-U0 전에 멈춘다. U2는 재기동 전 0행이면 재기동 0으로 멈추고, 재기동 뒤 false면 "되돌림 필요"로 멈춘다. U3는 마이그레이션 줄이 있으면 멈춘다 |
| g01 | 관문 OK면 U되돌림의 변경 3개(checkout·latest·up)로 HEAD·latest·backend가 원판으로 돌아간다. frontend 변경 0, 장부 완결, U3(rollback) 통과 |
| g02~g08 | 다음 경우 모두 변경 명령 0이고, HEAD NEW·새 backend 그대로·`t-rollback` 없음·LEDGER_COMPLETE 없음이다: STOP_INCOMPATIBLE(단독, 그리고 미종결 주문과 함께 — HOLD보다 먼저), HOLD, 집계 실패 STOP_UNKNOWN, 장부 쓰기 실패와 미리 있는 장부 경로(LEDGER_REFUSED, 덮어쓰기 거절), 새 키 없는 3판 형식 줄 |
| t01~t07 | 새 태그: 있음·같음이면 다시 쓰지 않는다(tag 명령 0). 다른 대상·조회 불명이면 멈춘다. 옛 b5cd1d0: 명시적 부재면 ⚪ 기록하고 통과하며 U1 뒤에도 만들지 않는다. 다른 대상·조회 불명이면 멈춘다 |
| r01~r03·j01 | U1되돌림은 재기동 0으로 HEAD·latest를 원판으로 되돌리고, frontend 변경 0, 새 보존 태그는 남긴다. U2 뒤 U1되돌림은 거절된다. jar 이름 목록 불일치와 JARM만 불일치(이름·`(끝) 895 898` 통과)는 재기동 전에 멈춘다 |
| p01~p04 | 기존 계약 유지: U3 P0 멈춤은 뉴스 조회보다 앞이다. 빈 로그, U4 로그 실패, U뒤관측 두 번째 로그 실패에서 멈춘다 |
| u01~u07 | 배포 전 읽기 v3: R1 LIVE 1, R3 판정 불가(확정 SELL 0, 매도<매수 수량), 형식 이상 2종, 조회 실패에서 빌드·체크아웃 전에 멈춘다. 확정 행·무거래 확정 행만 있으면 통과한다 |

**앞 회차(합산하지 않음).**
- **r1**: 대역 F 사본이 옛 판(eeab6c10, `NEW` 1899b49 핀)이어서 n01 중간에 중단했다. 결과는 쓰지 않는다([원문](standin/runs/scen-r1-중단-F사본교체.txt)).
- **r2 199/2**: 실패 2는 시나리오 쪽이다. 블록은 둘 다 계약대로 멈췄다. 고친 곳은 [r2→r3 차분](standin/harness/scen-r2-to-r3.diff) 9줄이고, 블록 변경은 없다.
  - f01은 frontend 이름만 바꾼 사례인데 기대 문구를 실행 컨테이너 쪽으로 적었다.
  - t02는 "다른 대상"으로 해석 불가한 값을 넣어 블록이 "조회 불명"으로 멈췄다.

**같은 본체의 기존 시험은 다시 하지 않았다.** 이미지 대응 4조건·imgchain·tagq 응답 형식·holdobs/newsobs 등 관측 함수·jar 비교기 v2의 기존 사례는 [V172D4 대역](../v172d4-20261116/README.md) r1/r2와 [U2확인 r3](../v172u2c-20261116/README.md)를 연결한다.

## 5. 검사

- 옛 운영 값 잔존: 생성기가 실행 줄(주석 제외)에서 옛 OLD·IMGOLD·manifest/config·FEIMGOLD·JARM·관문 3판·손실 조회 v2·`~/maint-o13n21`·`881 895`·`v94`·`96:t:`·frontend 단계 기록·UF2를 찾는다. 허용 예외는 IMGPREV9·FEPREV9 정의와 옛 태그 판정 줄뿐이며, 잔존은 0이다.
- 금지 명령 0: frontend build·up·tag, SQL 쓰기, flyway 변경.
- 변경 명령 수(블록별): U1 4(보존 태그·checkout·build·임시 컨테이너 rm), U1되돌림 2, U2 1, U되돌림 3, 나머지 0.
- `bash -n` 9/9.

## 6. 한계

- 2c5d8427·9d7f123a의 save 원문(manifest·config 바이트)은 보존본에 없다. 그래서 대역은 원판 이미지를 합성 원문으로 등록하고 그 값을 `vars.sh`로 치환했다. 실제 대응은 427차 보존 해시 대조(총괄 v1.198)와 실행 때의 U0 재대조로만 확인된다.
- 대역의 관문은 가짜 psql 응답으로 셸 판정만 본다. gate.sql 집계 본체는 앞 실제 PG 근거를 연결한다.
- 옛 b5cd1d0 태그의 현재 존재, 현재 실행 상태, 보존 태그 상태는 미확인이다. U0에서 판정한다.
- 운영 배포·관문 설치·실제 세션 생성은 승인 전이다.
