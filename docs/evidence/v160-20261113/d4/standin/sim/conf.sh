# SMA20 PAPER 후보 초안 D4 대역 공통 값(총괄 v1.144 H, D3 대역 장치를 옮김). 가짜 명령·init·sim 이 읽는다. 실제 서버 값은 D4 U변수 원문에 있고 여기서 바꾸지 않는다.
MIDC=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e   # 예상 밖 HEAD 사례용(로컬에 있는 다른 커밋)
REPOSRC="/d/Claude Code/projects/crypto-auto-trader"   # start_66.py 원문을 꺼내는 로컬 저장소(읽기만)
R=/d/tmp/v160d4/sim
. "$R/conf.local.sh"
OLDC=b5cd1d0d47ea68ffe6ff476b89cf9fca5bef1b88
NEWC=$D2_NEWC
QSC=fa0691e647b61f6df4a85bdf796996f50470185e
# 실행 중 #27 이미지 — D4 U변수 IMGOLD 원문 값을 대역 이미지 이름으로 그대로 쓴다(이름 I26 은 D3 장치의 변수 이름을 그대로 둔 것)
I26=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055
JAR_OLD=/d/tmp/o1o3/jars/b5cd1d0.jar   # 보존 실제 jar(a7ef759f…)
JAR_NEW=$D2_JAR_NEW
BLOCKS=${D2_BLOCKS:-/d/tmp/v160d4/blocks-d4}
FG=/d/tmp/sma20pd4/fg
# V159D4 덧붙임 — frontend 대역 이미지(실행 중 구판)·관문/조회문 고정 커밋 대역 값·V96 Flyway 체크섬
FEI0=sha256:$(printf '%s' standin-frontend | /usr/bin/sha256sum | cut -c1-64)
GSC=6385e1d5f64eb09a95791ba253a30d2b21341082   # V160D4: 실제 GS(총괄 v1.160 §5). 대역 git 은 이 커밋에서 실제로 꺼내 해시를 확인한 사본(GSD)을 사례마다 복사해 내준다
GSD=/d/tmp/v160/gs-6385e1d5   # git show 6385e1d5:<경로> 원문 3개(gate.sql c9dc2056…·rollback_gate.sh fe19aab4…·preflight v2 af4105c1…)
EVD="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/v159-20261113"
V96SUMX=-424836241
