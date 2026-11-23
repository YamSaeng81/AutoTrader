# D81A2 새 실행 출처 — 수용한 U0 보정본을 추출·실행 틀에 연결 (총괄 v1.227 §3-1)

2026-11-23 14:40~14:50 KST, Claude Code(개발 세션)가 제출했다. **총괄 판정 전이며 배포 실행 승인이 아니다.** 로컬 Git·로컬 파일·가짜 원격 프로그램만 썼다. 운영 SSH·DB·Docker·PG·빌드·회귀는 쓰지 않았다. 기존 [d81d4r-20261120](../d81d4r-20261120/README.md)(cd8bd76f)·[a2-fail-export-20261123](../a2-fail-export-20261123/README.md) 폴더는 바꾸지 않았다.

## 1. 새 고정 출처 — 커밋 65a384e563a90d949c94a4e343ea491939e6f5f7

| 항목 | 내용 |
|---|---|
| 블록 | [d4/blocks-d81/](d4/blocks-d81/) 9개. **U0만 91ea9342…**(수용한 A2 보정본과 바이트 동일). U변수 efb96108·U1·U1되돌림·U2·U3·U4·U되돌림·U뒤관측은 cd8bd76f에서 `git show`로 꺼낸 바이트 그대로다(해시 동일) |
| 해시 목록 | [d4/blocks-d81.sha256](d4/blocks-d81.sha256) — cd8bd76f 판에서 U0 줄 1개만 바뀜 |
| 추출기 | [runner/extract.py](runner/extract.py) — cd8bd76f 판에서 블록 경로(`d81a2-src-20261123`)·U0 고정값·주석만 바뀜([차이](runner/extract-vs-cd8bd76f.diff)). F·G(7c62a7db)·나머지 고정값·블록 목록 검사·11개 검사는 그대로 |
| 실행 틀 | [runner/run.sh](runner/run.sh) — U0 고정값·주석·시작 줄의 판 표지(`D81D4R`→`D81A2`)만 바뀜([차이](runner/run-vs-cd8bd76f.diff)). 전송 줄·기록 계약·기본 경로·`~/maint-d81`·허용 블록 조합은 같다 |
| 안 바꾼 것 | 제품 d81c3f2f·SQL·pf3ok·OLD/NEW·JARN/JARM·관문 4판·운영 호출 줄. F·G·U변수·나머지 7개 U블록 바이트 |

**고정 커밋에서 다시 꺼낸 결과:** 65a384e5의 extract.py로 같은 커밋에서 11블록을 꺼내 **11/11 고정값 일치, 종료 0**([blocks.json](runs/r2/blocks.json): source 65a384e5·fg 7c62a7db). 최종 승인 목록은 이 커밋과 이 폴더 경로를 가리킨다. 이 README·시험 파일은 뒤의 별도 커밋이며, 그 SHA는 제출 기록에 적는다.

기록 실패 계약은 run.sh 차이가 고정값·주석·표지 문자열뿐이므로 **코드 동일성**으로 cd8bd76f의 [영향 시험 21/21](../d81d4r-20261120/runner/runner-selftest-r2.txt)에 연결한다. 9/21/27/201건·PG·회귀·빌드는 반복하지 않았다.

## 2. 영향 확인 — 3경계 (+ 시작 전 가드 거절 5건)

시험 구조는 수용된 [A1 r1](../a1-fake-boundary-20261123/README.md)의 방식을 그대로 따른다.

