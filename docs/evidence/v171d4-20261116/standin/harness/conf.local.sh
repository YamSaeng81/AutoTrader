# V171D4 대역 값 — 정상 jar 를 서버 LF 빌드로 바꿨다. 이하 원래 주석
# V168D4 대역 값(22:35, v167d4s 사본에서 NEW·jar·DIFFN 만 교체) — 후보 0e1d2a02·그 로컬 bootJar(be723125…, JARM 7df5758f 동일)·git diff --name-only b5cd1d0 0e1d2a02(133경로). 이하 원래 주석: V167D4 대역 값 — 후보 99e6ab10(candidate/order-poll-race-b987d7a7)·그 로컬 bootJar(f7450f13…)·실제 git diff --name-only b5cd1d0 99e6ab10(130경로). V161D4 대역에서 NEW·jar·DIFFN·판 표지·기록 폴더만 다르다
D2_NEWC=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
D2_JAR_NEW=/d/tmp/v171/jars/srv-e25c2a85-app-new.jar   # V171D4: 서버가 0e1d2a02 로 빌드한 실제 jar(5c53c9e3…, JARM b6587953…, ~/maint-v168/app-new.jar). 종전 로컬 bootJar(CRLF, JARM 7df5758f…)는 JARM 거절 사례에 쓴다
D2_DIFF="$(cat /d/tmp/v168-ev/d4/diffn-b5cd1d0-0e1d2a02.txt)"
