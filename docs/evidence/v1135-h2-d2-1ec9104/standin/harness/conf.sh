# O1·O3 초안 D2 대역 공통 값(총괄 v1.135 §5-3). 가짜 명령·init·sim 이 읽는다. 실제 서버 값은 D2 U변수 원문에 있고 여기서 바꾸지 않는다.
R=/d/tmp/o13d2/sim
. "$R/conf.local.sh"   # D2_NEWC(최종 SHA)·D2_JAR_NEW(그 SHA 의 로컬 bootJar)·D2_DIFF(OLD..NEW 변경 경로, 줄바꿈 구분)
OLDC=1899b49f633f8aefdc66b15c42debdaba8b3852c
NEWC=$D2_NEWC
QSC=fa0691e647b61f6df4a85bdf796996f50470185e
# 실행 중 #26 이미지 — D2 U변수 IMGOLD 원문 값을 대역 이미지 이름으로 그대로 쓴다(실제 이미지를 만들거나 추정하지 않는다)
I26=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1
JAR_OLD=/d/tmp/o1o3/jars/1899b49.jar   # 보존 실제 jar(a4fd4e34…)
JAR_NEW=$D2_JAR_NEW
BLOCKS=${D2_BLOCKS:-/d/tmp/o13d2/blocks-d2}
FG=/d/tmp/o13d2/fg
