# U1-되돌림 — LIMITED_LIVE_TEST_PLAN.md §98(#26) U1 아래 한 줄 원문 그대로(재기동하지 않는다). D2 에서는 OLD=1899b49·image-before=#26 이미지.
git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format '{{.Id}}' $REPO:latest
