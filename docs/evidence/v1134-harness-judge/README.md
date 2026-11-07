# 하네스 판정 보완 (총괄 v1.134 §3, 2026-11-07, 로컬)

앱·DB·외부 명령 없이 순수 함수와 가짜 입력으로만 검증했다. **하네스 판정 보완만으로 한 시간 리허설을 다시 돌리지 않았다**(§3-5). 다음 실제 후보 검증에서 필요한 구간이 있으면 같이 수행한다. 과거 출력·실패 폴더·체크섬은 고치지 않았다.

## 바꾼 것

판정식을 `scripts/restart-test/judge.py`로 꺼내고 두 리허설 파일이 이를 부른다(`harness.diff`: d499f7c 대비).

| v1.134 | 이전 | 이후 |
|---|---|---|
| §3-1 재시작 요청 | `d == []`만 봄. 200 성공 응답이어도 통과 | `restart_rejected`: **HTTP 409 + "세션이 이미 실행 중입니다" + 기록 차이 0**을 함께. rehearse_w3 ③·rehearse_sma20 ⑤ 둘 다. 성공 응답도 상태 코드를 받도록 요청 함수를 바꿈 |
| §3-2 F5-3 | 기대 텍스트만 봄. 종료 1이어도 통과 | `f53_ok`: `rc == 0`을 함께 |
| §3-2 F5-1 주입 뒤 | 종료값 안 봄. end_kst 세 줄이 서로 같기만 하면 통과 | `f51_shifted_ok`: `rc == 0` · due t · 2줄 이상 같음 · **DB 실제 `started_at`(KST) + 56일과 같음** |
| §3-2 비0 기대 | `due f`·"아직 56일 전"·두 번째 요청 거절 | 그대로(비0 기대) |
| §3-3 최고 평가액 첫 기록 대기 | `note()` — 실패해도 계속 | `check()` — 실패하면 그 단계에서 중단 |
| §3-3 집계 | `통과/전체`에 관측 메모 포함 | `tally`: **단언 n/m · 관측 k** 따로. `(관측) ` 항목은 단언 수에서 뺌 |
| §3-4 환경 ERROR | `'Source] 수집 실패'` 공통 꼬리면 모두 뺌 | W3 실제 로그에서 관측한 원문 다섯 종류만 줄 끝까지 고정(웹소켓 `ConnectException`, RSS tokenpost/blockmedia `: null`, UpbitNotice·CryptoPanic `: null`, CoinGecko `: null`). 리허설 전용이며 운영 N03 분류와 무관 |

## 결과

- `test_judge-output.txt`: **35/35**, 종료 0.
  - 대조군: run2/run5 실제 409 응답 본문, run5 F5-1 원문 시각(started 2026-11-06 19:50:00.656152 → end 2027-01-01 19:50:00.656152), F5-3 기대 텍스트, 실제 환경 ERROR 원문 6줄.
  - 총괄이 확인한 허위 통과 세 개(200·차이 0 / F5-3 rc1 / F5-1 rc1)와 그 밖의 나쁜 입력(500, 다른 409 사유, started+56일 아님, 줄 1개, 다른 sourceId, null 아닌 뉴스 메시지, 운영 CryptoPanic `Connection reset`, 덧붙은 문자, 같은 문구 WARN, PoolLossGuard ERROR 등)을 모두 거절함.
- `w3-logs-env-recount.txt`: W3 보존 앱 로그 ERROR 160줄을 새 판정으로 다시 셈. 환경 154, 남음 6 = 생성 시점 PoolLossGuard 위험 한도 4(run2~run5 app-01) · O2 PoolLossGuard 1(run2) · run1 기동 실패 1. 기존 README 분류와 같다. 새 판정을 run5 ④ 구간에 적용해도 결과는 바뀌지 않는다(남은 6줄은 모두 ④ 구간 밖).

## 파일

`judge.py`·`test_judge.py`(main `scripts/restart-test/`와 같은 내용 사본), `harness.diff`, `test_judge-output.txt`, `w3-logs-env-recount.txt`.
