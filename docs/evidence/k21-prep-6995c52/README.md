# 개입 #21 (N12) 준비 — 실제 jar 대조와 L 블록 로컬 확인 (계획 §5-9 61)

2026-11-04 KST. 총괄 v1.92(§5-9 60)의 지시에 따른 로컬 준비다. 운영 DB·외부 HTTP 에는 접속하지 않았다. 운영 설치도 하지 않았다. 운영자는 총괄이 검토하기 전에는 L 블록을 실행하지 않는다.

**서버 접속 1회(보고)**: 준비 중 `ssh cat-prod 'python3 --version'` 을 한 번 실행했다. 결과는 `Python 3.10.12` 다. 새 함수의 문법이 서버 python 에서 도는지 보려던 것이다. 읽기 전용이었고 다른 명령은 없었다. 절차 검토 전에는 서버를 실행하지 않는다는 범위에 들어가므로 따로 적는다. 함수는 이 결과와 관계없이 3.8 미만에서도 도는 문법으로 썼다(대입식 `:=` 를 쓰지 않음).

| 항목 | 값 |
|---|---|
| 지금 운영(복구 기준) | `b1d7664db73222aec5d86f2e9137debef0ff4df3`, 이미지 `sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6`(`~/maint-n11/image-new.txt`) |
| 후보 | `6995c52259e3de4c7c922a32f9fb2aa9ddd49f00`(브랜치 `n12-http-log-body`, b1d7664 가 조상) |
| 변경 경로 | 3개(운영 코드 2·시험 1), 마이그레이션 없음 |
| 서버 기록 폴더 | `~/maint-n12`(새로 만듦). `~/maint-n11`·`~/maint-n08` 은 읽기만 |
| 보존 태그 | 이전 `autotrader-backend:ea44900`(→ `61804654…`) 그대로, 새로 `autotrader-backend:b1d7664`(→ 지금 이미지) |

## 1. 실제 bootJar 대조 — 이전 기대값 재사용 없음

두 작업 사본에서 `./gradlew :web-api:clean :web-api:bootJar -x test --offline` 를 새로 돌렸다(15:34 KST 완료). 운영 Dockerfile 의 `gradle :web-api:bootJar -x test` 와 같은 작업이다.

- `cat-n11`: HEAD `b1d7664`, 추적 파일 변경 없음.
- `cat-n12`: HEAD `6995c52`, 추적 파일 변경 없음.
- jar sha256·크기는 `jar-sha.txt` 에 있다. jar 원본(각 약 90MB)은 크기 때문에 Git 에 넣지 않았다. 이 PC 의 스크래치 `n21/jars/` 와 두 작업 사본의 `web-api/build/libs/` 에 있다.

비교 코드는 계획 L1 블록에서 그대로 꺼냈다. §55 K1 비교 코드와 바이트가 같다(`l1-check.txt`).

| 비교 | 출력 | 파일 |
|---|---|---|
| L1 비교 코드(바깥 jar 의 디렉터리가 아닌 항목 전부, 자체 모듈 3개는 풀어서) | `변경 …/CryptoPanicSource.class`, `변경 …/UpbitNoticeSource.class`, `(끝) 879 879` | `jar-diff-l1.txt` |

- 추가·삭제는 없다. 설정·리소스·마이그레이션 SQL·외부 라이브러리·로더·바깥 `META-INF`·`classpath.idx` 는 같다.
- b1d7664 쪽 879 는 #20 서버 K1 의 새 쪽 879 와 같다.
- `l1-check.txt`: 이 출력이 L 변수의 `LARX` 와 같고 `(끝) 879 879` 이며, L 변수 `DIFF3` 가 `git diff --name-only b1d7664 6995c52` 와 같음을 L 변수 블록을 그대로 읽어 확인했다.
- K1 의 jar 변형 거부 시험(바깥 MANIFEST·`classpath.idx`·로더)은 반복하지 않았다. 비교 코드가 §55 와 같기 때문이다.

## 2. L 블록 — K 블록에서 정해진 치환만

`blocks/` 에 계획 §5-9 61 의 L 블록 8개를 추출했다. 8개는 L 변수, L0, L1, L2, L3, L4, L 뒤 뉴스 관측, L 되돌림이다.

- 문서에서 꺼낸 블록이 생성본과 같고, `bash -n` 8/8 통과(`blocks-syntax.txt`), 블록별 sha256 은 `block-hashes.json` 에 있다.
- 계획 문서의 bash 블록은 76개에서 84개가 됐다. 앞 76개는 순서·바이트가 그대로다.
- `k-to-l.diff`: 원문 블록(§55 K 변수·K1·K3·K4·K 뒤, §53 K0·K2·K 되돌림)과 L 블록의 차이 전체다. 바뀐 것은 아래뿐이다.
  - 이름: `ok-K*`→`ok-L*`, 문구의 K0~K4→L0~L4, K1-되돌림→L1-되돌림, `KV=55`→`LV=61`(L 블록 8개 모두 확인).
  - 값: `OLD`·`NEW`·`IMGOLD`, 폴더 `~/maint-n12`, 이미지 기록 `~/maint-n11/image-new.txt`, 보존 태그 `:b1d7664`, `DIFF3`, `LARX`, `(끝) 879 879`.
  - 더한 것: `IMGPREV` 와 L0 의 이전 복구 태그 확인 1줄, `httpobs` 함수와 호출(L0 기준선 기록·L3·L4·L 뒤), L4 마지막 안내 1줄, L2·L 되돌림의 판 표지 확인 1줄.
