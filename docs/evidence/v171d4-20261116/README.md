# V171D4 준비 — 이미지 대응 원문·확인 도구·새 블록·대역·실행 틀 (422차 제출, 총괄 판정 전)

총괄 v1.171(421차) §3~§4. 후보 **0e1d2a02**, GS 6385e1d5, DIFFN 133, jar 이름 차이 62줄·881→895, **JARM b6587953…**(Git LF), 판 표지 UB=V171D4, 기록 폴더 `~/maint-v171`. 원본은 V168D4(d5790c1c)다. **운영 변경은 없다.** 서버에서 한 일은 읽기와 디스크에 쓰지 않는 `docker image save` 스트림 읽기뿐이다. 태그·컨테이너·저장소·daemon은 바꾸지 않았다. U0 이후 단계는 실행하지 않았다.

## 1. 상태·이미지 대응 읽기 (11-16 10:52~11:00 KST, 읽기만) — §3-1

원문: [state-1052/](state-1052/) (명령 원문과 출력을 같이 둠)

| 항목 | 값 |
|---|---|
| HEAD·추적 변경 | b5cd1d0d…, 추적 변경 0 |
| Docker·Compose·저장소 | 29.9.2(API 1.56)·Compose 5.6.0·`io.containerd.snapshotter.v1`(overlayfs), daemon.json 없음 |
| backend 컨테이너 | 743dfebac8c0…, 생성 11-10 04:48:42Z, 재시작 0. `.Image` = **52e12428**(이미지 대상 ID, index) · ImageManifestDescriptor = **ca440b84**(linux/amd64) |
| backend latest·b5cd1d0 태그 | 둘 다 대상 52e12428 → manifest ca440b84 → **config 0344757e**. `b5cd1d0` 태그는 V168 U1(10:25)이 만든 것 |
| frontend 컨테이너 | 9a63d15ab110…, 생성 09-22 07:38Z, 시작 10-21 04:45Z, 재시작 0. `.Image` = **d825a9f5** · ImageManifestDescriptor = **fa5c9ba8**(linux/amd64) |
| frontend 이미지 이름 | `autotrader-frontend`(latest) = 대상 **837db515** → manifest fa5c9ba8 → **config 80ce7550**. daemon이 이 manifest의 사용 컨테이너로 9a63d15…를 표시 |
| 거절 빌드 e25c2a85 | 태그 없음, manifest 626092ae → config d41dce30, 사용 컨테이너 없음 |
| ~/maint-v171 | 없음 |

**420차 해석을 바로잡는다.** frontend의 `.Image` d825a9f5는 config 다이제스트가 **아니다**. save 원문으로 확인한 config는 80ce7550이다([save-read-fe.txt](state-1052/save-read-fe.txt)). d825a9f5는 대상 ID·manifest·config 어느 것과도 같지 않고, 이미지로 조회하면 `No such image`다. 서버 유지보수 기록에서는 10-29부터 frontend 실행 값으로만 나온다([hist-read-1.txt](state-1052/hist-read-1.txt)). 무엇의 값인지는 **입증하지 못했다**(생성 당시 이미지 대상 ID였을 가능성은 가설).

이에 따라 §3-2가 지시한 "manifest의 config ↔ 실행 컨테이너 Image" 연결은 실제 서버에서 성립하지 않는다. backend는 `.Image` = 대상 ID, frontend는 `.Image` = 알 수 없는 값이다. **V171D4는 다음 네 가지로 대응을 확인한다(총괄 판단 요청 1).**
1. 컨테이너 ImageManifestDescriptor = 참조 대상의 linux/amd64 manifest
2. 이미지 API(`/images/<대상>/json?manifests=1`)가 그 manifest의 사용 컨테이너로 실행 컨테이너 ID를 표시
3. `docker image save --platform linux/amd64` 산출물의 manifest→config 연결과 blob 원문 해시
4. `.Image`가 허용값인지 — 옛 컨테이너는 U0 기록값(IMGOLD·FEIMGOLD), 새로 만든 컨테이너는 대상 ID 또는 config

10:00 보고와 장부는 10:12에 읽은 뒤 새 보고가 없다(다음 회차 22:00). 실자금·주문은 이번에 읽지 않았다.

## 2. 이미지 대응 확인 도구 — §3-2

