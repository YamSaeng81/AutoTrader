# N03 11-17 10:00 보고·09:30 장부 읽기 (예약 실행, 서버 10:13:16 KST, 읽기만)

## 실행 여부와 실제 시각

- **예약 실행**: 개발 세션이 백그라운드 작업으로 `wait_then.sh 01:13:00`(UTC) → [sched/n03run.sh](sched/n03run.sh) → [read.sh](read.sh) 를 걸어 두었다. 사본: [sched/wait_then.sh](sched/wait_then.sh) `ebd992cc…`, [sched/n03run.sh](sched/n03run.sh) `07455396…`, 접속 단계 실패(255)만 재시도하는 래퍼 [sched/ssh-shim2.sh](sched/ssh-shim2.sh) `b5afcd75…`(실제 쓰인 경로 [ssh-path.txt](ssh-path.txt)).
- **실제 읽은 시각** [run-time.txt](run-time.txt): 로컬 시작 01:13:14Z(이 셸의 `TZ=Asia/Seoul` 은 UTC 로 찍힘 = 10:13:14 KST), **서버 10:13:16 KST**, 로컬 끝 01:13:20Z(10:13:20 KST). 종료값 **read rc=0**([read-stderr.txt](read-stderr.txt)). 래퍼의 재시도 줄 0 — 이번에는 ssh 접속이 모두 첫 시도에 붙었다.
- [read.sh](read.sh) 는 이전 회차와 같은 명령(회차·폴더·장부 날짜만 인자). 서버 쓰기·재전송·수집 호출 없음. 운영 DB SQL 직접 실행은 하지 않았다 — **DB 직접 확인은 미확인**, DB 값은 아래 보고·장부 값(서버 점검 스크립트가 쓴 것)이다.
- 토큰 패턴 0([token-check.txt](token-check.txt)). 4파일 sha256 = 서버 값([server-files.txt](server-files.txt)·[local-sha256.txt](local-sha256.txt)).

## 결과 (보고 [20261117-1000.txt](20261117-1000.txt), 10:00:03 전송)

보고 머리: 🔴 이상 1건 — 🔴 미분류 ERROR 1, 🟡 외부 통신 실패 12, 🟡 KRW-BLAST 404·보충 수집 실패 4·66세션 적재 뒤처짐 12회.

| 항목 | 값 |
|---|---|
| 설치 [install_state.txt](install_state.txt) | crontab f2dce280…, INSTALLED_SHA c669ccdc…, HEAD **0e1d2a02**, daily_check.sh ff0a5d7d…, 장부 SQL 02a85850… |
| backend | **2c5d8427**, 기동 11-16 14:16:14 KST, 재시작 0, running |
| frontend [frontend-image.txt](frontend-image.txt) | **9d7f123a**, 기동 11-16 16:15:16 KST, 재시작 0, running (조회 10:13:20) |
| DB(보고 값) | Flyway 96, SID203 밖 실자금 보유·미결·정리 대기·격리 0, 66세션 RUNNING 63·지정 종료 STOPPED 3/3, 구간 SID203 밖 실자금 매수 0 |
| SID203 | CREATED · 20,200 · 보유 0 · 미결 0 · 구간 주문 0 · 정산 불일치 0 |
| BTC H1 | B1 true(최신 완료 봉 09:00), B3 20/20 |
| 텔레그램(구간) | 성공 2 · 큐 진입 실패 0 · 마지막 성공 04:26 |
| 로그 범위 | log.gz 13:00:22Z~00:59:40Z(11-16 22:00~11-17 09:59 KST), 구간 시작 전 생성된 같은 컨테이너 |
| 기타 [observations.txt](observations.txt) | 연결 누수·획득 실패 0, DOCTYPE 0, N17/N19/N20 0, CryptoPanic 403 ×23·IOException 1 |

**09:30 장부** [ledger-20261117.txt](ledger-20261117.txt)(09:30:02 생성, `5be6c4f7…`): SID203 CREATED·20,200, 신호·주문·왕복 0, 잔액 차이 0, 서킷브레이커·한도 0. 과거 장부 파일 그대로(maint-n17 기준, [ledger-files.txt](ledger-files.txt)).

## ERROR 21줄 분류 ([error_lines.txt](error_lines.txt), 시각 UTC)

아침 08:01 읽기([morning-20261117](../morning-20261117/README.md), 23:01Z 까지 ERROR 21)와 **같은 21줄**이다. 23:01Z(08:01 KST)~00:59Z 사이 새 ERROR 0, **마지막 ERROR 는 20:00:02Z(05:00 KST)**.

- KRW-BLAST 404 계열 12줄(19:10~19:12Z) — 거래지원 종료 마켓, 기존 경보.
- 통신 실패 8줄(보고의 "통신 실패 12"는 보고 스크립트의 분류 수치):
  - 15:00:49~15:01:19Z(00:00 KST) Upbit 캔들 `request timed out` 3 → IOTA·WAVES·CRO H1 동기화 실패 3
  - 15:50:47Z(00:50 KST) CryptoPanic `Connection reset`
  - 19:26:31Z(04:26 KST) 텔레그램 `응답 미수신: Connection reset` — 보고상 마지막 성공도 04:26 이다. **그 메시지가 실제로 전달됐는지는 확인하지 않았다**(전달 실패로 확정하지 않음).
- **🔴 미분류 1줄** = 20:00:02Z(05:00 KST) `ClaudeProvider 요청 실패` — 아침 읽기에서 REPORT_NARRATION·Connection reset 으로 본 줄. 외부 LLM 호출 실패이며 매매 경로가 아니다. 원인 미확인.

## 판정

- **새 P0 없음.** 실자금 보유·미결 0, 연결 획득 실패·누수·재기동 0, 이미지·기동 불변.
- 지속성: 통신 오류는 00:00~05:00 KST 에 흩어져 있고 05:00 뒤 10:00 까지 재발 0. 이번 10:13 ssh 접속은 재시도 없이 성공 — 아침 07:56~08:05 ssh 끊김은 이 시각에는 재현되지 않았다. 원인 조사는 [netinv-20261117](../netinv-20261117/README.md).
- 영향 분류: SID203 은 CREATED(거래 없음)라 주문 영향 0. 00:00 캔들 3코인 H1 동기화 실패는 BTC 가 아니며 B1·B3 은 정상. 뉴스·LLM 서술·텔레그램 1건은 보조 경로.
- 운영 변경 없음. 실제 운영 생성·T0 없음.
