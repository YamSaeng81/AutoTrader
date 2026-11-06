# §98(#26 최종안) 절을 LIMITED_LIVE_TEST_PLAN.md 의 "## 6. 시작·중단·종료 조건" 바로 앞에 넣는다. 블록 원문은 blocks-k26 파일 그대로.
import pathlib, sys
DOC = pathlib.Path('D:/Claude Code/projects/crypto-auto-trader/docs/LIMITED_LIVE_TEST_PLAN.md')
B = pathlib.Path('D:/tmp/n20c/blocks-k26')
blk = lambda n: '```bash\n' + (B / (n + '.sh')).read_text(encoding='utf-8') + '```\n'

head = """**98. N20 #26 최종 배포·복구안(UB=98) 고정 (2026-11-06, v1.130 — 개발 결과, 총괄 최종 출처 검토 전·실행하지 않는다)**

- **범위**: §97 지시 1~5만 했다. 실제 시간은 11-06 낮(13:17 KST 확인 시점 전후)이다. 서버·DB 접속, 빌드·재기동은 하지 않았다. 운영은 ee509cf / `200f2f57…` 그대로다. 근거는 [n20-k26-final-1899b49](evidence/n20-k26-final-1899b49/README.md)다.
- **고정 값**
  - 기점 `ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8`, 후보 `1899b49f633f8aefdc66b15c42debdaba8b3852c`(원격 `candidate/n20-held-mark-ee509cf` 와 같다).
  - 변경 경로는 2개다: `HeldMarkRefresher.java`, `N20HeldMarkItemIsolationTest.java`. 마이그레이션·설정은 없다.
  - `IMGOLD` = #25 실제 이미지 전체 ID `sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6`. U0 는 실행 이미지와 `~/maint-n19/image-new.txt` 가 이 값과 같은지 확인한다.
  - 기록 폴더는 `~/maint-n20` 이다. `~/maint-n19` 는 읽기만 한다. U1 이 새 보존 태그 `autotrader-backend:ee509cf`(= IMGOLD)를 만들고, 이전 태그 `autotrader-backend:7a94472`(= `e51b512a…`)는 U0 가 확인만 한다.
- **D2 대비 바뀐 줄 12줄**(블록 8개 중 8개, 생성기는 지정 횟수 일치를 강제)
  - 판 표지 `UB=N20D2` → `UB=98`(U 변수 1줄, U0 1줄, U1·U2·U3·U4·U 뒤 관측·U 되돌림 각 1줄).
  - `IMGOLD=미정` → 위 전체 ID(1줄). U0 의 `sha256:` 형식 검사는 그대로 두고 멈춤 문구만 고쳤다(1줄).
  - 설명 주석 2줄(RARX 출처, n20obs 머리).
  - 그 밖의 로직은 D2 와 같다. U3·U4 P0 검사(holdobs)는 로그 저장 바로 다음 줄에서 한 번만 돈다. 가격·주문·호가·기한 규칙은 바꾸지 않았다.
- **확인**
  - `bash -n` 8/8. 판 표지 검사는 6개 블록 각각 `98` 통과, `N20D2`·`92`·빈 값 멈춤이다. U0 IMGOLD 검사는 이 값 통과, `미정` 멈춤이다.
  - jar 기대는 재사용한다. 보존 실제 jar ee509cf `4f4f42aa…`·1899b49 `74fd7c83…` 해시가 v1.126 기록과 같고, 결과는 변경 2항목(`HeldMarkRefresher`, `HeldMarkRefresher$Book`)·`(끝) 881 881` 이다. 서버 U1 은 #25 서버 jar(`89b4ca17…`)와 새 빌드를 같은 방식으로 비교한다.
  - 제품 코드가 같으므로 114건·173건 시험, 전체 대역, jar 빌드는 반복하지 않았다.
- **실행 틀**(§97 지시 4): 다음 실행부터 출력 파일 이름에 단계와 `deploy`/`rollback` 을 넣고, 같은 이름이 있으면 거절 종료값을 그대로 보고한다. 보존 원문은 고치지 않았다.
- **판단 요청**: 이 판을 담은 커밋의 40자리 SHA 를 #26 고정 실행 출처로 검토해 실행 여부와 시점을 판정한다. 판정 전에는 D1·D2·이 판 모두 서버에서 실행하지 않는다.
- **유지**: SID 203 `CREATED`·최종 S 미확정·한도/풀/장부/cron/점검 c669 불변·12-01 08:50 KST 목표. 연결 획득 시간 초과는 수준과 무관하게 P0 즉시 보고·다음 단계 중단. 자동 되돌림은 추가하지 않는다.

**U 변수** — 🔴 새 셸마다 §5-8 **F 변수**(v1.129 판) → §5-9 의 **G 변수** → 이 블록 순서로 실행한다. F 변수가 `BTCIN`·`PSQL`·`DC`·`sc`·`svc` 를, G 변수가 `qc`·`zero5`·`alertsok`·`sid203`·`REPO`·`QS`·`POOLX`·`NOT404` 를 준다. 이 블록이 `M`·`OLD`·`NEW` 를 덮어쓴다. H~T 변수는 넣지 않는다.
"""

