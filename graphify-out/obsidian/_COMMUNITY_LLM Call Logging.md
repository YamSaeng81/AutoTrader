---
type: community
cohesion: 0.24
members: 12
---

# LLM Call Logging

**Cohesion:** 0.24 - loosely connected
**Members:** 12 nodes

## Members
- [[.countByCalledAtBetween()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.findAllByOrderByCalledAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.findByProviderNameOrderByCalledAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.findByTaskNameOrderByCalledAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.findTop50ByOrderByCalledAtDesc()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.getLogs()_1]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\controller\LlmConfigController.java
- [[.getTokenStats()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\controller\LlmConfigController.java
- [[.sumTokensByProvider()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.sumTokensByTask()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[.sumTotalTokensBetween()]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[LlmCallLogRepository]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java
- [[LlmCallLogRepository.java]] - code - D:\Claude Code\projects\crypto-auto-trader\web-api\src\main\java\com\cryptoautotrader\api\repository\LlmCallLogRepository.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/LLM_Call_Logging
SORT file.name ASC
```

## Connections to other communities
- 2 edges to [[_COMMUNITY_Infrastructure Config]]
- 2 edges to [[_COMMUNITY_LLM Task Router & AI Tests]]
- 2 edges to [[_COMMUNITY_Web API Controllers]]
- 1 edge to [[_COMMUNITY_Strategy Unit Tests]]
- 1 edge to [[_COMMUNITY_Backtest Runner & Orchestration]]

## Top bridge nodes
- [[.getTokenStats()]] - degree 8, connects to 4 communities
- [[.getLogs()_1]] - degree 7, connects to 4 communities