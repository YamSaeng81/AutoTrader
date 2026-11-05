package com.cryptoautotrader.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>N20 조사 — 보유 시세 보강({@link HeldMarkRefresher})의 응답 항목 격리와 호가 대체 로컬 재현</b> (총괄 v1.125 §4, P2·조사, 결함 미확정).
 *
 * <p>운영 코드는 바꾸지 않는다. 이 시험은 지금 동작을 <b>기록</b>한다(바람직한 계약을 단언하지 않는다). 외부 접속·DB·Spring 문맥 없이
 * 원문 클래스를 그대로 만들고, 시험 보유(코인별 10)·REST 대역·텔레그램 대역만 쓴다.</p>
 *
 * <p>입력: 시세 응답 [정상 A, 비정상 B, 정상 C] (모두 최근 체결). 비정상 B 의 종류 × 호가 상황(충분·깊이 부족·조회 실패)을 나눠
 * {@code byTrade}·{@code byBook}·{@code unobserved}·장부 출처/유효 가격·실패 표시·로그·경보를 남긴다.</p>
 */
@DisplayName("N20 보유 시세 보강 — 형식 이상 항목 뒤 체결 처리와 호가 대체 기록(운영 코드 무변경)")
class N20HeldMarkRefresherReproTest {

    static final String A = "KRW-N20A", B = "KRW-N20B", C = "KRW-N20C";
    static final BigDecimal QTY = new BigDecimal("10");
    static final String FORGED = "N20FORGED\nERROR 가짜 줄";

    MarkPriceBook book;
    HeldMarkRefresher refresher;
    UpbitRestClient rest;
    TelegramNotificationService tg;
    ListAppender<ILoggingEvent> logs;
    Logger logger;
    Instant now;

    /** 보유 조회만 흉내 낸다 — 코인별 10. */
    static class FakeJdbc extends JdbcTemplate {
        final Map<String, BigDecimal> held = new TreeMap<>();

