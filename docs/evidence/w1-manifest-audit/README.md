# W1 — 근거 목록 전체 대조와 #26 누락 5개 보완 (주말 지시 §3, 2026-11-06)

[주말 작업 지시](../../WEEKEND_20261106.md) §3 W1의 결과다. 로컬에서만 했다. 서버·DB·네트워크 접속은 없었다.

## 무엇을 했나

1. `audit.py`(이 폴더)로 `docs/evidence` 아래 SHA256SUMS를 모두 찾았다. 항목마다 다음 세 가지를 확인했다.
   - 작업 사본 파일의 sha256이 목록 값과 같은가
   - 지정 커밋 트리의 블롭 sha256이 목록 값과 같은가
   - 그 경로가 `.gitignore` 규칙에 걸리는가
   - 목록에 없는 폴더 안 파일도 따로 적었다(SHA256SUMS·.gitattributes 제외).
2. 대조기는 파일을 고치거나 `git add`하지 않는다. 블롭은 `git cat-file --batch`로 읽었다.

## 커밋 전 결과 (기준 1aaea01, `audit-1aaea01.json`)

| 값 | 수 |
|---|---|
| SHA256SUMS 목록 | 92개 폴더 |
| 항목 | 3448 |
| 작업 사본 일치 | **3448** (없음 0·다름 0) |
| 커밋 블롭 일치 | 3424 |
| 커밋 블롭 없음 | **24** |
| 커밋 블롭 다름 | 0 |
| ignore 규칙에 걸리는 항목 | 146 |
| 목록에 없는 파일 | 4 |

- **커밋 블롭 없음 24개**
  - `k26-result-1899b49/out/` 5개(final-state.txt, local-copy.sha256, operator-confirm.txt, server-list-sha.txt, u1-present.txt): `.gitignore`의 `out/` 규칙 때문이다. 총괄 v1.132가 찾은 누락과 같다.
  - `k26-result-review-1aaea01` 13개, `weekend-20261106-scope` 6개: 아직 커밋하지 않은 총괄 근거다.
- **ignore에 걸리는 나머지 141개**는 모두 이미 커밋돼 있다(블롭 일치). `*.log`·`out/` 규칙에 걸리지만 과거에 명시 추가된 것이다. 새 누락은 없다.
- **목록에 없는 파일 4개**: 폴더 자체 설명이고 원본 근거가 아니다. 목록을 고치지 않았다.
  - `n06-rehearsal-cd71d6a/README.md`, `n06-rehearsal-cd71d6a/manifest.json`
  - `n08-review-0de23ca/README.md`
  - `n13-deploy-prep-fe01207/.gitignore`

## 이번 커밋에서 보완한 것

- 원래 경로의 5개를 `git add -f`로 넣었다. 원문·`preserved-out/` 사본·원래 SHA256SUMS 값이 파일마다 같다.
- 원래 `k26-result-1899b49/SHA256SUMS`와 과거 커밋 1aaea01은 고치지 않았다. 1aaea01에 102개만 들어 있었다는 이력은 그대로다.
- 커밋 뒤 같은 대조기로 새 커밋을 다시 대조한 결과는 보고에 적는다. 이 커밋 안에 자기 SHA를 적지 않는다(N06).

## 하지 않은 것

- 옛 문서 형식 정리, 과거 미보존 XML 재생성, 목록 수정.
- 서버 보존본 재수집(이번 대조에서 서버에만 있어야 할 누락은 없었다).
