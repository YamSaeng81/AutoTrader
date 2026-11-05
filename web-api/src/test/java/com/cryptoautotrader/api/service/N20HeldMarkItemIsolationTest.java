package com.cryptoautotrader.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * <b>N20 — 보유 시세 보강({@link HeldMarkRefresher})의 응답 항목 격리·고정 기록 계약</b> (총괄 v1.126 §94).
 *
 * <p>조사 시험 {@code N20HeldMarkRefresherReproTest}(52114a3) 는 지금 동작을 기록했다. 이 시험은 바뀐 계약을 단언한다.</p>
 * <ul>
 *   <li>형식 이상 한 항목은 그 항목만 고정 WARN 뒤 건너뛰고, 뒤 정상 코인 C 는 이번 체결가(REST_TRADE 300)로 관측된다 — C 호가 호출 없음.</li>
 *   <li>0·음수 가격은 {@code observe}·{@code byTrade} 앞에서 거른다 — 기존 신선한 장부가 있어도 이번 체결로 세지 않는다.</li>
 *   <li>외부 값·예외 문구는 Result.error·실패 표시·로그·경보·회복 기록 어디에도 실리지 않는다(고정 사유만).</li>
 *   <li>관측 가격 없음의 ERROR·경보 한 번·사유 변경 WARN·회복 WARN 규칙과 차단 방향은 그대로다.</li>
 * </ul>
 * <p>외부 접속·DB·Spring 문맥 없이 원문 클래스를 만들고 시험 보유(코인별 10)·REST 대역·텔레그램 대역만 쓴다.</p>
 */
@DisplayName("N20 보유 시세 보강 — 항목 격리·고정 기록 계약")
class N20HeldMarkItemIsolationTest {

    static final String A = "KRW-N20A", B = "KRW-N20B", C = "KRW-N20C";
    static final BigDecimal QTY = new BigDecimal("10");
    static final String FORGED = "N20FORGED\nERROR 가짜 줄";
    static final String W_NAN = "[시세 보강] 시세 항목 건너뜀 — 가격 형식이 숫자가 아님";
    static final String W_MARKET = "[시세 보강] 시세 항목 건너뜀 — 코인 형식이 문자열이 아님";
    static final String W_ZERO = "[시세 보강] 시세 항목 건너뜀 — 가격이 아님 (부호=0)";
    static final String W_NEG = "[시세 보강] 시세 항목 건너뜀 — 가격이 아님 (부호=음수)";

    MarkPriceBook book;
    HeldMarkRefresher refresher;
    UpbitRestClient rest;
    TelegramNotificationService tg;
    FakeJdbc jdbc;
    ListAppender<ILoggingEvent> logs;
    Logger logger;
    Instant now;

    /** 보유 조회만 흉내 낸다 — 코인별 10. {@code failWith} 가 있으면 보유 조회가 그 예외를 던진다(회차 오류 경로). */
    static class FakeJdbc extends JdbcTemplate {
        final Map<String, BigDecimal> held = new TreeMap<>();
        RuntimeException failWith;

        @Override
        public void query(String sql, RowCallbackHandler rch) {
            if (failWith != null) throw failWith;
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
        setUpWith(new MarkPriceBook());
    }