[tool/imgchain.py](tool/imgchain.py)(fddd5d1c…). 블록은 U변수에 같은 원문을 담아 `~/maint-v171/imgchain.py`로 쓰고, 쓸 때마다 해시(ICSUM)를 대조한다.
- `api`: 대상 ID·Descriptor 일치, image 종류 manifest 정확히 1개, OCI manifest, linux/amd64, Available, 사용 컨테이너 형식.
- `save`: save 스트림을 디스크에 쓰지 않고 읽는다. index가 그 manifest 하나인지, blob 원문 해시가 이름과 같은지, manifest·config 크기, config os/arch를 확인한다.
- `ctr`: 컨테이너 ID·`.Image`·ImageManifestDescriptor(linux/amd64 OCI manifest).

**서버 읽기 시험**([srvtest-chain-1.txt](state-1052/srvtest-chain-1.txt), 11:00): backend latest·b5cd1d0과 frontend 이름 모두 대상→manifest→config→실행 컨테이너 연결이 확인됐다. d825a9f5와 없는 frontend:b5cd1d0은 `No such image`로 멈췄고, 다중 플랫폼 이미지(cloudflared)는 "image manifest 2개"로 거절했다. save 시간은 frontend 3초, backend 6초다.

## 3. V171D4 블록 — §3-3~§3-5, §4

생성기 [d4/mkdraft10.py](d4/mkdraft10.py)는 V168D4 원문([src.sha256](d4/src.sha256), d5790c1c 블롭 10개 해시 일치)에서 지정한 부분만 바꾼다. 바꾸는 곳마다 출현 횟수를 확인하고, 옛 판 표기(maint-v168·V168D4·7df5758f·v1.168 표지)나 실행 컨테이너 `.Image` 직접 비교가 남으면 멈춘다. 블록 10개는 [d4/blocks-d4/](d4/blocks-d4/), 해시는 [blocks-d4.sha256](d4/blocks-d4.sha256), 차분은 [blocks-d4.v168-to-v171.diff](d4/blocks-d4.v168-to-v171.diff)(554줄)다. 10블록 `bash -n` 통과.

| 블록 | 바뀐 것 |
|---|---|
| U변수 | M·UB·JARM b6587953…(LF 기대 목록 895행과 같은 값)·IMGOLDM/IMGOLDC·FETOLD/FEMOLD/FECOLD·도구 원문과 함수 `rchain`·`cchain`·`isimg`·`same3`·`ok3`·`okimg` |
| U0 | 판·JARM 값·도구 확인. backend·frontend 대응(대상·manifest·config·사용 연결·`.Image`)을 고정값과 대조해 `image-before-chain.txt`·`image-fe-before-chain.txt`로 기록. 보존 태그가 있으면 같은 이미지인지 확인 |
| U1 | **바꾸기 전에** 두 서비스·두 이름·두 보존 태그 대응을 확인한 뒤 backend·frontend 보존 태그를 **대상 ID**로 만든다(`fe-stage` = 보존태그). 새 backend 대응 기록, 빌드 뒤 보존 태그·실행 컨테이너 재확인. frontend는 `빌드시작` → 빌드 → 새 대응 기록 → `빌드끝` |
| U1되돌림 | 전면 교체. 바꾸기 전 두 실행 컨테이너 대응·단계 기록·이름 상태를 판정하고, 바뀐 이름만 보존 태그의 확인된 대상 ID로 되돌린다. 빌드 전 단계에서 이름이 바뀌었거나 `빌드끝`인데 이름이 새 이미지가 아니면 제3의 이미지로 보고 멈춘다. 이름 원복과 두 실행 컨테이너 대조가 끝난 뒤에만 완료를 기록한다 |
| U2 | 재기동 전 latest = 새 대응·실행 backend = #27 대응. 재기동 뒤 새 컨테이너 대응(`.Image` ∈ 대상·config) |
| U3 | 기대 대응 판정을 **P0 검사 뒤**에 멈추도록 옮겼다(조회 실패가 P0를 가리지 않게) |
| UF2 | `빌드끝` 확인, 교체 전 frontend = U0 대응·이름 = 새 대응·보존 태그 = U0 대응·backend = 새 대응. `교체시작`→교체 뒤 새 대응 확인→`교체끝` |
| U되돌림 | 바꾸기 전 backend/frontend 실행 대응을 구판/새판으로 판정. 어느 것도 아니거나 조회에 실패하면 멈춘다. 보존 태그 두 개 확인. frontend 교체 뒤면 이름을 보존 대상으로 되돌리고 재생성해 대조한다. 교체 전이면 이름만 되돌린다(재생성 없음). backend는 latest를 #27 대상으로 되돌리고 재생성 뒤 대조한다. 상태 기반 복구 관문은 그대로 |
| U뒤관측 | 새 대응 확인 |

