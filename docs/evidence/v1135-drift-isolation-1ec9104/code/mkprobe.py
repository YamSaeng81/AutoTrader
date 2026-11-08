# 수정 전 고정 오염 탐침(총괄 v1.135 §3-1): 0d142aa 원래 시험 클래스에 탐침 시험 1개만 더한다. 원래 시험·단언은 그대로.
# 탐침: 다른 세션의 slippage 0 행을 먼저 넣고, 원래와 같은 음수 입력을 기록한 뒤, 원래와 같은 findAll().get(0) 으로 읽는다.
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
anchor = '''    @Test
    @DisplayName("§14 record() — signalPrice 0이면 저장 생략")'''
assert s.count(anchor) == 1
probe = '''    @Test
    @DisplayName("탐침(수정 전 조회) — 다른 세션의 slippage 0 행이 먼저 있으면 findAll().get(0) 은 무엇을 읽나")
    void probe_negativeSlippage_withEarlierZeroRow_oldSelection() {
        driftRepo.save(ExecutionDriftLogEntity.builder()
                .sessionId(900L).sessionKind("LIVE").coinPair("KRW-BTC").strategyType("COMPOSITE_BREAKOUT")
                .side("BUY")
                .signalPrice(new BigDecimal("50000000"))
                .fillPrice(new BigDecimal("50000000"))
                .slippagePct(new BigDecimal("0.000000"))
                .executedAt(Instant.now())
                .build());

        tracker.record(2L, "LIVE", "KRW-ETH", "COMPOSITE_MOMENTUM", "SELL",
                new BigDecimal("50000000"), new BigDecimal("49900000"), Instant.now());

        for (ExecutionDriftLogEntity e : driftRepo.findAll()) {
            System.out.println("[DRIFT-PROBE] id=" + e.getId() + " sid=" + e.getSessionId() + " coin=" + e.getCoinPair()
                    + " slip=" + e.getSlippagePct());
        }
        BigDecimal slippage = driftRepo.findAll().get(0).getSlippagePct();
        assertThat(slippage).isLessThan(BigDecimal.ZERO);
    }

'''
open(dst, 'w', encoding='utf-8', newline='\n').write(s.replace(anchor, probe + anchor))
print('ok')