- [test/make-test-copy.sh](test/make-test-copy.sh)가 **고정 커밋의 run.sh**에서 시험 사본을 기계적으로 만든다. 바뀌는 곳은 ① 표지 1줄 ② `set -u` 뒤 [guard.inc](test/guard.inc) ③ `D=` 줄(기본 경로·원격 도구 변수 제거) ④ 원격 호출 1줄 → `"$FAKE" "$TEST_HOST" …`뿐이다([차이](runs/r2/prod-vs-test-copy.diff)). 주석 아닌 줄에서 따옴표 문구를 뺀 뒤 원격 도구 이름·운영 별칭·`$SSH`가 남으면 생성 실패.
- 가드: 가짜는 키트의 `fake/fake-ssh.sh` 절대 경로 하나·해시 고정, 대상은 `d81-test.invalid`만, Windows 형식/빈 항목/상대 PATH·상속 함수·BASH_ENV·`D4SSH` 거절. 기본값/대체 실행 없음.
- [fake-ssh.sh](test/fake-ssh.sh)는 받은 명령 문자열을 파일로 남기고 고정 응답 1줄만 낸다(명령을 실행하지 않는다). PATH 맨 앞 감지용 대체 파일(ssh·scp·sftp·curl·wget·nc·telnet·docker·psql·git)은 불리면 기록하고 97로 끝난다.
- [test/run-cases.sh](test/run-cases.sh)가 `env -i` + `bash --noprofile --norc`로 실행하고, 시작 전 `command -v ssh`가 감지용 대체 파일인지 확인한다([preflight](runs/r2/preflight.txt)). 생산용 run.sh 자체와 실제 원격 도구는 실행하지 않았다.

### r2 결과 — [results.tsv](runs/r2/results.tsv) · 8/8, 감지 호출 0

| 사례 | 종료 | 가짜 호출 | 확인 |
|---|---|---|---|
| **b1 정상 새 출처** | 0 | **1** | 받은 명령 = `bash -c` + F+G+U변수+U0(91ea9342) 원문, 같은 환경에서 만든 기대 문자열과 **바이트 동일**, 마지막 문자 `)`. exit-codes 1줄·미완료 표지 없음 |
| **b2 옛 U0(736351db) 혼입** | 2 | **0** | "블록 해시가 고정값·blocks.json 과 다름", out 폴더 미생성 |
| **b3 새 U0 1바이트 변조** | 2 | **0** | 같은 거절, out 폴더 미생성 |
| g1~g5 가짜 누락·다른 경로·다른 해시·Windows PATH·운영 별칭 대상 | 2 | 0 | 가드 거절 문구가 사유와 일치 |

### r1 — 구동기 기대값 결함으로 실패 보존(7/8)

r1에서는 b1만 `cmd_match=DIFF`였다. 실행 틀 결함이 아니라, 구동기가 자기 셸(로케일 미설정)에서 `printf %q`로 기대 문자열을 만들어 한글이 바이트 이스케이프로 바뀐 탓이다(실행 틀은 `LANG=C.UTF-8` 안에서 한글을 그대로 둔다. 기대 110,385바이트 / 실제 84,951바이트, 첫 차이 126번째 글자 = 첫 한글 주석). 구동기만 고쳐(기대 문자열을 같은 `env -i` 환경에서 생성) r2를 새로 돌렸고 성공 수를 합치지 않는다. r1 원문은 [runs/r1](runs/r1/)에 그대로 둔다.

## 3. 한계

- 시험 사본의 검증이다. 생산용 전송 줄(실제 원격 도구 호출)·서버 쪽 실행·`$DC exec`·실제 psql은 검증하지 않았다. 네트워크 차단 환경은 확보하지 않았고, 절대 경로 강제·정적 검사·감지용 대체 파일로 대신했다(감지 파일은 PATH 경유 호출만 잡는다).
- 판정 파일 기록 실패의 rc1·고정 설명 누락은 v1.227 §1대로 진행 차단이 유지되는 알려진 한계이며 고치지 않았다. 실행 틀은 원격 rc와 기록 실패를 따로 남기고 자동 재시도하지 않는다(cd8bd76f와 동일 코드).
- 회차 원문 압축: [runs/r1/full-r1.tar.gz](runs/r1/full-r1.tar.gz) · [runs/r2/full-r2.tar.gz](runs/r2/full-r2.tar.gz)(시험 키트·추출물·사례별 실행 폴더 포함). 체크섬은 [SHA256SUMS](SHA256SUMS).

## 4. 다시 실행하는 방법

`bash test/run-cases.sh r3` — 새 회차 이름만 받는다(`/d/tmp/d81a2-run-<회차>`가 있으면 거절). 같은 r2를 시간 채우기로 반복하지 않는다.
