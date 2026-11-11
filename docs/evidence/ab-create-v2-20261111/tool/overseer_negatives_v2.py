"""총괄 review.py 의 합성 변형 반복문을 그대로 v2 모듈에 돌린다(인자 하나 check_config(rows) — 기록부 없이)."""
import importlib, json, sys
sys.path.insert(0, '.')
ag = importlib.import_module('ab_aggregate')
fixture = importlib.import_module('test_ab_aggregate')
out = []
for name, mutation in [
    ('control', lambda rows: None),
    ('all_four_pairs_same_strategy_coin', lambda rows: [r.update(strategy='COMPOSITE_MEANREV_BB', coin='KRW-BTC') for r in rows]),
    ('all_capital_200000', lambda rows: [r.update(initial_capital='200000') for r in rows]),
    ('all_timeframe_H1', lambda rows: [r.update(timeframe='H1') for r in rows]),
    ('different_sha_between_pairs', lambda rows: [r.update(code_sha='different') for r in rows if r['pair_id'] == 'P4']),
]:
    rows = fixture.arms()
    mutation(rows)
    try:
        ag.check_config(rows)
        out.append({'case': name, 'result': 'ACCEPTED'})
    except ag.ConfigRejected as e:
        out.append({'case': name, 'result': 'REJECTED', 'reason': str(e)})
print(json.dumps(out, ensure_ascii=False, indent=2))
