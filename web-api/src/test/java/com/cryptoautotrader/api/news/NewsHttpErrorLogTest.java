package com.cryptoautotrader.api.news;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.source.CoinGeckoTrendingSource;
import com.cryptoautotrader.api.news.source.CryptoPanicSource;
import com.cryptoautotrader.api.news.source.RssNewsSource;
import com.cryptoautotrader.api.news.source.UpbitNoticeSource;
import com.cryptoautotrader.api.repository.NewsItemCacheRepository;
import com.cryptoautotrader.api.repository.NewsSourceConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N12(2026-11-04) — HTTP 비200 응답 본문을 로그에 옮기지 않는다.
 *
 * <p>운영 b1d7664 에서 CryptoPanic 403 의 HTML 본문 전체가 여러 줄로 WARN 에 찍혔다(UpbitNotice 는 앞 120자).
 * 외부 접속 없이 127.0.0.1 대역({@link LocalHttpStub})이 다중 행 HTML·가짜 비밀 문자열·URL 이 든 403/503 을 돌려준다.
 * 실패 출처 다음에 정상 빈 응답 출처를 두어 다음 출처 진행과 정상 경로를 함께 본다.
 *
 * <p>기대: 뉴스 패키지의 어떤 로그에도 본문 값이 없다. CryptoPanic·UpbitNotice 의 출처 WARN 은
 * {@code [출처] HTTP n — sourceId=…} 한 줄이다. 상위 결과는 FAILED·HTTP_n·받은 0·신규 0 그대로,
 * 다음 출처는 수집 완료, 시도 시각 갱신, ERROR 없음.
 * RSS·CoinGecko 는 이미 본문을 쓰지 않으므로 같은 대역에서 본문 부재만 대조한다(코드 무변경).
 * b1d7664 에서는 CryptoPanic·UpbitNotice 사례가 실패한다 — 그것이 재현 결과다.
 */
class NewsHttpErrorLogTest {

    private static final String SECRET = "FAKE-SECRET-N12-7f3a9c";
    private static final String API_KEY = "FAKE-APIKEY-N12-0000";
    /** 둘째 줄 앞부분에 비밀 값을 두어 UpbitNotice 의 앞 120자 자르기에도 걸리게 한다. */
    private static final String BODY = "<!DOCTYPE html>\n"
            + "<p>" + SECRET + " https://blocked.invalid/cdn-cgi?token=" + SECRET + "</p>\r\n"
            + "<html><head><title>Just a moment...</title></head>\n"
            + "<body>Access denied</body></html>\n";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger newsLogger;
    private Level savedLevel;
    private LocalHttpStub stub;

    private NewsSourceConfigRepository configRepo;
    private NewsAggregatorService service;

