package com.cryptoautotrader.api.news;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.api.controller.NewsSourceController;
import com.cryptoautotrader.api.dto.ApiResponse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N11 결과 계약 국소 시험 (2026-11-04). 외부 접속 없음 — 실제 출처 4종을 127.0.0.1 대역({@link LocalHttpStub})에 붙인다.
 * 저장소는 대역(mock). DB·스케줄러·Spring 컨텍스트는 띄우지 않는다.
 */
class NewsFetchResultContractTest {

    private static final String SECRET = "N11SECRETTOKEN";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> watched = new ArrayList<>();
    private final List<Level> savedLevels = new ArrayList<>();
    private LocalHttpStub stub;

    private NewsSourceConfigRepository configRepo;
    private NewsItemCacheRepository cacheRepo;
    private NewsSourceRegistry registry;
    private NewsAggregatorService service;

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        for (Class<?> c : List.of(NewsAggregatorService.class, RssNewsSource.class, UpbitNoticeSource.class,
                CryptoPanicSource.class, CoinGeckoTrendingSource.class)) {
            Logger l = (Logger) LoggerFactory.getLogger(c);
            savedLevels.add(l.getLevel());
            l.setLevel(Level.DEBUG);
            l.addAppender(logs);
            watched.add(l);
        }
        ObjectMapper om = new ObjectMapper();
        registry = new NewsSourceRegistry(List.of(new RssNewsSource(), new UpbitNoticeSource(om),
                new CryptoPanicSource(om), new CoinGeckoTrendingSource(om)));
        configRepo = mock(NewsSourceConfigRepository.class);
        cacheRepo = mock(NewsItemCacheRepository.class);
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
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

    // ── 대역 본문 ────────────────────────────────────────────────────────────

    private static String body(String type, String kind) {
        return switch (type + ":" + kind) {
            case "RSS:empty" -> "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title></channel></rss>";
            case "RSS:one" -> "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><item><title>A</title>"
                    + "<link>http://x/a</link><guid>g1</guid><pubDate>Mon, 02 Nov 2026 10:00:00 +0000</pubDate></item></channel></rss>";
            case "RSS:invalid" -> "<?xml version=\"1.0\"?><html><body>maintenance</body></html>";
            case "RSS:malformed" -> "<rss><channel>";
            case "UPBIT_NOTICE:empty" -> "{\"success\":true,\"data\":{\"notices\":[]}}";
            case "UPBIT_NOTICE:one" -> "{\"success\":true,\"data\":{\"notices\":[{\"id\":101,\"title\":\"공지 A\",\"listed_at\":\"2026-11-02T10:00:00+09:00\"}]}}";
            case "UPBIT_NOTICE:invalid" -> "{\"success\":true,\"data\":{}}";
            case "UPBIT_NOTICE:malformed" -> "{\"data\":";
            case "CRYPTOPANIC:empty" -> "{\"results\":[]}";
            case "CRYPTOPANIC:one" -> "{\"results\":[{\"id\":5,\"title\":\"News A\",\"url\":\"http://x/5\",\"published_at\":\"2026-11-02T01:00:00Z\"}]}";
            case "CRYPTOPANIC:invalid" -> "{\"detail\":\"rate limited\"}";
            case "CRYPTOPANIC:malformed" -> "{\"results\":";
            case "COINGECKO:empty" -> "{\"coins\":[]}";
            case "COINGECKO:one" -> "{\"coins\":[{\"item\":{\"id\":\"bitcoin\",\"name\":\"Bitcoin\",\"symbol\":\"btc\",\"market_cap_rank\":1}}]}";
            case "COINGECKO:invalid" -> "{\"status\":{\"error_code\":429}}";
            case "COINGECKO:malformed" -> "{\"coins\":";
            default -> throw new IllegalArgumentException(type + ":" + kind);
        };
    }

    private NewsSourceConfigEntity config(String sourceId, String type, String path) {
        return NewsSourceConfigEntity.builder()
                .sourceId(sourceId).sourceType(type).url(stub.url(path))
                .enabled(true).fetchIntervalMin(30).category("CRYPTO")
                .apiKey("CRYPTOPANIC".equals(type) ? SECRET : null)
                .build();
    }

