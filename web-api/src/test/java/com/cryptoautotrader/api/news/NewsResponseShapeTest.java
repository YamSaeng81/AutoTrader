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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N11 응답 판정 경계 (총괄 98bbeda 검토 보완, 2026-11-04). 외부 접속 없음 — 127.0.0.1 대역({@link LocalHttpStub}).
 *
 * <ul>
 *   <li>업비트: 본문 {@code success} 가 false 면 항목과 관계없이 FAILED, boolean 이 아니면 INVALID_RESPONSE.
 *       필드가 없으면 기존처럼 배열(data.notices → data.list → notices)로 판정한다.</li>
 *   <li>RSS: 루트가 {@code rss}(0.9x/2.0) 또는 RDF 네임스페이스의 {@code RDF}(RSS 1.0)이고 그 바로 아래
 *       {@code channel} 이 있어야 한다. 이름만 같은 {@code channel} 은 거부한다. Atom 등 새 형식은 지원하지 않는다.</li>
 * </ul>
 * 상위 수집기를 거쳐 기록·저장까지 본다. 실패 종류는 상수 대신 문자열로 적어 보완 전 후보에서도 컴파일된다.
 */
class NewsResponseShapeTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> watched = new ArrayList<>();
    private final List<Level> savedLevels = new ArrayList<>();
    private LocalHttpStub stub;
    private NewsSourceConfigRepository configRepo;
    private NewsItemCacheRepository cacheRepo;
    private NewsAggregatorService service;

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        for (Class<?> c : List.of(NewsAggregatorService.class, RssNewsSource.class, UpbitNoticeSource.class)) {
            Logger l = (Logger) LoggerFactory.getLogger(c);
            savedLevels.add(l.getLevel());
            l.setLevel(Level.DEBUG);
            l.addAppender(logs);
            watched.add(l);
        }
        ObjectMapper om = new ObjectMapper();
        NewsSourceRegistry registry = new NewsSourceRegistry(List.of(new RssNewsSource(), new UpbitNoticeSource(om),
                new CryptoPanicSource(om), new CoinGeckoTrendingSource(om)));
        configRepo = mock(NewsSourceConfigRepository.class);
        cacheRepo = mock(NewsItemCacheRepository.class);
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepo.existsBySourceIdAndExternalId(anyString(), anyString())).thenReturn(false);
        service = new NewsAggregatorService(configRepo, cacheRepo, registry);
        stub = new LocalHttpStub();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = 0; i < watched.size(); i++) {
            watched.get(i).detachAppender(logs);
            watched.get(i).setLevel(savedLevels.get(i));
        }
        stub.close();
    }

    private NewsFetchResult run(String type, String payload) {
        stub.route("/s", LocalHttpStub.Reply.ok(payload));
        NewsSourceConfigEntity cfg = NewsSourceConfigEntity.builder()
                .sourceId("b").sourceType(type).url(stub.url("/s"))
                .enabled(true).fetchIntervalMin(30).category("CRYPTO").build();
        when(configRepo.findBySourceId("b")).thenReturn(Optional.of(cfg));
        NewsFetchResult r = service.collectFromSourceManual("b");
        assertThat(cfg.getLastFetchedAt()).as("시도 기준 last_fetched_at").isNotNull();
        assertThat(lines(Level.ERROR)).as("출처·상위 모두 ERROR 를 더하지 않음(점검 분류 무변경)").isEmpty();
        return r;
    }

    private List<String> lines(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertComplete(NewsFetchResult r, int items) {
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.SUCCESS);
        assertThat(r.items()).hasSize(items);
        assertThat(lines(Level.INFO)).contains("[NewsAggregator] 수집 완료 — sourceId=b 전체=" + items + "건 신규=" + items + "건");
        assertThat(lines(Level.WARN)).noneMatch(m -> m.startsWith("[NewsAggregator] 수집 미완료"));
    }

    private void assertRejected(NewsFetchResult r, String kind) {
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.items()).isEmpty();
        assertThat(r.failureKind()).isEqualTo(kind);
        assertThat(lines(Level.INFO)).noneMatch(m -> m.startsWith("[NewsAggregator] 수집 완료"));
        assertThat(lines(Level.WARN)).anyMatch(m -> m.startsWith("[NewsAggregator] 수집 미완료 — sourceId=b 결과=FAILED 종류=" + kind + " "));
        verify(cacheRepo, never()).save(any());
    }

    // ── 업비트 success 필드 ──────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] 업비트 {0} → FAILED {2}, 항목 저장 없음")
    @CsvSource(delimiter = '|', value = {
            "success:false 빈 배열      | {\"success\":false,\"data\":{\"notices\":[]}}                                   | SOURCE_REPORTED_FAILURE",
            "success:false 항목 1개     | {\"success\":false,\"data\":{\"notices\":[{\"id\":1,\"title\":\"untrusted\"}]}} | SOURCE_REPORTED_FAILURE",
            "success:false data.list    | {\"success\":false,\"data\":{\"list\":[{\"id\":2,\"title\":\"untrusted\"}]}}    | SOURCE_REPORTED_FAILURE",
            "success:false 배열 없음    | {\"success\":false,\"error\":{\"name\":\"x\"}}                                   | SOURCE_REPORTED_FAILURE",
            "success 문자열 \"true\"    | {\"success\":\"true\",\"data\":{\"notices\":[{\"id\":3,\"title\":\"A\"}]}}       | INVALID_RESPONSE",
            "success 숫자 1             | {\"success\":1,\"data\":{\"notices\":[{\"id\":4,\"title\":\"A\"}]}}               | INVALID_RESPONSE",
            "success null               | {\"success\":null,\"data\":{\"notices\":[]}}                                     | INVALID_RESPONSE",
            "success 문자열 \"false\"   | {\"success\":\"false\",\"data\":{\"notices\":[]}}                                | INVALID_RESPONSE"})
    void upbitExplicitOrInvalidSuccessFlag(String name, String payload, String kind) {
        assertRejected(run("UPBIT_NOTICE", payload), kind);
    }

    @ParameterizedTest(name = "[{index}] 업비트 {0} → SUCCESS {2}건 (기존 호환)")
    @CsvSource(delimiter = '|', value = {
            "success:true 빈 배열        | {\"success\":true,\"data\":{\"notices\":[]}}                         | 0",
            "success:true 1건            | {\"success\":true,\"data\":{\"notices\":[{\"id\":5,\"title\":\"A\"}]}} | 1",
            "success 없음 data.notices   | {\"data\":{\"notices\":[{\"id\":6,\"title\":\"A\"}]}}                 | 1",
            "success 없음 data.list      | {\"data\":{\"list\":[{\"id\":7,\"title\":\"A\"}]}}                    | 1",
            "success 없음 최상위 notices | {\"notices\":[{\"id\":8,\"title\":\"A\"}]}                            | 1",
            "success 없음 빈 배열        | {\"data\":{\"notices\":[]}}                                           | 0"})
    void upbitCompatibleShapes(String name, String payload, int items) {
        assertComplete(run("UPBIT_NOTICE", payload), items);
    }

    // ── RSS 루트·구조 ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] RSS {0} → FAILED INVALID_RESPONSE")
    @CsvSource(delimiter = '|', value = {
            "html 아래 channel          | <html><body><channel/></body></html>",
            "html 아래 channel·item     | <html><body><channel><item><title>A</title><guid>g</guid></item></channel></body></html>",
            "rss 손자 channel           | <rss version=\"2.0\"><wrap><channel/></wrap></rss>",
            "rss 아래 channel 없음      | <rss version=\"2.0\"><item><title>A</title><guid>g</guid></item></rss>",
            "Atom(미지원)               | <feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title><entry><title>A</title></entry></feed>",
            "RDF 네임스페이스 아님      | <x:RDF xmlns:x=\"http://example.com/\"><channel/></x:RDF>",
            "접두어 붙은 rss            | <a:rss xmlns:a=\"http://example.com/\"><channel/></a:rss>"})
    void rssNonFeedRejected(String name, String payload) {
        assertRejected(run("RSS", payload), "INVALID_RESPONSE");
    }

    @ParameterizedTest(name = "[{index}] RSS {0} → SUCCESS {2}건 (기존 형식 유지)")
    @CsvSource(delimiter = '|', value = {
            "2.0 빈 channel              | <?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title></channel></rss> | 0",
            "2.0 1건                     | <rss version=\"2.0\"><channel><item><title>a</title><guid>g1</guid></item></channel></rss> | 1",
            "0.91 1건                    | <rss version=\"0.91\"><channel><title>t</title><item><title>a</title><link>http://x/a</link></item></channel></rss> | 1",
            "2.0 미선언 dc 접두어 1건    | <rss version=\"2.0\"><channel><item><title>a</title><guid>g2</guid><dc:creator>c</dc:creator></item></channel></rss> | 1",
            "1.0(RDF) 빈 channel         | <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\"><channel rdf:about=\"c\"><title>t</title></channel></rdf:RDF> | 0",
            "1.0(RDF) 1건                | <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\"><channel rdf:about=\"c\"><title>t</title></channel><item rdf:about=\"http://x/a\"><title>a</title><link>http://x/a</link></item></rdf:RDF> | 1"})
    void rssSupportedFeeds(String name, String payload, int items) {
        assertComplete(run("RSS", payload), items);
    }
}
