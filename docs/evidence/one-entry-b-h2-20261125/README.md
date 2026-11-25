# B 후보 c43e1f1a — H2 예약 시험 2클래스 1회 (v1.242 §3-2)

2026-11-25 KST. 작업 사본 `/d/tmp/one-entry-d81c3f2f`, HEAD **c43e1f1af1daab9c23e737101ed297826a70e4a5**, 작업 트리 변경 없음(실행기가 확인). H2만 사용. PG·jar·전체 회귀는 돌리지 않았다.

| 클래스 | 결과 |
|---|---|
| CapitalPoolLimitTest (공유 풀 한도·계좌 여력) | 8/8 통과 |
| ConcurrentEntryCapTest (시험 11 동시 진입·예약 상한) | 3/3 통과 |

**합계 11/11, gradle rc=0.** 원문: [summary](runs/cand-r1/summary.txt) · [gradle.log](runs/cand-r1/gradle.log) · [xml](runs/cand-r1/xml/) · 실행기 [run.sh](run.sh). 체크섬 [SHA256SUMS](SHA256SUMS).

범위: 옵션 없는 LIVE 예약에 추가된 `findById` 1회가 기존 예약 시험 결과를 바꾸지 않았다는 근거일 뿐이다. B를 배포 후보에 포함한다는 판정이 아니며, D81 11-27 실행 승인과는 별개다.
