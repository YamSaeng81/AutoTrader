import re,sys
sys.stdout.reconfigure(encoding="utf-8")
D=sys.argv[1]
for n in sys.argv[2:]:
    p=f"{D}/{n}Test.java"; src=open(p,encoding="utf-8").read()
    m=re.search(r'\n(    (?:private |static |public )*(?:static )?void (awaitDrained|awaitOrderExecutorDrained|drain)\(([^)]*)\)\s*\{)',src)
    assert m, n
    start=m.start(1); i=src.index('{',m.start(1))
    depth=0; j=i
    while True:
        c=src[j]
        if c=='{': depth+=1
        elif c=='}':
            depth-=1
            if depth==0: break
        j+=1
    body=src[i:j+1]
    assert 'getCompletedTaskCount' in body, n
    params=m.group(3).strip()
    if params:
        exe=params.split()[-1]
    else:
        e=re.search(r'var pool = (\w+)\.getThreadPoolExecutor\(\)',body); exe=e.group(1)
    t=re.search(r'\+ (\d[\d_]*);',body).group(1)
    new=(m.group(1)+"\n"
      "        // 🔴 (총괄 v1.164 §3-2, 2026-11-13, 시험만) 옛 조건(대기열 비었음·active 0·taskCount == completed)은 놀고 있던 작업자가\n"
      "        //    작업을 꺼낸 뒤 실행 표시 전에도 참이다 — DrainGapGate(꺼낸 뒤 머묾)로 이 클래스의 단언이 작업 전에 읽히는 것을 고정 재현했다.\n"
      "        //    이 호출 전에 넣은 작업이 모두 끝났음을 실행기 장벽(확정 신호)으로 기다린다. 시간 초과는 실패다. 기대값·단언은 그대로다.\n"
      f"        com.cryptoautotrader.api.support.ExecutorBarrier.awaitPriorTasks({exe}.getThreadPoolExecutor(), {t});\n"
      "    }")
    src=src[:start]+new+src[j+1:]
    open(p,"w",encoding="utf-8",newline="").write(src)
    print(n, m.group(2), exe, t)
