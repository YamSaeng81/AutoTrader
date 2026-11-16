# V168D4 실행 결과 — 11-16 U0 통과 · U1 JARM 불일치 멈춤 · U1되돌림 중간 멈춤(재기동 없음)

총괄 v1.169(418차) §3 / v1.170(419차) 지시. 출처 **d5790c1c**, 후보 **0e1d2a02**, 판 UB=V168D4. 운영 backend·frontend는 재기동하지 않았다. **U2 이후는 실행하지 않았다.** 운영자 "재기동 진행" 확인은 요청 단계까지 가지 않았다.

## 1. 순서와 종료값 (KST)

| 시각 | 단계 | 종료값 | 원문 |
|---|---|---|---|
| 10:12:30~35 | 10:00 보고·09:30 장부 읽기(읽기만) | 0 | [../n03-1116-1000](../n03-1116-1000/) |
| 10:12:58 | ~/maint-v161 보존(보완판 keep-v161.sh) | 0 | [keep/keep-v161.txt](keep/keep-v161.txt) |
| 10:25:12~33 | **U0** | **0** ✅ | [out/out-U0-deploy.txt](out/out-U0-deploy.txt) |
| 10:25:41~10:27:10 | **U1** | **1** 🔴 JARM 불일치 | [out/out-U1-deploy.txt](out/out-U1-deploy.txt) |
| 10:27:58 | **U1되돌림** | **1** 🔴 frontend 원복 단계 | [out/out-U1되돌림-rollback.txt](out/out-U1되돌림-rollback.txt) |

[out/exit-codes.txt](out/exit-codes.txt)는 실행 틀이 ssh 종료값을 즉시 기록한 것이다. 블록 12개 해시는 [blocks/blocks.json](blocks/blocks.json)(출처 d5790c1c)과 일치했다(실행 틀이 매 실행마다 대조).

## 2. 사전 읽기·보존

- 10:00 보고: 🟢, 4파일 해시가 서버 값과 일치. 설치값·cron·HEAD b5cd1d0·backend 52e12428(재시작0)·frontend 컨테이너는 11-15 22:00과 같음. BTC B1 true·B3 20/20. SID203 CREATED·주문0. SID203 밖 실자금 보유·미결0. ERROR 12 = KRW-BLAST 404(상장 폐지)뿐, 뉴스 미완료 24 = CryptoPanic 403뿐, Hikari 획득 실패·누수0.
- 보존: `~/maint-v161-pre-v168-20261116`에 32파일, 원본·사본 sha256 목록 같음. 11-13 [server-sha256.txt](../v162-run-20261113/server-sha256.txt) 32개와도 같다.
- 보존 스크립트 보완(v1.170 §2): 보완 전 [pre/keep-v161.v1-before.sh](pre/keep-v161.v1-before.sh)(26cb622c), 보완 [pre/keep-v161.sh](pre/keep-v161.sh)(0b8fd38f)·[pre/keep-v161-remote.sh](pre/keep-v161-remote.sh)(f9b09a0d). 가짜 명령 시험 [selftest/selftest.txt](selftest/selftest.txt): 정상0, 원본 목록 실패4, 사본 목록 실패4, 복사 실패4, 불일치5, 기존 폴더 거절3×4, 출력 재사용2. 실패 모두 성공 문구0·임시 폴더 잔여0. 보완 전 판은 목록 실패에서 종료0·✅(가림 재현).

## 3. U0 — 통과

Q3~Q9 기대값(실자금 보유0·미결0·정리 대기0·격리 대상0·66세션 RUNNING63/STOPPED3(지정 종료)·정비 뒤 실자금 매수0·격리 해제0), 손실 세 보호식 모두 허용, BTC true, 설치·이미지 그대로, 경보는 기존 KRW-BLAST 적재 뒤처짐뿐. 정비 시작 2026-11-16 10:25:15 KST.

## 4. U1 — 멈춤: 새 jar 내용 목록(JARM)이 기대값과 다름

