# 원문 보존 보완 (총괄 v1.166 §3-1)

기존 [SHA256SUMS](SHA256SUMS)·[README](README.md)·6f27b35d 커밋·다른 근거 폴더는 고치지 않았다. 원본 236개는 작업 사본에 그대로 있었고, 다시 생성한 파일은 없다.

| 결함(6f27b35d 커밋 블롭) | 보완 |
|---|---|
| 누락 23개: `final-batch.log` + `runs/*/gradle.log` 22개(`.gitignore`의 `*.log`) | 원래 경로에서 `git add -f` |
| 줄바꿈만 다름 106개(CRLF → LF 저장) | 이 폴더에만 `.gitattributes`의 `* -text`를 두고 `git add --renormalize`로 작업 사본 바이트를 다시 저장 |

대조: 보완 커밋의 블롭 236개를 **기존 SHA256SUMS**로 바이트 해시 대조해 236/236 일치, 누락 0·불일치 0(결과는 이 커밋 메시지와 [PROGRESS](../../PROGRESS.md)에 기록). 도구는 `git show <rev>:<경로>`의 바이트를 SHA-256으로 비교한다.

## 별도 보완 목록 (SHA256SUMS 밖의 새 파일)

| 파일 | 역할 |
|---|---|
| `.gitattributes` | 이 폴더 한정 줄바꿈 변환 금지 |
| `PRESERVATION-v1166.md` | 이 문서 |
| [`../n05-links-88f06345/CORRECTION-20261113-v1166.md`](../n05-links-88f06345/CORRECTION-20261113-v1166.md) | 앞 정정 메모의 `INTENT_READY`(낡은 엔티티)와 `dispatching=false`(정상 해제) 구분 |

해시는 [SUPPLEMENT-SHA256SUMS](SUPPLEMENT-SHA256SUMS).
