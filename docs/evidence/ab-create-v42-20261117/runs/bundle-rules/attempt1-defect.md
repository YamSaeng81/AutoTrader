# test_run_bundle.sh 1회차 결함 (2026-11-17 00:24Z, 결과 8/19 — 시험 결함)

- 원인: 대역 폴더를 `pwd -W` 형(`C:/Users/...`)으로 PATH 앞에 넣어 콜론에서 PATH 가 갈라졌다. 대역 python3·docker 가 아니라 **실제 python3·docker 가 불렸다.**
- 실제로 일어난 일(1회차 화면 출력에서 확인):
  - STEP 1·3·5: 실제 `docker compose -f <임시폴더>/repo/docker-compose.prod.yml exec ... psql` → compose 파일이 없어 psql rc=1 → 실행 틀 종료 2. DB 접속 없음.
  - STEP 2(T06~T10·T14): 실제 `create/ab_create.py --execute` 가 불렸으나 사전 DB 메타 조회(같은 없는 compose 파일)에서 실패 →
    `{"stop": "사전 DB 메타 조회 실패 — 보낸 것 없음", "status": "DB 메타 조회 실패(종료 1)"}`, ab_create rc=2. **POST 0건**.
    이 PC 에는 8080 수신 포트가 없었다(같은 시각 `netstat` LISTENING 에 :8080 없음). 운영 서버·운영 토큰 접근 없음(토큰은 임시 대역 문자열).
  - 출력은 모두 임시 폴더(시험 끝에 삭제)였다. 1회차 화면 출력 파일은 따로 저장하지 않았다.
- 보완: PATH 에는 유닉스형 경로(WU), 파일 인자는 python 이 읽는 형(W)을 쓴다. 시험 시작 전에 python3·docker·git·tee 가 모두 대역으로 잡히는지 확인하고 아니면 시험을 시작하지 않는다(종료 2).
- 2회차: 19/19 통과 — [test_run_bundle.run2.txt](test_run_bundle.run2.txt).