U4·F·G는 판 표지만 바뀌거나 그대로다. JARM 계산기(항목별 바이트 해시·중복 거절)와 RARX·JARN·관문/조회문 해시·블록 순서는 바꾸지 않았다.

## 4. 대역 — §4

장치: V168 대역 사본 `/d/tmp/v171d4s`. 바뀐 파일은 [standin/harness/](standin/harness/)와 [harness-v168-to-v171.diff](standin/harness-v168-to-v171.diff)에 있다.
- containerd 흉내: 이미지 = 대상 ID·linux/amd64 manifest·config, 컨테이너 = ID·`.Image`·ImageManifestDescriptor. 이미지 API(curl)와 save 스트림도 흉내 낸다([imgstate.py](standin/harness/imgstate.py)).
- #27 backend와 frontend는 **실제 서버 save 원문 blob**([fixreal/](standin/harness/fixreal/))을 쓴다. 실행 컨테이너 ID·`.Image`·manifest는 운영 읽기 값이다.
- 정상 jar는 서버가 0e1d2a02로 빌드한 실제 jar(5c53c9e3…)다. 옛 jar도 서버 #27 컨테이너의 실제 jar(4a685368…)를 쓴다. 둘 다 `~/maint-v168`에서 읽기로 받았다. 한 항목 변조 jar 2개(클래스·V96 SQL)와 로컬 CRLF bootJar(be723125…)도 쓴다([jars.sha256](jars/jars.sha256)). jar 원본은 크기 때문에 넣지 않았다.

사례: [scenarios-v171.sh](standin/scenarios-v171.sh)

| 묶음 | 내용 |
|---|---|
| x01~x03 | 정상 전 구간(JARM b6587953·대응 기록·새 컨테이너 대응), UF2 뒤 U되돌림(frontend 재생성 복구)·U3(rollback), U1 통과 뒤 U1되돌림 |
| x04·x04s·x05·x05c | 클래스/SQL 한 항목 변조 → RARX·JARN 통과 후 JARM 거절(재기동 전). b5cd1d0 jar·로컬 CRLF jar → RARX 거절 |
| x06~x12 | JARM 없음·한 글자 변조, 옛 U변수/옛 블록 혼합, maint-v168, UB=V168D4, FEIMGOLD 불일치 |
| i01~i08 | 잘못된 플랫폼·config os·blob 변조·image manifest 2개·ImageManifestDescriptor 없음·보존 태그 오염·API 실패·save 실패 → 멈춤, 변경 명령 0 |
| f01~f04 | frontend 보존 태그 오염(빌드 전, 변경 0)·빌드 실패(이름 그대로)·이름이 바뀐 뒤 빌드 실패 → U1되돌림 복구·빌드끝 뒤 제3의 이미지 → 멈춤 |
| t01~t03 | latest 변경 → U2 재기동 전 멈춤, frontend 이름 변경 → UF2 교체 전 멈춤, API 실패 → U2 멈춤 |
| c01~c03 | 새 컨테이너 `.Image`가 config인 경우 수용, 새 컨테이너 플랫폼 이상 → 되돌림 필요, daemon 사용 목록에 없음 → U3 멈춤 |
| r01~r04 | UF2 응답500 → U되돌림 frontend 복구, frontend 교체 전 U되돌림(이름만), 제3의 backend → 변경 0, 조회 실패 → 변경 0 |
| p01·p02 | P0 + 이미지 조회 실패 → P0로 멈춤, 조회 실패만 → 대응 멈춤 |
| o01 | **옛 V168D4 원문을 이 장치에서 돌리면 10:25~10:27 실패가 재현된다.** U1 JARM b6587953 ≠ 7df5758f, U1되돌림 `No such image: sha256:d825a9f5` |

