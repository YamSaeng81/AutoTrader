# U1-되돌림 — 342f461 계획서 U1 아래 한 줄 원문(재기동하지 않는다). D1 에서는 OLD=ee509cf·image-before=#25 이미지.
git checkout --quiet $OLD; docker tag "$(cat "$M/image-before.txt")" $REPO:latest; git rev-parse HEAD; docker image inspect --format '{{.Id}}' $REPO:latest