    void setUpWith(MarkPriceBook b) {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbc = new FakeJdbc();
        for (String c : List.of(A, B, C)) jdbc.held.put(c, QTY);
        book = b;
        ReflectionTestUtils.setField(book, "jdbc", jdbc);
        refresher = new HeldMarkRefresher(book, jdbc);
        rest = Mockito.mock(UpbitRestClient.class);
        tg = Mockito.mock(TelegramNotificationService.class);
        refresher.setUpbitRestClient(rest);
        ReflectionTestUtils.setField(refresher, "telegram", tg);
        logger = (Logger) LoggerFactory.getLogger(HeldMarkRefresher.class);
        if (logs != null) logger.detachAppender(logs);
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

    static String expectedWarn(String kind) {
        return switch (kind) {
            case "PRICE_FORGED", "PRICE_ABC" -> W_NAN;
            case "MARKET_NUM" -> W_MARKET;
            case "PRICE_ZERO" -> W_ZERO;
            case "PRICE_NEG" -> W_NEG;
            default -> null; // 대조군·누락/null·체결 시각 이상은 종전처럼 조용히 건너뜀
        };
    }

    void stubBook(String coin, String mode) throws Exception {
        switch (mode) {
            case "DEEP" -> Mockito.doReturn(List.of(orderbook(coin, now, new BigDecimal("99"), new BigDecimal("50")))).when(rest).getOrderbook(coin);
            case "SHALLOW" -> Mockito.doReturn(List.of(orderbook(coin, now, new BigDecimal("99"), new BigDecimal("3")))).when(rest).getOrderbook(coin);
            case "FAIL" -> Mockito.doThrow(new RuntimeException("호가 503 " + FORGED)).when(rest).getOrderbook(coin);
            case "BADBOOK" -> Mockito.doReturn(List.of(orderbook(coin, now, "x1" + FORGED, new BigDecimal("50")))).when(rest).getOrderbook(coin);
            default -> throw new IllegalArgumentException(mode);
        }
    }

    void stubTicker(Map<String, Object> middle) throws Exception {
        long ts = now.minusSeconds(5).toEpochMilli();
        Mockito.doReturn(List.of(ticker(A, new BigDecimal("100"), ts), middle, ticker(C, new BigDecimal("300"), ts)))
                .when(rest).getTicker(Mockito.anyString());
    }

    List<String> lines() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : logs.list) out.add(e.getLevel() + " " + e.getFormattedMessage());
        return out;
    }

    List<String> warns() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    String tgText() {
        return Mockito.mockingDetails(tg).getInvocations().stream().map(i -> String.valueOf((Object) i.getArgument(0)))
                .reduce("", (x, y) -> x + y + "\u0000");
    }

    int tgCount() {
        return Mockito.mockingDetails(tg).getInvocations().size();
    }

    /** 외부 값·예외 문구가 오류문·실패 표시·로그·경보 어디에도 없다. */
    void assertNoRawText(HeldMarkRefresher.Result r) {
        List<String> all = new ArrayList<>(lines());
        all.add(r == null || r.error() == null ? "" : r.error());
        all.add(refresher.failingSince() == null ? "" : refresher.failingSince());
        all.add(tgText());
        for (String s : all) {
            assertThat(s).as("가짜 표지").doesNotContain("N20FORGED").doesNotContain("가짜 줄");
            assertThat(s).as("줄바꿈").doesNotContain("\n").doesNotContain("\r");
            assertThat(s).as("파서·형변환 예외 문구").doesNotContain("Character").doesNotContain("cannot be cast")
                    .doesNotContain("java.lang").doesNotContain("abc").doesNotContain("ticker 500").doesNotContain("호가 503")
                    .doesNotContain("telegram 429").doesNotContain("x1");
        }
    }

    String row(String kase, HeldMarkRefresher.Result r) {
        StringBuilder src = new StringBuilder();
        for (String c : List.of(A, B, C)) {
            MarkPriceBook.Mark m = book.get(c);
            src.append(c.substring(4)).append(':').append(m == null ? "-" : m.source() + "/" + book.freshPrice(c, now)).append(' ');
        }
        return "N20계약행|" + kase + "|byTrade=" + short3(r.byTrade()) + "|byBook=" + short3(r.byBook())
                + "|unobserved=" + short3(r.unobserved()) + "|장부=" + src.toString().trim()
                + "|실패표시=" + (refresher.failingSince() != null) + "|경보=" + tgCount()
                + "|오류문=" + oneLine(r.error()) + "|로그=" + oneLine(String.join(" ⏎ ", lines()));
    }

    static String oneLine(String s) {
        return s == null ? "null" : s.replace("\r", "\\r").replace("\n", "\\n");
    }

    static String short3(List<String> l) {
        return l.stream().map(c -> c.substring(4)).toList().toString();
    }

