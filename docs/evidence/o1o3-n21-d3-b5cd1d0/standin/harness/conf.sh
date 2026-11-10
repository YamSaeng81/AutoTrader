# O1·O3+N21 초안 D3 대역 공통 값(총괄 v1.139 §107, D2 대역 장치를 옮김). 가짜 명령·init·sim 이 읽는다. 실제 서버 값은 D3 U변수 원문에 있고 여기서 바꾸지 않는다.
MIDC=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e   # 예상 밖 HEAD 사례용(N21 실패 시험 커밋)
REPOSRC="/d/Claude Code/projects/crypto-auto-trader"   # start_66.py 원문을 꺼내는 로컬 저장소(읽기만)
R=/d/tmp/o13n21d3/sim
. "$R/conf.local.sh"   # D2_NEWC(최종 SHA)·D2_JAR_NEW(그 SHA 의 로컬 bootJar)·D2_DIFF(OLD..NEW 변경 경로, 줄바꿈 구분)
OLDC=1899b49f633f8aefdc66b15c42debdaba8b3852c
NEWC=$D2_NEWC
QSC=fa0691e647b61f6df4a85bdf796996f50470185e
# 실행 중 #26 이미지 — D2 U변수 IMGOLD 원문 값을 대역 이미지 이름으로 그대로 쓴다(실제 이미지를 만들거나 추정하지 않는다)
I26=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1
JAR_OLD=/d/tmp/o1o3/jars/1899b49.jar   # 보존 실제 jar(a4fd4e34…)
JAR_NEW=$D2_JAR_NEW
BLOCKS=${D2_BLOCKS:-/d/tmp/o13n21d3/blocks-d3}
FG=/d/tmp/o13n21d3/fg