    @BeforeEach
    void setUp() {
        // 출처(…news.source)와 상위 수집기(…news) 로그를 모두 받는다
        newsLogger = (Logger) LoggerFactory.getLogger("com.cryptoautotrader.api.news");
        savedLevel = newsLogger.getLevel();
        newsLogger.setLevel(Level.DEBUG);
        logs.start();
        newsLogger.addAppender(logs);

        ObjectMapper om = new ObjectMapper();
        NewsSourceRegistry registry = new NewsSourceRegistry(List.of(
                new RssNewsSource(), new UpbitNoticeSource(om), new CryptoPanicSource(om),
                new CoinGeckoTrendingSource(om)));
        configRepo = mock(NewsSourceConfigRepository.class);
        NewsItemCacheRepository cacheRepo = mock(NewsItemCacheRepository.class);
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new NewsAggregatorService(configRepo, cacheRepo, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        newsLogger.detachAppender(logs);
        newsLogger.setLevel(savedLevel);
        if (stub != null) stub.close();
    }

    private static String emptyBody(String type) {
        return switch (type) {
            case "RSS" -> "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title></channel></rss>";
            case "UPBIT_NOTICE" -> "{\"success\":true,\"data\":{\"notices\":[]}}";
            case "CRYPTOPANIC" -> "{\"results\":[]}";
            case "COINGECKO" -> "{\"coins\":[]}";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static String sourceTag(String type) {
        return switch (type) {
            case "RSS" -> "[RssNewsSource]";
            case "UPBIT_NOTICE" -> "[UpbitNoticeSource]";
            case "CRYPTOPANIC" -> "[CryptoPanicSource]";
            case "COINGECKO" -> "[CoinGeckoTrendingSource]";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private NewsSourceConfigEntity config(String sourceId, String type, String url) {
        return NewsSourceConfigEntity.builder()
                .sourceId(sourceId).sourceType(type).url(url)
                .apiKey("CRYPTOPANIC".equals(type) ? API_KEY : null)
                .enabled(true).fetchIntervalMin(30).category("CRYPTO")
                .lastFetchedAt(null)
                .build();
    }

    @ParameterizedTest(name = "{0} HTTP {1}: 본문 없이 출처·상태 코드만, 결과·다음 출처 그대로")
    @CsvSource({
            "CRYPTOPANIC, 403", "CRYPTOPANIC, 503",
            "UPBIT_NOTICE, 403", "UPBIT_NOTICE, 503",
            "RSS, 403", "RSS, 503",
            "COINGECKO, 403", "COINGECKO, 503"})
    @DisplayName("N12 — HTTP 비200 응답 본문이 로그에 남지 않는가")
    void httpErrorBodyNotLogged(String type, int status) throws Exception {
        stub = new LocalHttpStub();
        stub.route("/fail", LocalHttpStub.Reply.status(status, BODY));
        stub.route("/ok", LocalHttpStub.Reply.ok(emptyBody(type)));

        NewsSourceConfigEntity failing = config("stub_fail", type, stub.url("/fail"));
        NewsSourceConfigEntity next = config("stub_ok", type, stub.url("/ok"));
        when(configRepo.findAllByEnabledTrue()).thenReturn(List.of(failing, next));

        Instant before = Instant.now();
        service.collectAll();

        List<ILoggingEvent> events = List.copyOf(logs.list);
        List<String> all = events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        List<String> sourceWarns = events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(sourceTag(type)))
                .toList();
        System.out.println("[n12 " + type + " " + status + "] 출처 WARN " + sourceWarns.size() + "개:");
        sourceWarns.forEach(m -> System.out.println("  >>" + m.replace("\r", "\\r").replace("\n", "\\n") + "<<"));
        all.stream().filter(m -> m.startsWith("[NewsAggregator] 수집 "))
                .forEach(m -> System.out.println("  " + m));

        // 두 출처 모두 실제로 요청됐다
        assertThat(stub.hits("/fail")).isEqualTo(1);
        assertThat(stub.hits("/ok")).isEqualTo(1);

        // 본문의 어떤 값도 뉴스 패키지 로그에 없다
        assertThat(all).noneMatch(m -> m.contains(SECRET));
        assertThat(all).noneMatch(m -> m.contains(API_KEY));
        assertThat(all).noneMatch(m -> m.contains("auth_token"));
        assertThat(all).noneMatch(m -> m.contains("<!DOCTYPE") || m.contains("<html") || m.contains("<p>"));
        assertThat(all).noneMatch(m -> m.contains("blocked.invalid") || m.contains("Just a moment") || m.contains("Access denied"));
        assertThat(all).noneMatch(m -> m.contains("\n") || m.contains("\r"));

        // 출처 WARN 은 한 줄
        assertThat(sourceWarns).hasSize(1);
        if ("CRYPTOPANIC".equals(type) || "UPBIT_NOTICE".equals(type)) {
            assertThat(sourceWarns.get(0)).isEqualTo(sourceTag(type) + " HTTP " + status + " — sourceId=stub_fail");
        }

        // 상위 결과·다음 출처·시도 시각은 그대로
        assertThat(all).anyMatch(m -> m.startsWith("[NewsAggregator] 수집 미완료 — sourceId=stub_fail 결과=FAILED 종류=HTTP_" + status + " ")
                && m.endsWith("받은=0건 신규=0건"));
        assertThat(all).noneMatch(m -> m.startsWith("[NewsAggregator] 수집 완료 — sourceId=stub_fail "));
        assertThat(all).anyMatch(m -> m.equals("[NewsAggregator] 수집 완료 — sourceId=stub_ok 전체=0건 신규=0건"));
        assertThat(failing.getLastFetchedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(next.getLastFetchedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(events).noneMatch(e -> e.getLevel() == Level.ERROR);
    }
}
