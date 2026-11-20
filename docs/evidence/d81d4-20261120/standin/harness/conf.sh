# D81D4 대역 공통 값(총괄 v1.198 §3-5) — v172d4s/sim 장치를 옮겨 원판을 현재 운영(0e1d2a02·backend 2c5d8427·frontend 9d7f123a·V96)으로 바꿨다.
# 실제 서버 값은 D81D4 U변수 원문에 있다. 2c5d8427·9d7f123a 의 save 원문(manifest·config 바이트)은 보존본에 없어 대역은 합성 이미지로 등록하고
# 그 대상·manifest·config 를 vars.sh 로 대역 치환한다(실제 값의 정합은 총괄 v1.198 이 427차 보존 해시로 대조한 범위로 연결, 대역 범위 밖).
MIDC=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e   # 예상 밖 HEAD 사례용(로컬에 있는 다른 커밋)
REPOSRC="/d/Claude Code/projects/crypto-auto-trader"   # start_66.py 원문을 꺼내는 로컬 저장소(읽기만)
R=/d/tmp/d81d4s/sim
OLDC=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
NEWC=d81c3f2f91c0239aacb0e1176dde9b898f4d7466
QSC=fa0691e647b61f6df4a85bdf796996f50470185e
# 원판 backend(대역 합성, imgstate build backend op-0e1d2a02-2c5d8427 — 결정적)
I26=sha256:bbce251cb3a356cccf770e931b61d7ff4442c06ce1962288c4745ba232195ea1
I26M=sha256:e9aac1bc758460c1337242383c235ecf8990395c944b077f7be9cd04c9720528; I26C=sha256:be2ec5a7e3161477906772109bf08c462ed28fed304cfd773986e104f6b74a86
# 원판 frontend(대역 합성, imgstate build frontend op-fe-9d7f123a) — 11-16 UF2 가 새로 만든 컨테이너라 .Image = 대상 ID
FET0=sha256:fb15d35de3df2dc4533192e3288fbaea2623e9f03b353519fb6e93e4568cb8e4
FEM0=sha256:c52ed321e482406a2fd200b68640d21b02cb270dff58266570488f212f25f6fb; FEC0=sha256:625146864246046cb4cc993d0f9febd72e1e65aadca6c06a22f81bd37d4785f5
FEI0=$FET0
# 옛 보존 태그(실제 값 — U변수 IMGPREV9·FEPREV9 와 같다)
P9=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055; FP9=sha256:837db515e0eb8b9cb4bcf8f03ed3b98adf9f8cdb176d13777949c792d10b5017
JAR_OLD=/d/tmp/v171/jars/srv-e25c2a85-app-new.jar   # 서버가 0e1d2a02 로 빌드한 실제 jar(5c53c9e3…, ~/maint-v168·v172 app-new) = 현재 운영 backend 의 jar
JAR_NEW=/d/tmp/final-lf-d81c3f2f/web-api/build/libs/web-api-0.1.0.jar   # 후보 d81c3f2f LF jar(eae7e1d2…, JARM 33a06f0e… — 447차 수용, 재빌드하지 않음)
JAR_CRLF_OLD=/d/tmp/v171/jars/srv-52e12428-app-old.jar   # JARM 거절 사례용 다른 jar(b5cd1d0 서버 jar)
BLOCKS=${D2_BLOCKS:-/d/tmp/d81d4s/blocks-d81}
FG=/d/tmp/d81d4s/fg   # 7c62a7db 의 F(26d39269…, 현재 운영 NEW 핀)·G(4d7ff02a…) — 실행 틀 extract 와 같은 원문
GSC=e97ca31b23dec54db7c962395d2f8e60cc92afb0
GSD=/d/tmp/d81d4s/gs-e97ca31b   # git show e97ca31b:<경로> 원문 3개(gate.sql b24239f8…·rollback_gate.sh 0b391e58…·predeploy v3 ca6e7c8d…)
GTPX=docs/evidence/dec-costbasis-followup-20261119/rollback-gate-v4-draft
PFPX=docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql
V96SUMX=-424836241
D2_DIFF="$(cat /d/tmp/d81d4s/diffn-0e1d2a02-d81c3f2f.txt)"   # git diff --name-only OLDC NEWC 43경로(미리 만든 파일 — 가짜 git 안에서 실제 git 을 부르지 않는다)