- `kwin`·`newsobs`·`btcok`·`btcgate`·`NEWSERR`·`NEWSCFG`·장부/점검/cron 해시는 §55 와 바이트가 같다. 그래서 `newsobs` 출력에 "K0 때"라는 문구가 남는다. 이번에는 L0 때 설정을 뜻한다.

## 3. `httpobs` 국소 사례 — 16/16 기대와 같음(`httpobs-cases.txt`)

L 변수 블록 원문을 그대로 읽고 `python3` 만 이 PC 의 python 으로 바꿔 돌렸다. 입력은 `cases/*.txt` 다. 실행기는 스크래치에만 두고 저장소에 넣지 않았다.

| 사례 | 기대 | 결과 |
|---|---|---|
| c01 옛 CryptoPanic 형식 + HTML 연속 줄 3줄 | 1 | 같음 |
| c02 새 CryptoPanic 403 + 상위 짝 | 0 | 같음 |
| c03 새 Upbit 503 + 짝 | 0 | 같음 |
| c04 CoinGecko 429 + 짝 | 0 | 같음 |
| c05 RSS 404 + 짝 | 0 | 같음 |
| c06 새 형식, 상위 짝 없음 | 3 | 같음 |
| c07 새 형식 뒤 연속 줄 | 1 | 같음 |
| c08 비200 없음(다른 ERROR·스택 줄만) | 0, "미관측" | 같음 |
| c09 옛 Upbit 앞 120자 형식(한 줄) | 1 | 같음 |
| c10 새 CryptoPanic 정상 + 옛 Upbit | 1 | 같음 |
| c11 짝의 sourceId 다름 | 3 | 같음 |
| c12 짝의 상태 다름 | 3 | 같음 |
| c13 docker 머리 없는 로그 | 0 | 같음 |
| c14 반복(CryptoPanic 2·Upbit 1) | 0 | 같음 |
| c15 #20 보존 실제 줄(`k20-result-b1d7664/news-log-lines.txt` 사본) | 1 | 같음 |
| 인자 없음 | 3 | 같음 |

- 모든 사례에서 출력에 본문 내용(`DOCTYPE`·`<html`·가짜 비밀 값·`Just a moment`)이 나오지 않았다. 실행기가 이를 따로 검사했다.
- c15 는 #20 운영 로그에서 HTML 연속 줄을 뺀 사본이다. 그래서 "연속 줄 0줄"이지만 꼬리 형식만으로 🔴 가 된다.

## 4. 하지 않은 것과 한계

- 운영 실행, 서버 docker 빌드, 운영 DB·외부 HTTP 접속은 하지 않았다(위 python 버전 조회 1회 제외).
- 기존 뉴스 시험·167건·전체 리허설·K1 jar 변형 시험은 반복하지 않았다.
- L0~L4 전체를 대역으로 돌리는 가짜 실행은 하지 않았다. K 블록과 같은 줄은 #20 에서 실제로 돌았고, 새로 더한 `httpobs` 만 위 사례로 확인했다.
- `httpobs` 의 "뒤 5줄 안 짝" 은 같은 스레드의 연속 기록을 전제로 한다. 다른 스레드 줄이 많이 끼면 ⚪ 가 나올 수 있다. ⚪ 는 멈춤이 아니라 보고 대상이다.
- 배포 뒤 비200 이 자연 발생하지 않으면 N12 운영 관측은 "미관측"으로 남는다. 실패를 유도하지 않는다.
- F 변수·F1·최종 S·d491fd1·장부·cron·SID 203 `CREATED`·12-01 08:50 은 그대로다.
- **사용 시간**: 15:32 KST 착수(첫 시각 확인)부터 이 근거를 담은 커밋 시각(Git 기록)까지다. 이 근거를 담은 main 커밋의 SHA 는 적지 않는다(N06).

## 5. 파일

| 파일 | 내용 |
|---|---|
| `jar-sha.txt` | 로컬 bootJar 두 개의 sha256·크기 |
| `jar-diff-l1.txt` | L1 비교 코드 출력 |
| `l1-check.txt` | 비교 코드 동일·`LARX`·`(끝)`·`DIFF3` 확인 |
| `blocks/*.sh` | 계획 §5-9 61 L 블록 8개 추출본 |
| `block-hashes.json`, `blocks-syntax.txt` | 블록 해시·줄 번호, `bash -n` 결과 |
| `k-to-l.diff` | 원문 K 블록 → L 블록 차이 전체 |
| `httpobs-cases.txt`, `cases/*.txt` | `httpobs` 16사례 출력과 입력 |
| `.gitattributes`(`* -text`), `SHA256SUMS` | 원본 바이트 보존과 체크섬 |
