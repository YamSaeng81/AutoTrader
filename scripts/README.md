# scripts/ 색인

> 2026-09-15 작성. `scripts/` 에는 **상시 재실행해도 되는 것**과 **특정 시점 1회성**이
> 섞여 있는데 파일명만으로는 구분되지 않는다. 날짜가 파일명에 없어도 헤더에 박힌 경우가
> 많다. 이 색인은 그 구분을 명시한다.

파일을 옮기지 않은 이유: `docs/NEXT.md` 와 `docs/old_progress.md` 가 상대 경로로
다수를 참조하고 있어, 이동하면 살아있는 참조가 깨진다.

## 상시용 — 언제 실행해도 안전

| 스크립트 | 용도 |
|---|---|
| [`db-restore-drill.sh`](db-restore-drill.sh) | DB 복원 드릴 스크립트 — 20260415_analy.md Tier 4 §17 |
| [`security-check.sh`](security-check.sh) | 보안 점검 스크립트 — 20260415_analy.md Tier 4 §18 |
| [`status.sh`](status.sh) | 지금 무엇을 봐야 하는가 — 상태 점검 (읽기 전용) |

## 1회성 — 그대로 재실행하지 말 것

특정 시점의 운영 상태·세션 번호·코인 목록을 전제로 쓰였다. 지금 실행하면 엉뚱한
세션을 건드리거나 아무 일도 하지 않는다. **읽고 참고하되, 필요하면 새로 쓸 것.**