결과:
- **r1(11:15~12:14, 순차)**: 성공 193 · 실패 3 · 금지 명령 0. 실패 3건은 모두 시나리오 쪽 실수였다. x02 U3 재실행이 같은 출력 이름으로 실행 틀에서 거절됐고, x05c 기대값은 V96이 추가 항목이라 96이며, c02 주입이 옛 컨테이너까지 적용돼 재기동 전에 멈췄다(블록 동작은 올바름). 원문: [scenarios-v171-r1.txt](standin/scenarios-v171-r1.txt), 당시 스크립트 [scenarios-v171.r1-script.sh](standin/scenarios-v171.r1-script.sh). 수정 뒤 c03을 추가했다.
- **r2(최종, 12:11~12:34, 4묶음 병렬)**: **성공 203 · 실패 0**(a 47·b 52·c 55·d 49, 묶음마다 금지 명령 합 0). 묶음은 [split-scen.py](standin/split-scen.py)가 같은 스크립트를 절 단위로 나눈 [r2-parts/](standin/r2-parts/)다(사례·단언 그대로). 원문은 [scenarios-v171-r2.txt](standin/scenarios-v171-r2.txt)다.
- 실행 원문: r1·r2 사례 폴더 77개, 11,814파일 해시 [runs-r1r2.sha256](standin/runs-r1r2.sha256). 압축본 [standin-runs-r1r2.tar.gz](standin/standin-runs-r1r2.tar.gz)(4.5MB)에서는 jar 사본과 고정 자료 사본(start_66 원문·procs·시험 파일·관문·SQL, 내용 종류 1~5개)을 뺐다. jar 사본 98개는 알려진 jar 5개와 바이트가 같다.

## 5. 실행 틀

[runner/run.sh](runner/run.sh)·[runner/extract.py](runner/extract.py)는 V168 실행 틀에서 판·경로·대조만 바꿨다. 출처 커밋 SHA는 이 파일이 그 커밋에 함께 들어가므로 미리 적을 수 없다. 그래서 **블록 12개 sha256을 두 파일에 고정**했다. extract.py는 실행 때 받은 출처 커밋의 블롭과 `blocks-d4.sha256`을 고정값과 대조한다. 출력 덮어쓰기 거절·ssh 종료값 즉시 기록은 그대로다.

가짜 ssh 시험 [runner-selftest.txt](runner/runner-selftest.txt): **12/12**. 정상 0·원격 1·ssh 255를 그대로 전달하고, 같은 출력 이름·모드/블록 이름 오류·블록 1바이트 변조·출처 형식·옛 U변수 혼합을 ssh 호출 없이 거절한다. 원격 원문은 F+G+U변수+블록과 바이트가 같고 마지막 문자가 `)`다.

## 6. 남은 한계

- 새로 만든 컨테이너의 `.Image` = 대상 ID는 11-10 backend 한 건의 관측이다. 그래서 블록은 대상 ID 또는 config를 허용한다. 실제 재생성 결과는 U2·UF2 원문으로 확인한다.
- d825a9f5의 정체는 미입증이다. 대응 판정은 manifest·사용 연결·config로 하며, 이 값은 U0 기록값 대조에만 쓴다.
- save 스트림 읽기는 확인 한 번에 backend 약 430MB·6초를 읽는다. U1·U되돌림에서 여러 번 쓴다(재기동 전 확인 위주).
- 서버에는 `~/maint-v168`, `~/maint-v161-pre-v168-20261116`, 태그 없는 e25c2a85, V168 U1이 만든 backend:b5cd1d0 태그가 그대로 있다(이번 보완 범위에서 삭제하지 않음). U0는 이 보존 태그가 #27과 같은지 확인한다.

## 7. 판정 요청

1. 실행 컨테이너 대응 기준(§1의 1~4). §3-2 문구의 "config ↔ 컨테이너 Image" 대신 manifest·사용 연결·config·허용 `.Image`로 확인한다.
2. V171D4 블록·대역·실행 틀을 새 고정 출처로 수용할지. 수용되면 가장 이른 허용 창에 직전 보고·장부·실제 상태 확인 → 새 U0 → U1(30분 안) → U2 직전 운영자 확인 1회 순서로 진행한다.
