"""F3(RUNBOOK 185~) 계좌 대조식만 옮긴 계산 예시. 운영·DB 조회 없음. 수치는 가정값.
F3 기대: 거래소 KRW 증감 = SID203 세션 증감(±1원) · 거래소 BTC 증감 = SID203 순체결량 = 열린 포지션 수량."""
from decimal import Decimal as D
def f3(name, k1, k3, b1, b3, sid_avail, sid_net, sid_posq):
    dk = k3 - k1; ds = sid_avail - D(20200); db = b3 - b1
    ok_k = abs(dk - ds) <= 1; ok_b = (db == sid_net == sid_posq)
    print(f"{name}: 거래소KRW증감 {dk} · 세션증감 {ds} · 차이 {dk-ds} → {'일치' if ok_k else '🔴 불일치'} | "
          f"거래소BTC증감 {db} · 순체결량 {sid_net} · 포지션 {sid_posq} → {'일치' if ok_b else '🔴 불일치'}")
# SID203: 20,000원 BTC 매수(수수료 10원 가정), 체결 0.0002 BTC
s_av, s_q = D(20200) - D(20010), D("0.0002")
f3("단독     ", D(100000), D(100000)-D(20010), D(0), s_q, s_av, s_q, s_q)
# 병행A: DYNAMIC 이 ETH 를 16,000원(+수수료 8원) 매수 — BTC 는 안 건드림
f3("병행 ETH ", D(100000), D(100000)-D(20010)-D(16008), D(0), s_q, s_av, s_q, s_q)
# 병행C: DYNAMIC 이 BTC 를 16,000원(+8원) 매수, 0.00016 BTC
f3("병행 BTC ", D(100000), D(100000)-D(20010)-D(16008), D(0), s_q+D("0.00016"), s_av, s_q, s_q)
# 병행D: 같은 날 DYNAMIC 이 ETH 를 사고 팔아 손익 −50원(수수료 포함)
f3("병행 왕복", D(100000), D(100000)-D(20010)-D(50), D(0), s_q, s_av, s_q, s_q)
