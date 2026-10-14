# 실제 프로세스 재시작 시험 (2026-10-13)

H2·모의 객체가 아니라 **실제 PostgreSQL + 실제 앱 프로세스 + 별도 프로세스 거래소 대역**으로, 주문 처리 도중 앱을
강제 종료하고 재기동했을 때 중복 제출·중복 정산·예약 유실이 없는지 본다.

## 구성

| 요소 | 내용 |
|---|---|
| DB | `cat-rt-pg` 컨테이너(`timescale/timescaledb:latest-pg15`, 운영과 같은 이미지), `127.0.0.1:15432`. 스키마는 앱 기동 시 **Flyway 가 V1~V87 을 실제로 적용**한다(시험용 수동 스키마 없음, `ddl-auto: validate` 통과) |
| Redis | `cat-rt-redis`, `127.0.0.1:16379` |
| 거래소 대역 | `exchange_double.py` — 앱과 **별도 프로세스**. 접수·체결 기록을 파일에 보존하므로 앱을 죽여도 외부 주문이 남는다. 제어 엔드포인트로 체결 방식(즉시 체결·대기·절반 체결), 응답 보류(그 사이 앱을 죽인다)를 지정한다 |
| 앱 | `restartHarness` 소스 세트 클래스패스로 띄운 실제 JVM. 🔴 운영 jar 에는 시험 진입점이 없다 |
| 강제 종료 | ① `-Dcrashpoint=<지점>` → 그 지점에서 `Runtime.halt(137)`(kill -9 와 같음) ② 외부 `taskkill /F /T` |

강제 종료 지점(`CrashPoint`, 속성이 없으면 아무 일도 하지 않는다): `AFTER_INTENT_COMMIT` · `AFTER_CLAIM_COMMIT` ·
`AFTER_EXCHANGE_ACCEPT` · `AFTER_FILL_OBSERVED` · `IN_FILL_ACCOUNTING`(회계 쓰기를 DB 에 보낸 뒤 커밋 전).

🔴 이 속성들은 **시험 빌드에서만** 허용된다 — 운영 jar(시험 진입점 클래스 없음)에 주면 `TestHooks` 가 기동을 거부한다
(2026-10-14). 실행 증거는 저장소 밖 `D:\Claude Code\evidence\crypto-auto-trader\` 에 보관한다(이미지는 digest 로 기록).

시험 환경 격리: JVM 프록시를 막힌 포트로 둬 외부 인터넷 호출을 차단하고, 주문 API·상태 확인 주소만 대역으로 돌린다
(`-Dupbit.api.base-url`, `-Dupbit.health-check-url` — 시험 빌드가 아니면 기동 거부).

## 실행

```bash
./gradlew :web-api:restartHarnessClasspath      # 클래스패스 → web-api/build/restart-harness-classpath.txt
cd scripts/restart-test
python scenarios.py --fresh --both               # DB 컨테이너를 새로 만들고 LIVE 전부 + DYNAMIC 4종
python scenarios.py S6                           # 시나리오 하나(기존 DB)
```

로그: `restart-test-work/app-NN-<label>.log`, `double.log`, `exchange_state.json`.

## 시나리오 (세션 10,000 에서 3,000 시장가 매수, 대역 기본 체결 2,940·9.8·수수료 1.47)

| | 종료 시점 | 복구 후 기대 |
|---|---|---|
| S1 | 의도 커밋 직후 | 소유 프로세스 상실로 철회(`WITHDRAWN_OWNER_LOST`), 거래소 0회, 예약 한 번 복원(10,000) |
| S2 | claim 커밋 직후(거래소 미도달) | 식별자 조회 404 → `UNKNOWN`, **미접수로 단정하지 않음**, 재제출 0, 예약 7,000 유지 |
| S3 | 거래소 접수·체결 직후, 응답 기록 전 | 식별자로 찾아 원자 회계 한 번(7,058.53·9.8·버전 1), 거래소 1회 |
| S3b | 거래소가 응답을 붙잡은 사이 외부 강제 종료 | S3 과 같음 |
| S4 | 체결 관측 커밋 직후 | 대기 표시로 재처리 한 번 |
| S5 | 회계 트랜잭션 안(커밋 전) | DB 롤백 → 재처리 한 번 |
| S6 | 정지 → 늦은 체결 → 정리 매도 절반 체결·대기 중 강제 종료 | 재기동 + CLOSING 시간 초과 + 전체 비상 정지 호출에도 재매도 0, 거래소가 잔량 취소로 끝내면 남은 4.9 만 다시, 합계 9.8, CLOSED·정리 완료 |
| S7 | 정리 매도 접수 후 강제 종료, 그동안 거래소에서 전량 체결 | 재기동 후 대금 한 번(9,997.06), 재매도 0 |

모든 시나리오는 끝에 앱을 한 번 더 강제 종료·재기동하고 주문·잔액·포지션·예약·정리 대기·거래소 기록이 그대로인지
확인한다.
