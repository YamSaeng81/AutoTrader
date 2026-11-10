import io
p = 'web-api/src/test/java/com/cryptoautotrader/api/service/DynamicStopRaceTest.java'
s = io.open(p, encoding='utf-8').read()
old_wait = s[s.index('    private void awaitDrained() {'):s.index('    private static OrderResponse exchange(')]
new_wait = '''    /**
     * 제출 작업을 운영과 같은 {@code orderExecutor} 에 넘기고 <b>그 작업의 실제 종료</b>를 기다린다 (2026-11-11, v1.145 §3-1).
     *
     * <p>종전 {@code awaitDrained()} 는 "큐 비었음·active 0·taskCount == completedTaskCount" 를 기다렸다. 놀고 있는
     * 작업자가 큐에서 작업을 꺼낸 뒤 실행 표시를 잡기 전에는 세 조건이 모두 참이라, 전체 시험 1회차에서 ①이
     * {@code INTENT_READY} 를 읽은 뒤에야 {@code order-exec-2} 가 철회를 기록했다
     * ({@link OrderExecutorDrainPredicateGapTest} 가 그 창을 고정 재현한다). 단독 실행은 새 작업자 경로라 창이 없었다.</p>
     *
     * <p>{@code @Async} 프록시는 같은 실행기에 다시 넘기기만 하므로, 프록시를 벗긴 대상의 {@code submitPreparedOrder} 를
     * 직접 작업으로 제출해 Future 를 받는다. 시간 초과·예외는 실패다. sleep 도, 조건 완화도 없다.</p>
     */
    private void dispatchAndAwait(Intent intent) {
        OrderExecutionEngine target = AopTestUtils.getUltimateTargetObject(engine);
        Future<?> done = orderExecutor.submit(() -> target.submitPreparedOrder(intent.orderId(), intent.request()));
        try {
            done.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            done.cancel(true);
            throw new AssertionError("제출 작업이 20초 안에 끝나지 않았다 — 미완료를 정상 종료로 보지 않는다", e);
        } catch (ExecutionException e) {
            throw new AssertionError("제출 작업이 예외로 끝났다: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 인터럽트", e);
        }
    }

'''
s = s.replace(old_wait, new_wait)
call = '        engine.submitPreparedOrder(intent.orderId(), intent.request());\n        awaitDrained();\n'
n = s.count(call)
assert n == 2, n
s = s.replace(call, '        dispatchAndAwait(intent);\n')
s = s.replace('import org.springframework.boot.test.mock.mockito.MockBean;\n',
              'import org.springframework.boot.test.mock.mockito.MockBean;\nimport org.springframework.test.util.AopTestUtils;\n')
s = s.replace('import java.util.List;\n',
              'import java.util.List;\nimport java.util.concurrent.ExecutionException;\nimport java.util.concurrent.Future;\n'
              'import java.util.concurrent.TimeUnit;\nimport java.util.concurrent.TimeoutException;\n')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
