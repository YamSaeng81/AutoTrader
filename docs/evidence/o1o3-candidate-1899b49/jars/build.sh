# 깨끗한 작업 사본에서 bootJar(시험 제외). 인자: <작업 사본> <이름>
set -e
W="$1"; N="$2"; O=/d/tmp/o1o3/jars
cd "$W"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
git rev-parse HEAD > $O/build-head-$N.txt; git status --porcelain > $O/build-status-$N.txt
[ -s $O/build-status-$N.txt ] && { echo "dirty $N"; exit 1; }
date "+$N build start %F %T" >> $O/build-time.txt
./gradlew :web-api:clean :web-api:bootJar -x test --offline > $O/build-$N.log 2>&1; echo "exit $?" >> $O/build-$N.log
date "+$N build end %F %T" >> $O/build-time.txt
j=$(ls web-api/build/libs/*.jar | grep -v plain); cp "$j" $O/$N.jar; echo "$N $j"