- 서버 빌드: jar 881→895, 차이 62줄(이름 목록), app-new.jar 5c53c9e3…, 이미지(목록) e25c2a85…(태그 없음으로 남음).
- **서버 JARM `b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8` ≠ 기대 `7df5758f…`.** 블록이 재기동 전에 멈췄다. 기대값은 바꾸지 않았다.
- 차이 대조([diag/diff97.tsv](diag/diff97.tsv), 서버 목록 [server-maint-v168/jar-manifest-new.txt](server-maint-v168/jar-manifest-new.txt), 기대 목록 [../v168-final-20261115/jars/manifest-0e1d2a02.lf.txt](../v168-final-20261115/jars/manifest-0e1d2a02.lf.txt)):
  - 항목 이름 895개 같음. **.class 등 나머지 798항목 내용 같음.**
  - 다른 항목 **97개 = 텍스트 리소스 전부**(application.yml·logback-spring.xml·마이그레이션 SQL 95개).
  - 97/97: 서버 값 = `git show 0e1d2a02:<원본>`(LF) sha256, 기대 값 = 같은 원본을 CRLF로 바꾼 sha256. 그 밖 0.
  - 서버의 현재 실행 jar(app-old, b5cd1d0)와 새 jar 모두 application.yml에 CR 없음(57f9a47c… = git LF).
- 해석(가설 아님, 위 대조로 확인한 사실): 기대 JARM은 이 개발 PC(Windows, `core.autocrlf=true`) 작업 사본의 bootJar에서 만들어 리소스가 CRLF였다. 서버는 LF 체크아웃으로 빌드한다. 제품 코드(.class)는 같다. **어느 쪽을 기준으로 할지는 총괄 판정 사항이며, 개발 세션은 기대값을 바꾸지 않았다.**

## 5. U1되돌림 — 중간 멈춤, 서버는 U1 전 상태

- 블록 실행 순서: 사전 검사 통과 → `git checkout b5cd1d0` ✅ → `docker tag 52e12428 autotrader-backend:latest` ✅ → **frontend 원복 `docker tag sha256:d825a9f5… autotrader-frontend` 에서 `No such image`로 종료1**. 그 뒤 기록(git-u1rollback.txt)·확인 단계는 실행되지 않았다.
- 원인(읽기 확인): 서버 도커 29.9.2는 containerd 이미지 저장소다. 실행 중 frontend 컨테이너의 `.Image`는 설정 다이제스트 d825a9f5…로 표시되지만, 이 값은 이미지 참조로 쓸 수 없다. 같은 이미지는 `autotrader-frontend:latest` = 837db515…(목록, 2026-09-18 생성)이며 linux/amd64 항목 fa5c9ba8…이 컨테이너 라벨 `com.docker.compose.image`와 같고 사용 중(U) 표시다. U1은 frontend 빌드 전에 멈췄으므로 frontend 태그는 바뀌지 않았다.
- 되돌림 뒤 읽기 확인(10:29:27 KST, 읽기만): HEAD b5cd1d0·추적 변경0, backend latest 52e12428, start_66.py 68d441ce…·U0 보존본과 같음·시험 파일 있음, crontab f2dce280…, backend 52e12428 기동 11-10 재시작0, frontend d825a9f5(컨테이너) 기동 10-21 재시작0, `ok-U1` 없음.
- 블록을 고쳐 다시 실행하지 않았다(원문 고정). 블록의 frontend 원복 비교는 containerd 저장소에서 같은 이미지라도 실패한다. U1 정상 통과 뒤 UF2·U되돌림의 같은 비교도 영향을 받을 수 있다 — 총괄 판정 필요.

## 6. 서버 기록

[server-maint-v168/](server-maint-v168/)은 ~/maint-v168 중 jar 2개·backend-before.log(33MB)를 뺀 파일 원본이다. 서버에는 `~/maint-v168`, `~/maint-v161-pre-v168-20261116`, 태그 없는 이미지 e25c2a85…가 남아 있다(지우지 않음).

## 7. 판정 요청

1. JARM 기준: 서버 LF 빌드 기준 새 기대값을 받을지, 다른 방법으로 입력 동일성을 확인할지.
2. U1되돌림·UF2·U되돌림의 frontend 이미지 참조(containerd 저장소) 처리.
3. 위 판정 뒤 V168D4 재시도 일정(U0부터 다시인지). 그때까지 운영은 b5cd1d0/52e12428 그대로다.