| 날짜 | 스크립트 | 무엇을 했나 |
|---|---|---|
| 2026-09-18 | [`wf_watchlist_0918.sh`](wf_watchlist_0918.sh) | 감시 범위 WF 확장 19코인 57조합 — "전략 문제 vs 배치 문제" 판별 |
| 2026-09-18 | [`rebaseline_wf_0918.sh`](rebaseline_wf_0918.sh) | 규칙 v3 재검증 기준선 — 운영 5조합 WF 재실행 (`--verify` 로 v3 저장 확인) |
| 2026-09-14 | [`replace_eul_prom_0914.sh`](replace_eul_prom_0914.sh) | 고정 격자에서 EUL·PROM 을 ETH·XRP 로 교체 |
| 2026-09-08 | [`revalidate_walk_forward_0908.sh`](revalidate_walk_forward_0908.sh) | Walk Forward 전면 재검증 — 손절 로직 수정 반영 |
| 2026-09-08 | [`stop_duplicate_paper_sessions.sh`](stop_duplicate_paper_sessions.sh) | 고정코인 PAPER 세션 중복분 정리 |
| 2026-09-08 | [`stop_killed_dynamic_sessions_0908.sh`](stop_killed_dynamic_sessions_0908.sh) | 폐기 판정된 동적 세션 정지 — 세션 83·91 |
| 2026-09-08 | [`stop_null_sl_paper_sessions_0908.sh`](stop_null_sl_paper_sessions_0908.sh) | stop_loss_pct 가 NULL 인 잔존 고정코인 PAPER 세션 정지 |
| 2026-09-07 | [`fix_data_integrity_0907.sh`](fix_data_integrity_0907.sh) | 운영 DB 데이터 정합성 보정 — 2026-09-07 |
| 2026-09-02 | [`collect_watchlist_candles_0902.sh`](collect_watchlist_candles_0902.sh) | 감시목록 캔들 수집 — WF 커버리지 확장의 선행 작업 |
| 2026-09-02 | [`expand_dynamic_fleet_0902.sh`](expand_dynamic_fleet_0902.sh) | DYNAMIC 페이퍼 함대 증설 — 표본 축적 속도 보정 |
| 2026-09-02 | [`walk_forward_watchlist_0902.sh`](walk_forward_watchlist_0902.sh) | WF 커버리지 확장 — 09-02 워치리스트 기준 |
| 2026-08-31 | [`restart_dynamic_fleet_clean_baseline.sh`](restart_dynamic_fleet_clean_baseline.sh) | DYNAMIC 페이퍼 함대 재기동 — 깨끗한 기준선 + 규칙 동결 |
| 2026-08-31 | [`stop_pullback_mtf_fleet.sh`](stop_pullback_mtf_fleet.sh) | COMPOSITE_PULLBACK_MTF 전면 정지 |
| 2026-08-28 | [`collect_watchlist_candles.sh`](collect_watchlist_candles.sh) | 감시목록 코인 캔들 수집 — WF 커버리지 확장의 선행 작업 |
| 2026-08-28 | [`restart_stopped_dynamic_sessions.sh`](restart_stopped_dynamic_sessions.sh) | 정지된 동적 세션 재기동 — 세션 60 / 62 |
| 2026-08-28 | [`walk_forward_new_watchlist_coins.sh`](walk_forward_new_watchlist_coins.sh) | WF 커버리지 확장 — 신규 감시목록 코인 |
| 2026-08-26 | [`create_ab_signal_exit_sessions.sh`](create_ab_signal_exit_sessions.sh) | 전략 SELL 청산 A/B — COMPOSITE_PULLBACK_MTF |
| 2026-08-24 | [`backfill_candle_data.sh`](backfill_candle_data.sh) | candle_data 백필 |
| 2026-08-24 | [`backfill_watchlist_coins.sh`](backfill_watchlist_coins.sh) | 실제 DYNAMIC 감시목록 커버리지 백필 |
| 2026-08-24 | [`cleanup_failed_ab_sessions.sh`](cleanup_failed_ab_sessions.sh) | 손절폭 A/B 1차 시도 정리 — 파라미터가 유실된 세션 40개 제거 |
| 2026-08-24 | [`create_ab_stoploss_sessions.sh`](create_ab_stoploss_sessions.sh) | 손절폭 A/B 실험군 생성 |
| 2026-08-24 | [`disable_duplicate_strategies.sh`](disable_duplicate_strategies.sh) | 중복 전략 비활성화 — COMPOSITE_MTF_BTC_STRICT |
| 2026-08-24 | [`rerun_walk_forward_active_strategies.sh`](rerun_walk_forward_active_strategies.sh) | 활성 전략 6종 Walk Forward 재검증 |
| 2026-08-24 | [`rerun_walk_forward_watchlist.sh`](rerun_walk_forward_watchlist.sh) | 실제 DYNAMIC 감시목록 커버리지 WF 재검증 |
| 2026-08-24 | [`restart_ab_stoploss_treatment.sh`](restart_ab_stoploss_treatment.sh) | 손절폭 A/B 실험군 재기동 |
| 2026-08-24 | [`stop_h1_zero_winrate.sh`](stop_h1_zero_winrate.sh) | H1 승률 0% 전략 세션 중지 |
| 2026-08-24 | [`stop_pullback_paper_fleet.sh`](stop_pullback_paper_fleet.sh) | COMPOSITE_PULLBACK_MTF 고정코인 PAPER 세션 중지 |
| 2026-08-19 | [`create_ab_dampen_sessions.sh`](create_ab_dampen_sessions.sh) | 신호 감쇠 A/B 실험군 세션 생성 |
| 2026-08-18 | [`build_paper_grid.sh`](build_paper_grid.sh) | 페이퍼 데이터 생성 격자 — 7전략 × 8코인 × 2타임프레임 = 112세션 |
| 2026-08-18 | [`rebuild_paper_fleet.sh`](rebuild_paper_fleet.sh) | 실자본 매매 전면 중단 |

## 주의

- 다수가 **운영 DB 와 운영 API 를 직접 건드린다.** 실행 전 대상 환경을 반드시 확인할 것.
- `docs/NEXT.md` 가 현재 작업으로 참조 중인 1회성 스크립트가 있다
  (`replace_eul_prom_0914.sh`, `revalidate_walk_forward_0908.sh`). 그 문서의 맥락 안에서만 실행한다.
- 새 1회성 스크립트를 추가할 때는 파일명에 날짜를 넣고 이 표에도 한 줄 추가할 것.

