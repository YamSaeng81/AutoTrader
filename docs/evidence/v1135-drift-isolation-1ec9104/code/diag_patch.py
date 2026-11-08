# 진단 전용(커밋하지 않음): ExecutionDriftTrackerTest 에 행 덤프 출력만 더한다. 단언은 그대로.
p = r'D:\tmp\o1o3-1899b49\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java'
s = open(p, encoding='utf-8').read()
s = s.replace('import org.junit.jupiter.api.AfterEach;\n',
              'import org.junit.jupiter.api.AfterEach;\nimport org.junit.jupiter.api.BeforeEach;\nimport org.junit.jupiter.api.TestInfo;\n', 1)
anchor = '    @AfterEach\n    void cleanup() {'
assert anchor in s
diag = '''    // [DRIFT-DIAG] 진단 전용 — 커밋하지 않음
    @BeforeEach
    void diagBefore(TestInfo info) {
        diagDump("before " + info.getTestMethod().map(m -> m.getName()).orElse("?"));
    }

    private void diagDump(String label) {
        List<ExecutionDriftLogEntity> rows = driftRepo.findAll();
        System.out.println("[DRIFT-DIAG] " + label + " rows=" + rows.size());
        for (ExecutionDriftLogEntity e : rows) {
            System.out.println("[DRIFT-DIAG]   id=" + e.getId() + " kind=" + e.getSessionKind() + " sid=" + e.getSessionId()
                    + " coin=" + e.getCoinPair() + " strat=" + e.getStrategyType() + " side=" + e.getSide()
                    + " signal=" + e.getSignalPrice() + " fill=" + e.getFillPrice() + " slip=" + e.getSlippagePct()
                    + " at=" + e.getExecutedAt());
        }
    }

'''
s = s.replace(anchor, diag + anchor, 1)
old = '''        BigDecimal slippage = driftRepo.findAll().get(0).getSlippagePct();
'''
assert old in s
new = '''        diagDump("after record (negative)");
        System.out.println("[DRIFT-DIAG] get(0).id=" + driftRepo.findAll().get(0).getId());
        BigDecimal slippage = driftRepo.findAll().get(0).getSlippagePct();
'''
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
