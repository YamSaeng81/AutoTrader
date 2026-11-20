# 12월 준비 31 — 수집 계약 보충 메모 (총괄 v1.208 §2·§3-1) — 실행 승인이 아님

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [수집 계약 초안](../dec-collect-contract-20261120/README.md)의 바이트·SQL 9818db54·파서 0f6fe827·기존 결과·체크섬은 바꾸지 않았다. 아래 3항은 [총괄 v1.208](../review-73c23938/README.md) §2를 그대로 반영한다. 근거는 총괄의 [직접 형식 대조](../review-73c23938/contract-probes.json)다. PG·기존 13건은 다시 돌리지 않았다.

1. **C0~C6은 실행 관리 순서이며 P7 `meta.steps`가 아니다.** P7 v8의 단계는 `db_fp_pre → exchange_before → db_extract → exchange_after → db_fp_post` 정확히 5개다. C2의 실제 psql 호출 구간만 `db_extract`에 대응한다. 나머지 4개는 별도 수집 근거가 필요하며, 미수집 단계를 같은 시각·같은 지문으로 채우지 않는다. 계약 §1 "단계 번호는 `meta.steps`에 그대로 대응" 문장은 이 항으로 대체한다.
2. **시계 표본은 P7 스키마 `{pair:['db','collector'], step:'db_extract', c0, d, c1, mono_rtt_ms}`로 쓴다**(clock `version`=`p7-clock-1`). 계약 §2 예시에 빠진 `step`은 필수다. 표본 하나는 DB의 d(META `clock_timestamp()`) 시각을 수집기 구간으로 옮기는 근거일 뿐이며, 그보다 앞선 META `now()`나 과거 생성·월 경계 시각으로 외삽하지 않는다.
3. **구간은 단조 시계 일치가 전제다.** 스냅숏은 psql 호출의 실제 시작·끝 사이에 있지만, 벽시계 `[c0,c1]`로 표현하려면 단조 경과와 벽시계 경과가 P7 `MONO_TOL`(500µs) 안에서 맞아야 한다. 이동·역행이면 그 회차의 축 관계를 미확정으로 둔다. DB `[now(), END]`도 벽시계 무이동 근거 없이 보장 경계로 쓰지 않는다. 어느 구간도 정확한 월말 T 포착이 아니다.

이 보충을 적용한 파일 조립기는 [dec-collect-assembler-20261120](../dec-collect-assembler-20261120/README.md)이다.