    @ParameterizedTest(name = "{0} × 호가 {1}")
    @CsvSource({
            "CONTROL,DEEP", "CONTROL,FAIL",
            "PRICE_FORGED,DEEP", "PRICE_FORGED,SHALLOW", "PRICE_FORGED,FAIL",
            "PRICE_ABC,DEEP", "PRICE_ABC,SHALLOW", "PRICE_ABC,FAIL",
            "MARKET_NUM,DEEP", "MARKET_NUM,SHALLOW", "MARKET_NUM,FAIL",
            "PRICE_ZERO,DEEP", "PRICE_ZERO,FAIL",
            "PRICE_NEG,DEEP", "PRICE_NEG,FAIL",
            "PRICE_NULL,DEEP", "PRICE_NULL,FAIL",
            "TS_BAD,DEEP", "TS_BAD,FAIL",
    })
    @DisplayName("① 시세 [정상 A, 이상 B, 정상 C] — B 만 건너뛰고 C 는 이번 체결가, C 호가 호출 없음")
    void middleBadItemIsIsolated(String kind, String bookMode) throws Exception {
        stubTicker(badItem(kind));
        for (String c : List.of(A, B, C)) stubBook(c, bookMode);

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind + "×" + bookMode, r));

        assertThat(r.targets()).containsExactly(A, B, C);
        assertThat(r.byTrade()).as("A·C 는 이번 체결로 관측").contains(A, C);
        assertThat(book.get(C).source()).isEqualTo(MarkPriceBook.REST_TRADE);
        assertThat(book.freshPrice(C, now)).isEqualByComparingTo("300");
        Mockito.verify(rest, Mockito.never()).getOrderbook(A);
        Mockito.verify(rest, Mockito.never()).getOrderbook(C);

        boolean control = kind.equals("CONTROL");
        assertThat(r.byTrade().contains(B)).as("B 이번 체결 처리").isEqualTo(control);
        if (!control) {
            switch (bookMode) {
                case "DEEP" -> {
                    assertThat(r.byBook()).containsExactly(B);
                    assertThat(r.unobserved()).isEmpty();
                    assertThat(r.error()).isNull();
                    assertThat(refresher.failingSince()).isNull();
                    assertThat(tgCount()).isZero();
                    assertThat(lines()).anyMatch(l -> l.startsWith("INFO [시세 보강] 호가로 보강한 보유 [" + B + "]"));
                }
                case "SHALLOW", "FAIL" -> {
                    assertThat(r.byBook()).isEmpty();
                    assertThat(r.unobserved()).containsExactly(B);
                    assertThat(refresher.failingSince()).as("차단 표시 유지").isNotNull();
                    assertThat(tgCount()).as("실패 시작 경보 1회").isEqualTo(1);
                    assertThat(lines().stream().filter(l -> l.startsWith("ERROR ")).count()).isEqualTo(1);
                    assertThat(r.error()).isEqualTo(bookMode.equals("FAIL") ? B + " 호가 조회 실패; "
                            : B + " 호가 깊이 부족(보이는 매수 잔량 3 < 보유 10); ");
                }
                default -> { }
            }
        } else {
            assertThat(r.unobserved()).isEmpty();
            Mockito.verify(rest, Mockito.never()).getOrderbook(Mockito.anyString());
        }
        assertThat(lines()).as("호가 INFO 는 체결이 조용하다고 말하지 않는다").noneMatch(l -> l.contains("체결이 조용한"));
        String w = expectedWarn(kind);
        List<String> itemWarns = warns().stream().filter(x -> x.startsWith("[시세 보강] 시세 항목 건너뜀")).toList();
        if (w == null) assertThat(itemWarns).isEmpty();
        else assertThat(itemWarns).as("호가가 충분해도 고정 WARN 1줄").containsExactly(w);
        assertNoRawText(r);
    }

    @ParameterizedTest(name = "{0} × C 기존 WS {1}초 전 × 호가 FAIL")
    @CsvSource({"PRICE_FORGED,90", "PRICE_FORGED,130", "MARKET_NUM,90", "MARKET_NUM,130"})
    @DisplayName("② C 에 기존 관측이 있어도 이번 체결가 300 으로 갱신된다(끊김 없음)")
    void existingMarkForC(String kind, long ageSec) throws Exception {
        book.observe(C, new BigDecimal("290"), now.minusSeconds(ageSec), now.minusSeconds(ageSec), MarkPriceBook.WS_TRADE);
        stubTicker(badItem(kind));
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind + "+C기존" + ageSec + "초", r));

        assertThat(r.byTrade()).contains(A, C);
        assertThat(book.get(C).source()).isEqualTo(MarkPriceBook.REST_TRADE);
        assertThat(book.freshPrice(C, now)).isEqualByComparingTo("300");
        assertThat(r.unobserved()).containsExactly(B);
        assertNoRawText(r);
    }

    @ParameterizedTest(name = "B 기존 WS 90초 전 190 + 이번 {0} × 호가 {1}")
    @CsvSource({"PRICE_ZERO,FAIL", "PRICE_NEG,FAIL", "PRICE_ZERO,DEEP", "PRICE_NEG,DEEP"})
    @DisplayName("③ B 에 기존 신선한 장부가 있어도 이번 0·음수 가격을 byTrade 로 세지 않는다(장부는 지우지 않음)")
    void existingFreshMarkForBWithZeroPrice(String kind, String bookMode) throws Exception {
        book.observe(B, new BigDecimal("190"), now.minusSeconds(90), now.minusSeconds(90), MarkPriceBook.WS_TRADE);
        stubTicker(badItem(kind));
        for (String c : List.of(A, B, C)) stubBook(c, bookMode);

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row(kind + "+B기존90초×" + bookMode, r));

        assertThat(r.targets()).as("90초 > 60초 — 다시 조회 대상").contains(B);
        assertThat(r.byTrade()).as("이번 0·음수는 체결 성공이 아니다").doesNotContain(B).contains(A, C);
        Mockito.verify(rest).getOrderbook(B);
        if (bookMode.equals("FAIL")) {
            assertThat(book.get(B).source()).as("기존 WS 장부 그대로").isEqualTo(MarkPriceBook.WS_TRADE);
            assertThat(book.freshPrice(B, now)).isEqualByComparingTo("190");
            assertThat(r.unobserved()).as("기존 장부의 남은 유효 시간 동안 관측 유지").isEmpty();
        } else {
            assertThat(r.byBook()).containsExactly(B);
            assertThat(book.freshPrice(B, now)).isEqualByComparingTo("99");
        }
        assertThat(warns()).contains(kind.equals("PRICE_ZERO") ? W_ZERO : W_NEG);
        assertNoRawText(r);
    }

    @Test
    @DisplayName("④ B 호가만 형식 이상 — 그 코인의 호가 조회 실패(고정 문구), C 는 영향 없음")
    void badBookForBOnly() throws Exception {
        stubTicker(badItem("PRICE_FORGED"));
        stubBook(A, "DEEP");
        stubBook(B, "BADBOOK");
        stubBook(C, "DEEP");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row("PRICE_FORGED+B호가형식이상", r));

        assertThat(r.byTrade()).contains(A, C);
        assertThat(r.unobserved()).containsExactly(B);
        assertThat(r.error()).isEqualTo(B + " 호가 조회 실패; ");
        assertNoRawText(r);
    }

    @Test
    @DisplayName("⑤ 시세 조회 예외·호가 예외 원문이 오류문·실패 표시·ERROR·경보에 실리지 않는다")
    void tickerAndBookExceptionTextIsNotPropagated() throws Exception {
        Mockito.doThrow(new RuntimeException("ticker 500 " + FORGED)).when(rest).getTicker(Mockito.anyString());
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row("시세예외+호가예외", r));

        assertThat(r.unobserved()).containsExactly(A, B, C);
        assertThat(r.error()).isEqualTo("시세 조회 실패; " + A + " 호가 조회 실패; " + B + " 호가 조회 실패; " + C + " 호가 조회 실패; ");
        assertThat(refresher.failingSince()).contains("관측 가격 없음").contains("시세 조회 실패");
        assertThat(tgCount()).isEqualTo(1);
        assertThat(tgText()).contains("원인: 시세 조회 실패");
        assertNoRawText(r);
    }

    @Test
    @DisplayName("⑥ 장부 내부 예외는 항목 건너뜀으로 삼키지 않는다 — 고정 '시세 처리 실패' 로 남고 뒤 코인은 호가 경로")
    void internalBookExceptionIsNotSwallowedAsItemSkip() throws Exception {
        MarkPriceBook spy = Mockito.spy(new MarkPriceBook());
        setUpWith(spy);
        Mockito.doThrow(new IllegalStateException("내부 " + FORGED)).when(spy)
                .observe(Mockito.eq(B), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        stubTicker(badItem("CONTROL"));
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row("장부내부예외", r));

        assertThat(r.byTrade()).as("내부 예외 뒤로는 진행하지 않는다(종전 배치 경로)").containsExactly(A);
        assertThat(r.unobserved()).containsExactly(B, C);
        assertThat(r.error()).startsWith("시세 처리 실패; ");
        assertThat(warns()).contains("[시세 보강] 시세 처리 실패 (종류=IllegalStateException)");
        assertThat(tgCount()).isEqualTo(1);
        assertNoRawText(r);
    }

    @Test
    @DisplayName("⑦ 경보 전송 실패 — 원문 없는 고정 WARN, 실패 표시는 남는다(성공으로 바꾸지 않음)")
    void alertSendFailureTextIsNotPropagated() throws Exception {
        Mockito.doThrow(new RuntimeException("telegram 429 " + FORGED)).when(tg).sendCustomNotification(Mockito.anyString());
        stubTicker(badItem("PRICE_FORGED"));
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");

        HeldMarkRefresher.Result r = refresher.refreshNow(now);
        System.out.println(row("경보전송실패", r));

        assertThat(r.unobserved()).containsExactly(B);
        assertThat(refresher.failingSince()).isNotNull();
        assertThat(warns()).contains("[시세 보강] 경보 전송 실패 (종류=RuntimeException)");
        assertNoRawText(r);
    }

    @Test
    @DisplayName("⑧ 실패 시작 → 같은 사유 지속 → 사유 변경 → 회복 — 경보 1회·ERROR 1·변경 WARN·회복 WARN, 원문 없음")
    void failurePersistRecoverCycle() throws Exception {
        stubTicker(badItem("PRICE_FORGED"));
        for (String c : List.of(A, B, C)) stubBook(c, "FAIL");
        HeldMarkRefresher.Result r1 = refresher.refreshNow(now);
        assertThat(r1.unobserved()).containsExactly(B);
        assertThat(tgCount()).isEqualTo(1);

        HeldMarkRefresher.Result r2 = refresher.refreshNow(now);
        assertThat(tgCount()).as("지속 — 경보 다시 없음").isEqualTo(1);

        stubBook(B, "SHALLOW");
        HeldMarkRefresher.Result r3 = refresher.refreshNow(now);
        assertThat(r3.unobserved()).containsExactly(B);
        assertThat(warns()).anyMatch(w -> w.startsWith("[시세 보강] 관측 가격 없음 지속 — 사유 변경 [" + B + "]"));
        assertThat(tgCount()).isEqualTo(1);

        stubBook(B, "DEEP");
        HeldMarkRefresher.Result r4 = refresher.refreshNow(now);
        System.out.println(row("시작→지속→변경→회복", r4));
        assertThat(r4.unobserved()).isEmpty();
        assertThat(refresher.failingSince()).isNull();
        assertThat(warns()).anyMatch(w -> w.startsWith("[시세 보강] 회복 — 보유 코인 관측 가격 공급 재개 (종전: "));
        assertThat(lines().stream().filter(l -> l.startsWith("ERROR ")).count()).isEqualTo(1);
        assertThat(tgCount()).isEqualTo(1);
        for (HeldMarkRefresher.Result r : List.of(r1, r2, r3, r4)) assertNoRawText(r);
    }

    @Test
    @DisplayName("⑨ 회차 오류(보유 조회 예외) — 고정 ERROR, 예외 원문 없음")
    void roundErrorTextIsNotPropagated() {
        jdbc.failWith = new IllegalStateException("DB " + FORGED);
        refresher.refresh();
        System.out.println("N20계약행|회차오류|로그=" + oneLine(String.join(" ⏎ ", lines())));
        assertThat(lines()).containsExactly("ERROR [시세 보강] 회차 오류 (종류=IllegalStateException)");
        assertNoRawText(null);
    }
}
