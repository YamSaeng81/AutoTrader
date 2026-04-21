---
type: community
cohesion: 0.15
members: 20
---

# Execution Drift Tracking

**Cohesion:** 0.15 - loosely connected
**Members:** 20 nodes

## Members
- [[.avgSlippagePctSince()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\ExecutionDriftLogRepository.java
- [[.checkDriftAlert()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[.cleanup()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.findTop20BySessionIdOrderByExecutedAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\ExecutionDriftLogRepository.java
- [[.findTop50ByStrategyTypeOrderByExecutedAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\ExecutionDriftLogRepository.java
- [[.getRecentBySession()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[.getRecentBySession_returnsSessionRecords()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.getWeeklyAvgSlippage()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[.getWeeklyAvgSlippage_computesCorrectly()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.getWeeklyAvgSlippage_excludesOldRecords()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.record()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[.record_calculatesSlippageCorrectly()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.record_negativeSlippage()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[.record_skipsWhenSignalPriceIsZero()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[ExecutionDriftLogRepository]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\ExecutionDriftLogRepository.java
- [[ExecutionDriftLogRepository.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\ExecutionDriftLogRepository.java
- [[ExecutionDriftTracker]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[ExecutionDriftTracker.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\service\ExecutionDriftTracker.java
- [[ExecutionDriftTrackerTest]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java
- [[ExecutionDriftTrackerTest.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\test\java\com\cryptoautotrader\api\service\ExecutionDriftTrackerTest.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Execution_Drift_Tracking
SORT file.name ASC
```

## Connections to other communities
- 4 edges to [[_COMMUNITY_Backtest Runner & Orchestration]]
- 2 edges to [[_COMMUNITY_Strategy Config & Parameters]]
- 1 edge to [[_COMMUNITY_Backtest Configuration]]

## Top bridge nodes
- [[.record_calculatesSlippageCorrectly()]] - degree 5, connects to 2 communities
- [[.record()]] - degree 7, connects to 1 community
- [[.getRecentBySession_returnsSessionRecords()]] - degree 4, connects to 1 community
- [[.checkDriftAlert()]] - degree 3, connects to 1 community
- [[.record_negativeSlippage()]] - degree 3, connects to 1 community