parts = [head, blk('U변수'),
 '\n**U0. ⏸ 직전 확인 — 읽기 전용, 재기동 없음** (총괄 실행 판단 뒤)\n', blk('U0'),
 '\n**U1. 새 이미지 빌드와 jar 대조 — 재기동 없음**\n', blk('U1'),
 "\n- **U1-되돌림**: 멈춤 문구에 \"U1-되돌림\" 이 있을 때만 한다(`git checkout` 뒤). `git checkout --quiet $OLD; docker tag \"$(cat \"$M/image-before.txt\")\" $REPO:latest; git rev-parse HEAD; docker image inspect --format '{{.Id}}' $REPO:latest` → `ee509cf8…`·`image-before.txt`(= `200f2f57…`) 와 같은 ID. 재기동하지 않는다. 그 밖의 U1 멈춤은 출력만 보고한다. 보존 태그 `autotrader-backend:ee509cf`·`autotrader-backend:7a94472` 는 남겨 둔다.\n",
 '\n**U2. 재기동 (개입 #26) — U1 통과 뒤** (⏸ 바로 앞에서 U1 결과를 보여 운영자 확인을 한 번 받는다)\n', blk('U2'),
 '\n**U3. 기동·뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19·N20 줄 확인 — U2 5~10분 뒤** (되돌림 뒤에도 같은 묶음을 쓴다. `t-rollback.utc` 가 있으면 그 시각과 기존 이미지로 보고, BTC·뉴스·HTTP 줄은 기록만 한다. 연결 획득 시간 초과는 로그 저장 직후 P0 로 멈춘다)\n', blk('U3'),
 '\n**U4. 30분 재점검 — 기동 30분 뒤** (연결 획득 시간 초과는 로그 저장 직후 P0 로 멈춘다)\n', blk('U4'),
 '\n**U 뒤 관측(뉴스·HTTP 줄·HOLD 질의·N15/N16·N17·N19·N20 줄) — 읽기 전용, 반복 가능** (U4 통과 뒤 다음 정기 보고 무렵 등. 수집 API·강제 재시도·주기 변경 없이 기존 주기의 로그만 읽는다)\n', blk('U뒤관측'),
 '\n**U 되돌림 — backend (U2~U4 의 "되돌림 필요" 때, 또는 총괄 지시 뒤)** (DB 마이그레이션·장부 변경이 없으므로 데이터 작업은 없다. ee509cf 로 돌아가면 N20 보완(보유 시세 보강의 항목 격리·고정 문구 WARN)이 다시 없어진다. N15·N16·N17·N19 방어는 ee509cf 에도 있으므로 유지된다)\n', blk('U되돌림'),
 '\n']
sec = ''.join(parts)
t = DOC.read_bytes().decode('utf-8')
if '**98. ' in t:
    sys.exit('이미 §98 있음')
anchor = '## 6. 시작·중단·종료 조건'
if t.count(anchor) != 1:
    sys.exit('기준 줄 수 다름')
nl = '\r\n' if '\r\n' in t[:2000] else '\n'
t = t.replace(anchor, sec.replace('\n', nl) + anchor)
DOC.write_bytes(t.encode('utf-8'))
print('§98 넣음, 줄바꿈', repr(nl))