    private NewsFetchResult manual(NewsSourceConfigEntity cfg) {
        when(configRepo.findBySourceId(cfg.getSourceId())).thenReturn(Optional.of(cfg));
        return service.collectFromSourceManual(cfg.getSourceId());
    }

    private List<String> lines(Level level, String loggerSuffix) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == level && e.getLoggerName().endsWith(loggerSuffix))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> completeLines(String sourceId) {
        return lines(Level.INFO, "NewsAggregatorService").stream()
                .filter(m -> m.startsWith("[NewsAggregator] 수집 완료 — sourceId=" + sourceId + " ")).toList();
    }

    private List<String> incompleteLines(String sourceId) {
        return lines(Level.WARN, "NewsAggregatorService").stream()
                .filter(m -> m.startsWith("[NewsAggregator] 수집 미완료 — sourceId=" + sourceId + " ")).toList();
    }

    private List<String> aggregatorErrors() {
        return lines(Level.ERROR, "NewsAggregatorService");
    }

    // ── 1. 성공: 0건·1건·중복 ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}: 정상 빈 응답 = SUCCESS 0건, 수집 완료 전체=0건")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void successZero(String type) {
        stub.route("/s", LocalHttpStub.Reply.ok(body(type, "empty")));
        NewsSourceConfigEntity cfg = config("s0", type, "/s");
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.SUCCESS);
        assertThat(r.items()).isEmpty();
        assertThat(r.failureKind()).isNull();
        assertThat(completeLines("s0")).containsExactly("[NewsAggregator] 수집 완료 — sourceId=s0 전체=0건 신규=0건");
        assertThat(incompleteLines("s0")).isEmpty();
        assertThat(cfg.getLastFetchedAt()).isNotNull();
        verify(cacheRepo, never()).save(any());
    }

    @ParameterizedTest(name = "{0}: 1건 = SUCCESS, 신규 1건 저장")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void successOne(String type) {
        stub.route("/s", LocalHttpStub.Reply.ok(body(type, "one")));
        NewsSourceConfigEntity cfg = config("s1", type, "/s");
        when(cacheRepo.existsBySourceIdAndExternalId(anyString(), anyString())).thenReturn(false);
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.SUCCESS);
        assertThat(r.items()).hasSize(1);
        assertThat(completeLines("s1")).containsExactly("[NewsAggregator] 수집 완료 — sourceId=s1 전체=1건 신규=1건");
        verify(cacheRepo, times(1)).save(any());
    }

    @ParameterizedTest(name = "{0}: 이미 있는 뉴스뿐 = SUCCESS, 신규 0건(실패 아님)")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void successDuplicateOnly(String type) {
        stub.route("/s", LocalHttpStub.Reply.ok(body(type, "one")));
        NewsSourceConfigEntity cfg = config("sd", type, "/s");
        when(cacheRepo.existsBySourceIdAndExternalId(anyString(), anyString())).thenReturn(true);
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.SUCCESS);
        assertThat(completeLines("sd")).containsExactly("[NewsAggregator] 수집 완료 — sourceId=sd 전체=1건 신규=0건");
        assertThat(incompleteLines("sd")).isEmpty();
        verify(cacheRepo, never()).save(any());
    }

    // ── 2. 실패: 연결 강제 종료·HTTP 비200·잘못된 응답 ─────────────────────────

    @ParameterizedTest(name = "{0}: 연결 강제 종료 = FAILED(예외 종류), 출처 ERROR 원문 형식 유지, 수집 완료 없음")
    @CsvSource({
            "RSS,          RssNewsSource,          '[RssNewsSource] 수집 실패 — sourceId=sr: '",
            "UPBIT_NOTICE, UpbitNoticeSource,      '[UpbitNoticeSource] 수집 실패: '",
            "CRYPTOPANIC,  CryptoPanicSource,      '[CryptoPanicSource] 수집 실패: '",
            "COINGECKO,    CoinGeckoTrendingSource, '[CoinGeckoTrendingSource] 수집 실패: '"})
    void connectionDropped(String type, String sourceLogger, String errorPrefix) {
        stub.route("/s", LocalHttpStub.Reply.reset());
        NewsSourceConfigEntity cfg = config("sr", type, "/s");
        Instant before = Instant.now();
        NewsFetchResult r = manual(cfg);

        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.items()).isEmpty();
        assertThat(r.failureKind()).isNotBlank().doesNotContain(" ").doesNotContain("127.0.0.1");
        assertThat(r.decidedAt()).isAfterOrEqualTo(before);
        // 출처 자신의 ERROR 한 줄 — 기존 문구 형식 그대로 (점검 분류 대상)
        assertThat(lines(Level.ERROR, sourceLogger)).singleElement().asString().startsWith(errorPrefix);
        // 상위 수집기: 수집 완료 없음, 미완료 WARN 1줄, 상위에서 ERROR 를 더하지 않음
        assertThat(completeLines("sr")).isEmpty();
        assertThat(incompleteLines("sr")).singleElement().asString()
                .contains("결과=FAILED").contains("종류=" + r.failureKind()).contains("받은=0건 신규=0건");
        assertThat(aggregatorErrors()).isEmpty();
        // 시도 기준 last_fetched_at 유지
        assertThat(cfg.getLastFetchedAt()).isAfterOrEqualTo(before);
    }

    @ParameterizedTest(name = "{0}: HTTP 503 = FAILED HTTP_503")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void httpNon200(String type) {
        stub.route("/s", LocalHttpStub.Reply.status(503, "busy"));
        NewsSourceConfigEntity cfg = config("sh", type, "/s");
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.failureKind()).isEqualTo("HTTP_503");
        assertThat(completeLines("sh")).isEmpty();
        assertThat(incompleteLines("sh")).singleElement().asString().contains("종류=HTTP_503");
        assertThat(cfg.getLastFetchedAt()).isNotNull();
    }

    @ParameterizedTest(name = "{0}: 필요한 배열/요소가 없는 응답 = FAILED INVALID_RESPONSE (정상 빈 배열과 구분)")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void missingRequiredField(String type) {
        stub.route("/s", LocalHttpStub.Reply.ok(body(type, "invalid")));
        NewsSourceConfigEntity cfg = config("si", type, "/s");
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.failureKind()).isEqualTo(NewsFetchResult.INVALID_RESPONSE);
        assertThat(completeLines("si")).isEmpty();
    }

    @ParameterizedTest(name = "{0}: 깨진 본문 = FAILED(파싱 예외 종류)")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC", "COINGECKO"})
    void malformedBody(String type) {
        stub.route("/s", LocalHttpStub.Reply.ok(body(type, "malformed")));
        NewsSourceConfigEntity cfg = config("sm", type, "/s");
        NewsFetchResult r = manual(cfg);
        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.failureKind()).isNotBlank().isNotEqualTo(NewsFetchResult.INVALID_RESPONSE);
        assertThat(completeLines("sm")).isEmpty();
        assertThat(incompleteLines("sm")).hasSize(1);
    }

    // ── 3. 순회: 실패해도 다음 출처 진행, 시도 간격 유지 ──────────────────────

    @Test
    @DisplayName("collectAll: 앞 출처가 실패해도 다음 출처를 수집하고, 각자 결과대로 기록한다")
    void nextSourceProceedsAfterFailure() {
        stub.route("/bad", LocalHttpStub.Reply.reset());
        stub.route("/good", LocalHttpStub.Reply.ok(body("UPBIT_NOTICE", "one")));
        NewsSourceConfigEntity bad = config("bad", "RSS", "/bad");
        NewsSourceConfigEntity good = config("good", "UPBIT_NOTICE", "/good");
        when(configRepo.findAllByEnabledTrue()).thenReturn(List.of(bad, good));
        when(cacheRepo.existsBySourceIdAndExternalId(anyString(), anyString())).thenReturn(false);

        service.collectAll();

        assertThat(stub.hits("/good")).isEqualTo(1);
        assertThat(incompleteLines("bad")).hasSize(1);
        assertThat(completeLines("bad")).isEmpty();
        assertThat(completeLines("good")).containsExactly("[NewsAggregator] 수집 완료 — sourceId=good 전체=1건 신규=1건");
        verify(cacheRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("시도 간격: 실패 뒤에도 last_fetched_at 은 시도 시각 — 다음 주기 전에는 다시 요청하지 않고, 간격이 지나면 요청한다")
    void attemptIntervalPreservedAfterFailure() {
        stub.route("/bad", LocalHttpStub.Reply.status(500, ""));
        NewsSourceConfigEntity bad = config("iv", "CRYPTOPANIC", "/bad");
        when(configRepo.findAllByEnabledTrue()).thenReturn(List.of(bad));

        service.collectAll();               // 첫 시도(last_fetched_at 없음)
        assertThat(stub.hits("/bad")).isEqualTo(1);
        Instant attempted = bad.getLastFetchedAt();
        assertThat(attempted).isNotNull();

        service.collectAll();               // 같은 주기 안 — 실패였어도 즉시 재시도하지 않는다
        assertThat(stub.hits("/bad")).isEqualTo(1);
        assertThat(bad.getLastFetchedAt()).isEqualTo(attempted);

        bad.setLastFetchedAt(Instant.now().minus(30, ChronoUnit.MINUTES)); // 간격(30분) 경과
        service.collectAll();
        assertThat(stub.hits("/bad")).isEqualTo(2);
    }

    // ── 4. 부분 결과·계약 위반·지원하지 않는 타입 ──────────────────────────────

    @Test
    @DisplayName("PARTIAL: 받은 항목은 기존처럼 중복 확인 뒤 저장, 수집 완료로 기록하지 않음, 캐시 삭제 없음")
    void partialSavesItemsButIsNotComplete() {
        NewsSource partialSource = mock(NewsSource.class);
        NewsSourceRegistry reg = mock(NewsSourceRegistry.class);
        when(reg.get("MOCK")).thenReturn(partialSource);
        NewsAggregatorService svc = new NewsAggregatorService(configRepo, cacheRepo, reg);
        NewsSourceConfigEntity cfg = NewsSourceConfigEntity.builder().sourceId("pp").sourceType("MOCK").enabled(true).build();
        NewsItem it = NewsItem.builder().sourceId("pp").externalId("e1").title("t").build();
        when(partialSource.fetch(cfg)).thenReturn(
                NewsFetchResult.fromException(List.of(it), new java.net.SocketException("Connection reset")));
        when(configRepo.findBySourceId("pp")).thenReturn(Optional.of(cfg));
        when(cacheRepo.existsBySourceIdAndExternalId("pp", "e1")).thenReturn(false);

        NewsFetchResult r = svc.collectFromSourceManual("pp");

        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.PARTIAL);
        assertThat(r.failureKind()).isEqualTo("SocketException");
        verify(cacheRepo, times(1)).save(any());
        verify(cacheRepo, never()).deleteByFetchedAtBefore(any());
        assertThat(completeLines("pp")).isEmpty();
        assertThat(incompleteLines("pp")).singleElement().asString()
                .contains("결과=PARTIAL").contains("종류=SocketException").contains("받은=1건 신규=1건")
                .doesNotContain("Connection reset");
        assertThat(cfg.getLastFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("출처가 null 을 돌려주면(계약 위반) 성공으로 보지 않는다 — FAILED NULL_RESULT")
    void nullResultIsNotSuccess() {
        NewsSource broken = mock(NewsSource.class);
        NewsSourceRegistry reg = mock(NewsSourceRegistry.class);
        when(reg.get("MOCK")).thenReturn(broken);
        NewsAggregatorService svc = new NewsAggregatorService(configRepo, cacheRepo, reg);
        NewsSourceConfigEntity cfg = NewsSourceConfigEntity.builder().sourceId("nn").sourceType("MOCK").enabled(true).build();
        when(configRepo.findBySourceId("nn")).thenReturn(Optional.of(cfg));

        NewsFetchResult r = svc.collectFromSourceManual("nn");

        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.failureKind()).isEqualTo(NewsFetchResult.NULL_RESULT);
        assertThat(completeLines("nn")).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 타입: FAILED UNSUPPORTED_TYPE, last_fetched_at 은 기존처럼 갱신하지 않음")
    void unsupportedTypeKeepsOldBehaviour() {
        NewsSourceConfigEntity cfg = NewsSourceConfigEntity.builder().sourceId("uu").sourceType("NOPE").enabled(true).build();
        when(configRepo.findBySourceId("uu")).thenReturn(Optional.of(cfg));

        NewsFetchResult r = service.collectFromSourceManual("uu");

        assertThat(r.status()).isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(r.failureKind()).isEqualTo(NewsFetchResult.UNSUPPORTED_TYPE);
        assertThat(cfg.getLastFetchedAt()).isNull();
        verify(configRepo, never()).save(any());
    }

    // ── 5. 비밀값·결과 불변식·수동 조회 호출부 ─────────────────────────────────

    @Test
    @DisplayName("실패 기록(결과·상위 WARN)에 토큰·URL·예외 메시지가 들어가지 않는다")
    void failureRecordCarriesNoSecretOrUrl() {
        stub.route("/s", LocalHttpStub.Reply.reset());
        NewsSourceConfigEntity cfg = config("sx", "CRYPTOPANIC", "/s");
        NewsFetchResult r = manual(cfg);
        assertThat(r.failureKind()).doesNotContain(SECRET).doesNotContain("http").doesNotContain(":");
        assertThat(incompleteLines("sx")).singleElement().asString()
                .doesNotContain(SECRET).doesNotContain("127.0.0.1").doesNotContain("http");
    }

    @Test
    @DisplayName("결과 불변식: 성공에 실패 종류 금지, 실패·부분에 종류 필수, 항목 유무로 FAILED/PARTIAL 구분")
    void resultInvariants() {
        Instant t = Instant.now();
        NewsItem it = NewsItem.builder().sourceId("x").externalId("e").title("t").build();
        assertThatThrownBy(() -> new NewsFetchResult(NewsFetchResult.Status.SUCCESS, List.of(), "X", t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsFetchResult(NewsFetchResult.Status.FAILED, List.of(), null, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsFetchResult(NewsFetchResult.Status.FAILED, List.of(it), "X", t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewsFetchResult(NewsFetchResult.Status.PARTIAL, List.of(), "X", t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NewsFetchResult.fromException(List.of(), new java.io.IOException("Connection reset")).status())
                .isEqualTo(NewsFetchResult.Status.FAILED);
        assertThat(NewsFetchResult.success(null).items()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("수동 조회 API: 기존 sourceId·collected·titles 유지 + status·failureKind 추가")
    void manualFetchEndpointCarriesStatus() {
        NewsAggregatorService agg = mock(NewsAggregatorService.class);
        NewsSourceController controller = new NewsSourceController(configRepo, cacheRepo, agg, registry);

        when(agg.collectFromSourceManual("a")).thenReturn(NewsFetchResult.failed("HTTP_503"));
        Map<String, Object> failed = controller.fetchNow("a").getData();
        assertThat(failed).containsEntry("sourceId", "a").containsEntry("collected", 0)
                .containsEntry("status", "FAILED").containsEntry("failureKind", "HTTP_503");
        assertThat((List<String>) failed.get("titles")).isEmpty();

        NewsItem it = NewsItem.builder().sourceId("b").externalId("e").title("제목").build();
        when(agg.collectFromSourceManual("b")).thenReturn(NewsFetchResult.success(List.of(it)));
        ApiResponse<Map<String, Object>> ok = controller.fetchNow("b");
        assertThat(ok.getData()).containsEntry("collected", 1).containsEntry("status", "SUCCESS")
                .containsEntry("failureKind", null);
        assertThat((List<String>) ok.getData().get("titles")).containsExactly("제목");
    }
}
