---
type: community
cohesion: 0.12
members: 21
---

# Order Execution & Repository

**Cohesion:** 0.12 - loosely connected
**Members:** 21 nodes

## Members
- [[.cancelAllActiveOrders_cancelsAllActive()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.cancelOrder_notFoundThrows()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.cleanup()_1]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.countBySessionIdIsNotNullAndStateIn()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.existsByCoinPairAndSideAndStateIn()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.failedOrder()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.filledOrder()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.findAllByOrderByCreatedAtDesc()_3]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.findByCreatedAtBetweenOrderByCreatedAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.findByExchangeOrderId()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.findBySessionIdAndCreatedAtBetweenOrderByCreatedAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.findBySessionIdAndStateIn()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.findBySessionIdOrderByCreatedAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[.getOrders()_2]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\OrderExecutionEngine.java
- [[.pendingOrder()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.repository_duplicateDetection()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[.submittedOrder()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[OrderExecutionEngineTest]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[OrderExecutionEngineTest.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\OrderExecutionEngineTest.java
- [[OrderRepository]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java
- [[OrderRepository.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\OrderRepository.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Order_Execution_&_Repository
SORT file.name ASC
```

## Connections to other communities
- 15 edges to [[_COMMUNITY_Backtest Runner & Orchestration]]
- 2 edges to [[_COMMUNITY_Strategy Unit Tests]]
- 1 edge to [[_COMMUNITY_Web API Controllers]]

## Top bridge nodes
- [[.cancelAllActiveOrders_cancelsAllActive()]] - degree 8, connects to 2 communities
- [[OrderExecutionEngineTest]] - degree 13, connects to 1 community
- [[OrderRepository]] - degree 12, connects to 1 community
- [[.getOrders()_2]] - degree 5, connects to 1 community
- [[.repository_duplicateDetection()]] - degree 4, connects to 1 community