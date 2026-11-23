import json, sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8')).get('data') or {}
except Exception:
    d = None
if d is None:
    print('ACCT parse=fail')
else:
    btc = [h for h in d.get('holdings', []) if h.get('currency') == 'BTC']
    print('ACCT apiKeyConfigured=%s error_key=%s availableKrw=%s lockedKrw=%s totalKrwBalance=%s fetchedAt=%s btc_balance=%s btc_locked=%s btc_currentPrice=%s other_holdings=%d' % (
        d.get('apiKeyConfigured'), 'error' in d, d.get('availableKrw', '-'), d.get('lockedKrw', '-'), d.get('totalKrwBalance', '-'),
        d.get('fetchedAt', '-'), btc[0].get('balance') if btc else 0, btc[0].get('locked') if btc else 0,
        btc[0].get('currentPrice') if btc else '-', len([h for h in d.get('holdings', []) if h.get('currency') != 'BTC'])))
for line in open(sys.argv[2], encoding='utf-8'):
    if line.split('\t', 1)[0] in ('META', 'SESS', 'OBUY', 'CNT', 'POS', 'MAXTS', 'MORD', 'MSES', 'END'):
        print(line.rstrip('\n'))
