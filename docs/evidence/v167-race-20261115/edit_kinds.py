import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(old, new, cnt=1):
    global s
    assert s.count(old) == cnt, (old[:60], s.count(old))
    s = s.replace(old, new)


rep("import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;\n",
    "import com.cryptoautotrader.api.entity.DynamicSessionEntity;\nimport com.cryptoautotrader.api.entity.LiveTradingSessionEntity;\n")
rep("import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;\n",
    "import com.cryptoautotrader.api.repository.DynamicSessionRepository;\nimport com.cryptoautotrader.api.repository.LiveTradingSessionRepository;\n")
rep("import org.junit.jupiter.api.Test;\n",
    "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.params.ParameterizedTest;\nimport org.junit.jupiter.params.provider.CsvSource;\n")
rep("    @Autowired private TradeLogRepository tradeLogRepository;\n",
    "    @Autowired private TradeLogRepository tradeLogRepository;\n    @Autowired private DynamicSessionRepository dynamicRepo;\n")
rep("    private final List<Long> createdSessions = new ArrayList<>();\n",
    "    private final List<Long> createdSessions = new ArrayList<>();\n    private final List<Long> createdDynamic = new ArrayList<>();\n")
rep("""        createdSessions.clear();
    }""", """        createdSessions.clear();
        createdDynamic.forEach(id -> {
            List<Long> orderIds = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).stream()
                    .map(OrderEntity::getId).toList();
            tradeLogRepository.findAll().stream().filter(l -> orderIds.contains(l.getOrderId())).forEach(tradeLogRepository::delete);
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("DYNAMIC", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("DYNAMIC", id).forEach(positionRepository::delete);
            dynamicRepo.deleteById(id);
        });
        createdDynamic.clear();
    }""")
rep("record Fixture(Long sessionId, Long positionId, OrderRequest request) { }",
    "record Fixture(String kind, Long sessionId, Long positionId, OrderRequest request) { }")
rep("""    Fixture runningSessionWithBuyRequest() {
        LiveTradingSessionEntity session""", """    Fixture runningSessionWithBuyRequest() {
        return fixture("LIVE", "BUY");
    }

    /** LIVE·DYNAMIC 실자금 세션의 시장가 매수(3,000원 선차감) 또는 보유 9.8개 시장가 매도. */
    Fixture fixture(String kind, String side) {
        boolean sell = "SELL".equals(side);
        Long sessionId;
        if ("DYNAMIC".equals(kind)) {
            sessionId = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(new BigDecimal("10000.00"))
                    .availableKrw(new BigDecimal("7000.00"))
                    .totalAssetKrw(new BigDecimal("10000.00"))
                    .investRatio(new BigDecimal("0.3000")).stopLossPct(new BigDecimal("5.00"))
                    .status("RUNNING").scanState("POSITION_MONITORING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60)
                    .build()).getId();
            createdDynamic.add(sessionId);
        } else {
            sessionId = liveSession();
        }
        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(sell ? new BigDecimal("9.80000000") : BigDecimal.ZERO)
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind(kind).sessionId(sessionId)
                .openedAt(Instant.now()).build());

        OrderRequest req = new OrderRequest();
        req.setCoinPair("KRW-DOGE");
        req.setSide(side);
        req.setOrderType("MARKET");
        req.setQuantity(sell ? new BigDecimal("9.80000000") : new BigDecimal("3000.00000000"));
        req.setReason("전략 신호 — 제출·해소 경합 시험");
        req.setSignalPrice(new BigDecimal("300.00000000"));
        req.setSessionId(sessionId);
        req.setSessionKind(kind);
        req.setPositionId(pos.getId());
        return new Fixture(kind, sessionId, pos.getId(), req);
    }

    private Long liveSession() {
        LiveTradingSessionEntity session""")
start = s.index("        createdSessions.add(session.getId());\n")
endmark = "        return new Fixture(session.getId(), pos.getId(), req);\n    }\n"
end = s.index(endmark)
s = s[:start] + "        createdSessions.add(session.getId());\n        return session.getId();\n    }\n" + s[end + len(endmark):]
rep("""        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", f.sessionId()).stream()
                .filter(o -> identifier""", """        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(f.kind(), f.sessionId()).stream()
                .filter(o -> identifier""")
rep("""    private String snapshot(Fixture f, Long id) {""", """    // ── 사례 A·B 를 LIVE 매도·DYNAMIC 매수·DYNAMIC 매도에서 (총괄 v1.167 §4-4) ─────

    @ParameterizedTest(name = "{0} {1} — 순서 {2}")
    @CsvSource({"LIVE,SELL,A", "LIVE,SELL,B", "DYNAMIC,BUY,A", "DYNAMIC,BUY,B", "DYNAMIC,SELL,A", "DYNAMIC,SELL,B"})
    @DisplayName("A·B — LIVE 매도·DYNAMIC 매수/매도에서도 제출 결과의 SUBMITTED·주문번호를 보존한다")
    void kindsAndSides(String kind, String side, String order) throws Exception {
        Fixture f = fixture(kind, side);
        CreateGate create = new CreateGate();
        String uuid = "uuid-race-" + kind + "-" + side + "-" + order;
        stubCreate(create, uuid, false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Long id;
        if ("A".equals(order)) {
            QueryGate query = new QueryGate();
            stubIdentifierQuery(query, null);
            id = submitAndPark(f, create);
            Thread resolver = startResolver(id, failure);
            await(query.querying, "폴러의 식별자 조회");
            create.release.countDown();
            awaitSubmitThreadDone();
            assertThat(reload(id).getState()).as("전제: 제출 결과가 먼저 커밋됐다").isEqualTo("SUBMITTED");
            query.release.countDown();
            join(resolver, failure);
            assertThat(transitions(id)).containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING", "SUBMITTING→SUBMITTED");
        } else {
            stubIdentifierQuery(null, null);
            id = submitAndPark(f, create);
            join(startResolver(id, failure), failure);
            assertThat(reload(id).getState()).as("전제: 폴러의 UNKNOWN 이 먼저 커밋됐다").isEqualTo("UNKNOWN");
            create.release.countDown();
            awaitSubmitThreadDone();
            assertThat(reload(id).getUnknownReason()).isNull();
            assertThat(transitions(id)).containsExactly("null→INTENT_READY", "INTENT_READY→SUBMITTING",
                    "SUBMITTING→UNKNOWN", "UNKNOWN→SUBMITTED");
        }
        OrderEntity o = reload(id);
        assertThat(o.getState()).isEqualTo("SUBMITTED");
        assertThat(o.getExchangeOrderId()).isEqualTo(uuid);
        assertThat(o.getSubmittedAt()).isNotNull();
        verifySubmittedOnce();
    }

    private String snapshot(Fixture f, Long id) {""")
open(p, 'w', encoding='utf-8').write(s)
print("ok")
