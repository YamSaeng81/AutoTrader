import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
def rep(o,n):
    global s
    assert s.count(o)==1,o; s=s.replace(o,n)
rep("        final AtomicBoolean gateOnce = new AtomicBoolean(true);\n",
    "        final AtomicBoolean gateOnce = new AtomicBoolean(true);\n"
    "        /** 코어 작업자가 runWorker 에 들어가 대기열에서 기다리기 시작했다는 신호(총괄 v1.167 §4-5, ExecutorBarrierPremiseProbeTest). */\n"
    "        final CountDownLatch inTake = new CountDownLatch(2);\n")
rep("                public Runnable take() throws InterruptedException {\n                    Runnable r = super.take();",
    "                public Runnable take() throws InterruptedException {\n                    inTake.countDown();\n                    Runnable r = super.take();")
rep("            pool.prestartAllCoreThreads();\n",
    "            pool.prestartAllCoreThreads();\n"
    "            // 🔴 (총괄 v1.167 §4-5, 시험만) 시작됐지만 아직 runWorker 전인 작업자는 상태 -1 이라 getActiveCount·getTaskCount 에\n"
    "            //    1 로 세진다 — 붐비는 전체 회귀에서 둘째 작업자가 그 상태면 아래 옛 조건이 거짓이 된다(b2976905 전체 회귀 1회 실패,\n"
    "            //    기전은 ExecutorBarrierPremiseProbeTest 가 결정적으로 재현). 두 코어 작업자가 대기열에 들어간 것을 신호로 확인한다.\n"
    "            assertThat(ex.inTake.await(5, TimeUnit.SECONDS)).as(\"코어 작업자 2개가 대기열에서 기다린다\").isTrue();\n")
rep("            boolean oldPredicate = pool.getQueue().isEmpty() && pool.getActiveCount() == 0\n                    && pool.getTaskCount() == pool.getCompletedTaskCount();\n",
    "            boolean oldPredicate = pool.getQueue().isEmpty() && pool.getActiveCount() == 0\n                    && pool.getTaskCount() == pool.getCompletedTaskCount();\n"
    "            String poolState = \"pool=\" + pool.getPoolSize() + \" active=\" + pool.getActiveCount() + \" queue=\" + pool.getQueue().size()\n"
    "                    + \" task=\" + pool.getTaskCount() + \" completed=\" + pool.getCompletedTaskCount();\n")
rep('assertThat(oldPredicate).as("옛 조건은 이 창에서 참")', 'assertThat(oldPredicate).as("옛 조건은 이 창에서 참 (" + poolState + ")")')
open(p,'w',encoding='utf-8').write(s); print("ok")
