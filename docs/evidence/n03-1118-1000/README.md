# N03 11-18 10:00 보고·09:30 장부 예약 읽기

[read.sh](read.sh)(총괄 v1.185 §4 확인 해시 41e4f337…)를 인자 `20261118-1000 / n03-1118-1000 / 20261118`로 예약 실행했다([sched](sched/)). 읽기 전용이며 재실행·재전송·수집 호출은 하지 않았다.

- **실제 시각:** 로컬 시작 01:13:18Z(10:13:18 KST), 서버 `10:13:21 KST`, 끝 01:13:24Z. 개별 읽기 표준오류 없음, 전체 `read rc=0`([read-stderr](read-stderr.txt)·[run-time](run-time.txt)).
- **파일 대조:** 보고·msg·raw·last_send 4개의 로컬 해시가 서버 해시와 같다([server-files](server-files.txt)·[local-sha256](local-sha256.txt)). 서버 파일 시각은 10:00:03~04 KST다. 토큰 형식 검사는 모두 0이다([token-check](token-check.txt)).
- **설치 상태:** INSTALLED_SHA c669ccdc, daily_check.sh ff0a5d7d…, daily_checks.sql 653b22ba…, sid_ledger.sql 02a85850…, 서버 HEAD 0e1d2a02, backend 이미지 2c5d8427…, 재시작 0, 기동 11-16 05:16:14Z([install_state](install_state.txt)).

## 보고 판정 (10:00:04 KST 전송 성공)

**🔴 이상 1건 = 미분류 ERROR 1건.** `22:22:56 RssNewsSource 수집 실패 — sourceId=tokenpost_rss: request timed out`이다. 보조 뉴스 RSS의 시간 초과로, 같은 원문 유형이 11-03·11-04·11-12·11-13·11-14 보고에서도 미분류로 나왔다. 스크립트 규칙대로 🔴로 두며 이번 읽기로 원인을 확정하지 않는다. 매매·정산 항목 이상은 없다.

- DB: Flyway 96. SID203 밖 실자금 보유·미결·구간 매수 각 0, 정리 대기·격리 대상 0. 66세션 중 RUNNING 63, 지정 종료 3/3.
- SID203: CREATED·잔액 20200.00·보유0·미결0·정산 확인 불가0·불일치0. BTC H1 입력 B1 true, B3 20/20.
- 🟡: 외부 통신 실패 4, KRW-BLAST 404·보충 수집 실패 각 4, 66세션 적재 뒤처짐 12회(전부 KRW-BLAST). 텔레그램 성공 2·큐 실패 0. ERROR 13줄([error_lines](error_lines.txt)), 수집 미완료는 cryptopanic HTTP_403 24·tokenpost_rss 시간 초과 1([observations-supp](observations-supp.txt)).

## 09:30 장부

[ledger-20261118](ledger-20261118.txt)는 09:30:01 KST 생성이다(서버 해시 34934f2d…). L0 `203 CREATED init_ok=t 20200.00`, L1~L3 0행, L4 balance_diff 0.00·순체결량0=열린 포지션0. 과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

마지막 운영 읽기는 이제 **11-18 10:13:21 KST**(서버 시각)다. 11-17 17:18~11-18 08:18은 계속 미관측이다.