        @Override
        public void query(String sql, RowCallbackHandler rch) {
            for (Map.Entry<String, BigDecimal> e : held.entrySet()) {
                try {
                    ResultSet rs = Mockito.mock(ResultSet.class);
                    Mockito.when(rs.getString(1)).thenReturn(e.getKey());
                    Mockito.when(rs.getBigDecimal(2)).thenReturn(e.getValue());
                    rch.processRow(rs);
                } catch (java.sql.SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return (T) held.getOrDefault((String) args[0], BigDecimal.ZERO);
        }
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        FakeJdbc jdbc = new FakeJdbc();
        for (String c : List.of(A, B, C)) jdbc.held.put(c, QTY);
        book = new MarkPriceBook();
        ReflectionTestUtils.setField(book, "jdbc", jdbc);
        refresher = new HeldMarkRefresher(book, jdbc);
        rest = Mockito.mock(UpbitRestClient.class);
        tg = Mockito.mock(TelegramNotificationService.class);
        refresher.setUpbitRestClient(rest);
        ReflectionTestUtils.setField(refresher, "telegram", tg);
        logger = (Logger) LoggerFactory.getLogger(HeldMarkRefresher.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    static Map<String, Object> ticker(Object market, Object price, Object ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("market", market);
        m.put("trade_price", price);
        m.put("trade_timestamp", ts);
        return m;
    }

    static Map<String, Object> orderbook(String coin, Instant at, Object bidPrice, Object bidSize) {
        Map<String, Object> u = new HashMap<>();
        u.put("bid_price", bidPrice);
        u.put("bid_size", bidSize);
        return Map.of("market", coin, "timestamp", at.toEpochMilli(), "orderbook_units", List.of(u));
    }

    /** 비정상 B 항목 — 종류별. */
    Map<String, Object> badItem(String kind) {
        long ts = now.minusSeconds(5).toEpochMilli();
        return switch (kind) {
            case "CONTROL" -> ticker(B, new BigDecimal("200"), ts);
            case "PRICE_FORGED" -> ticker(B, FORGED, ts);
            case "PRICE_ABC" -> ticker(B, "abc", ts);
            case "MARKET_NUM" -> ticker(12345, new BigDecimal("200"), ts);
            case "PRICE_ZERO" -> ticker(B, BigDecimal.ZERO, ts);
            case "PRICE_NEG" -> ticker(B, new BigDecimal("-1"), ts);
            case "PRICE_NULL" -> ticker(B, null, ts);
            case "TS_BAD" -> ticker(B, new BigDecimal("200"), "x");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    void stubBook(String coin, String mode) throws Exception {
        switch (mode) {
            case "DEEP" -> Mockito.doReturn(List.of(orderbook(coin, now, new BigDecimal("99"), new BigDecimal("50")))).when(rest).getOrderbook(coin);
            case "SHALLOW" -> Mockito.doReturn(List.of(orderbook(coin, now, new BigDecimal("99"), new BigDecimal("3")))).when(rest).getOrderbook(coin);
            case "FAIL" -> Mockito.doThrow(new RuntimeException("호가 503(시험)")).when(rest).getOrderbook(coin);
            case "BADBOOK" -> Mockito.doReturn(List.of(orderbook(coin, now, "x1", new BigDecimal("50")))).when(rest).getOrderbook(coin);
            default -> throw new IllegalArgumentException(mode);
        }
    }

    String row(String kase, String bookMode, HeldMarkRefresher.Result r) {
        StringBuilder src = new StringBuilder();
        for (String c : List.of(A, B, C)) {
            MarkPriceBook.Mark m = book.get(c);
            src.append(c.substring(4)).append(':')
                    .append(m == null ? "-" : m.source() + "/" + book.freshPrice(c, now)).append(' ');
        }
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : logs.list) lines.add(e.getLevel() + " " + e.getFormattedMessage());
        int tgCount = Mockito.mockingDetails(tg).getInvocations().size();
        String tgText = Mockito.mockingDetails(tg).getInvocations().stream().map(i -> String.valueOf((Object) i.getArgument(0))).reduce("", String::concat);
        return "N20행|" + kase + "|호가=" + bookMode + "|byTrade=" + short3(r.byTrade()) + "|byBook=" + short3(r.byBook())
                + "|unobserved=" + short3(r.unobserved()) + "|장부=" + src.toString().trim()
                + "|실패표시=" + (refresher.failingSince() != null) + "|경보=" + tgCount
                + "|오류문=" + oneLine(r.error())
                + "|로그=" + oneLine(String.join(" ⏎ ", lines))
                + "|오류문·경보·로그에_FORGED=" + (contains(r.error(), "FORGED") || tgText.contains("FORGED") || String.join("", lines).contains("FORGED"))
                + "|오류문·경보·로그에_줄바꿈=" + (contains(r.error(), "\n") || tgText.contains("\n") || lines.stream().anyMatch(l -> l.contains("\n")));
    }

    static boolean contains(String s, String t) {
        return s != null && s.contains(t);
    }

    static String oneLine(String s) {
        return s == null ? "null" : s.replace("\r", "\\r").replace("\n", "\\n");
    }

    static String short3(List<String> l) {
        return l.stream().map(c -> c.substring(4)).toList().toString();
    }

    /**
     * 비정상 B 는 가운데. 호가는 세 코인 모두 같은 상황. 기대값은 7a94472/ee509cf 원문을 읽어 예측한 지금 동작이다(바람직한 동작이 아니다).
     * cut = 시세 루프가 B 에서 끊겨 C 가 체결 처리되지 않는가.
     */
    @ParameterizedTest(name = "{0} × 호가 {1}")
    @CsvSource({
            "CONTROL,DEEP,false",
            "CONTROL,FAIL,false",
            "PRICE_FORGED,DEEP,true",
            "PRICE_FORGED,SHALLOW,true",
            "PRICE_FORGED,FAIL,true",
            "PRICE_ABC,DEEP,true",
            "PRICE_ABC,FAIL,true",
            "MARKET_NUM,DEEP,true",
            "MARKET_NUM,SHALLOW,true",
            "MARKET_NUM,FAIL,true",
            "PRICE_ZERO,DEEP,false",
            "PRICE_ZERO,FAIL,false",
            "PRICE_NEG,DEEP,false",
            "PRICE_NULL,DEEP,false",
            "TS_BAD,DEEP,false",
    })
    @DisplayName("시세 [정상 A, 비정상 B, 정상 C] × 호가 상황")
    void middleBadItem(String kind, String bookMode, boolean cut) throws Exception {
        long ts = now.minusSeconds(5).toEpochMilli();
        Mockito.doReturn(List.of(ticker(A, new BigDecimal("100"), ts), badItem(kind), ticker(C, new BigDecimal("300"), ts)))
                .when(rest).getTicker(Mockito.anyString());
        for (String c : List.of(A, B, C)) stubBook(c, bookMode);

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind, bookMode, r));

        assertThat(r.targets()).containsExactly(A, B, C);
        assertThat(r.byTrade()).as("A 는 항상 체결 처리").contains(A);
        boolean bPublished = kind.equals("CONTROL");
        assertThat(r.byTrade().contains(B)).as("B 체결 처리").isEqualTo(bPublished);
        assertThat(r.byTrade().contains(C)).as("C 체결 처리 — 끊김이면 거짓").isEqualTo(!cut);
        if (cut) {
            assertThat(r.error()).as("배치 catch 의 오류문").startsWith("시세 조회 실패: ");
            assertThat(book.get(C) == null || !MarkPriceBook.REST_TRADE.equals(book.get(C).source()))
                    .as("C 의 정상 체결가가 장부에 적히지 않았다").isTrue();
        }
        // C 는 체결 처리가 끊겨도 호가로 대체되는가 — 호가 상황에 따라 나뉜다
        if (cut) {
            switch (bookMode) {
                case "DEEP" -> {
                    assertThat(r.byBook()).contains(C);
                    assertThat(book.get(C).source()).isEqualTo(MarkPriceBook.REST_ORDERBOOK_DEPTH);
                    assertThat(book.freshPrice(C, now)).isEqualByComparingTo("99");
                    assertThat(r.unobserved()).doesNotContain(C);
                }
                case "SHALLOW", "FAIL" -> {
                    assertThat(r.byBook()).doesNotContain(C);
                    assertThat(r.unobserved()).contains(C);
                    assertThat(refresher.failingSince()).as("실패 표시").isNotNull();
                    Mockito.verify(tg, Mockito.times(1)).sendCustomNotification(Mockito.anyString());
                }
                default -> { }
            }
        }
    }

    @ParameterizedTest(name = "{0} × C 기존 체결가 {1}초 전 × 호가 FAIL")
    @CsvSource({"PRICE_FORGED,90", "PRICE_FORGED,130", "MARKET_NUM,90"})
    @DisplayName("C 에 기존 체결 관측이 있을 때 — 끊김 뒤 호가 실패면 기존 관측의 남은 유효 시간만큼만 관측 유지")
    void existingMarkForC(String kind, long ageSec) throws Exception {
        long ts = now.minusSeconds(5).toEpochMilli();
        book.observe(C, new BigDecimal("290"), now.minusSeconds(ageSec), now.minusSeconds(ageSec), MarkPriceBook.WS_TRADE);
        Mockito.doReturn(List.of(ticker(A, new BigDecimal("100"), ts), badItem(kind), ticker(C, new BigDecimal("300"), ts)))
                .when(rest).getTicker(Mockito.anyString());
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind + "+C기존" + ageSec + "초", "FAIL", r));

        assertThat(r.targets()).contains(C);
        assertThat(r.byTrade()).doesNotContain(C);
        assertThat(book.get(C).source()).as("새 체결가 300 이 아니라 기존 관측 그대로").isEqualTo(MarkPriceBook.WS_TRADE);
        assertThat(r.unobserved().contains(C)).as("120초를 넘었으면 관측 없음").isEqualTo(ageSec > 120);
    }

    @ParameterizedTest(name = "B 호가 형식 이상 × 시세 {0}")
    @CsvSource({"PRICE_FORGED", "CONTROL"})
    @DisplayName("호가 경로는 코인별 catch — B 의 호가 형식 이상은 C 의 호가 대체를 끊지 않는다")
    void badBookForBOnly(String kind) throws Exception {
        long ts = now.minusSeconds(5).toEpochMilli();
        Mockito.doReturn(List.of(ticker(A, new BigDecimal("100"), ts), badItem(kind), ticker(C, new BigDecimal("300"), ts)))
                .when(rest).getTicker(Mockito.anyString());
        stubBook(A, "DEEP");
        stubBook(B, "BADBOOK");
        stubBook(C, "DEEP");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind + "+B호가형식이상", "B=BADBOOK,C=DEEP", r));

        if (kind.equals("CONTROL")) {
            assertThat(r.unobserved()).isEmpty();
            Mockito.verify(rest, Mockito.never()).getOrderbook(Mockito.anyString());
        } else {
            assertThat(r.byBook()).contains(C);
            assertThat(r.unobserved()).contains(B).doesNotContain(A, C);
            assertThat(r.error()).contains(B + " 호가 조회 실패: ");
        }
    }
}
