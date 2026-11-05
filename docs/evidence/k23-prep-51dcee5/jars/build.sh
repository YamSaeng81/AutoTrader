set -e
cd "/d/Claude Code/projects/cat-n15"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
for ref in fe01207 candidate/n15-n16-integration-fe01207; do
  git checkout -q --detach $ref
  sha=$(git rev-parse HEAD); short=${sha:0:7}
  [ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "dirty"; exit 1; }
  date "+$short build start %H:%M:%S"
  ./gradlew :web-api:clean :web-api:bootJar -x test --offline > /d/tmp/n15final/jars/build-$short.log 2>&1
  date "+$short build end %H:%M:%S"
  j=$(ls web-api/build/libs/*.jar | grep -v plain)
  echo "$short $j"
  cp "$j" /d/tmp/n15final/jars/$short.jar
done
git checkout -q candidate/n15-n16-integration-fe01207
git log --oneline -1
