package com.cryptoautotrader.api.news;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N11 재현(2026-11-04) — 운영 ea44900의 공개 경로(collectAll)와 로그·last_fetched_at만 본다.
 * 외부 접속 없이 127.0.0.1 소켓 대역({@link LocalHttpStub})으로
 * ① 요청 뒤 서버 RST(운영 로그의 "Connection reset" 과 같은 연결 강제 종료 — 예외 문구는 OS 마다 다름) ② 정상 빈 응답을
 * 실제 출처 → 상위 수집기에 넣는다.
 *
 * <p>기대(보완 계약): 실패한 수집에는 "수집 완료"가 찍히지 않고, 정상 빈 응답에는 "수집 완료 … 전체=0건"이 찍힌다.
 * 두 경우 모두 last_fetched_at 은 시도 시각으로 갱신된다(재시도 주기 보존).
 * 운영 코드(ea44900)에서는 실패에도 "수집 완료"가 찍혀 이 시험이 실패한다 — 그것이 재현 결과다.
 * 이 파일은 운영 기점과 후보에서 바이트 그대로 함께 돈다(새 API를 쓰지 않는다).
 */
class NewsFetchGapReproTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger aggLogger;
    private Level savedLevel;
    private LocalHttpStub stub;

    private NewsSourceConfigRepository configRepo;
    private NewsItemCacheRepository cacheRepo;
    private NewsAggregatorService service;

    @BeforeEach
    void setUp() {
        aggLogger = (Logger) LoggerFactory.getLogger(NewsAggregatorService.class);
        savedLevel = aggLogger.getLevel();
        aggLogger.setLevel(Level.DEBUG);
        logs.start();
        aggLogger.addAppender(logs);

        ObjectMapper om = new ObjectMapper();
        NewsSourceRegistry registry = new NewsSourceRegistry(List.of(
                new RssNewsSource(), new UpbitNoticeSource(om), new CryptoPanicSource(om)));
        configRepo = mock(NewsSourceConfigRepository.class);
        cacheRepo = mock(NewsItemCacheRepository.class);
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new NewsAggregatorService(configRepo, cacheRepo, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        aggLogger.detachAppender(logs);
        aggLogger.setLevel(savedLevel);
        if (stub != null) stub.close();
    }

    private static String emptyBody(String type) {
        return switch (type) {
            case "RSS" -> "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title></channel></rss>";
            case "UPBIT_NOTICE" -> "{\"success\":true,\"data\":{\"notices\":[]}}";
            case "CRYPTOPANIC" -> "{\"results\":[]}";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private NewsSourceConfigEntity config(String sourceId, String type, String url) {
        return NewsSourceConfigEntity.builder()
                .sourceId(sourceId).sourceType(type).url(url)
                .enabled(true).fetchIntervalMin(30).category("CRYPTO")
                .lastFetchedAt(null)
                .build();
    }

    private List<String> aggregatorLines(String sourceId) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("sourceId=" + sourceId + " ") || m.endsWith("sourceId=" + sourceId))
                .toList();
    }

    @ParameterizedTest(name = "{0}: Connection reset 은 수집 완료가 아니고, 정상 빈 응답은 수집 완료 0건")
    @ValueSource(strings = {"RSS", "UPBIT_NOTICE", "CRYPTOPANIC"})
    @DisplayName("재현 — 실패와 정상 0건이 상위 수집기 기록에서 구분되는가")
    void resetVersusEmpty(String type) throws Exception {
        stub = new LocalHttpStub();
        stub.route("/reset", LocalHttpStub.Reply.reset());
        stub.route("/empty", LocalHttpStub.Reply.ok(emptyBody(type)));

        NewsSourceConfigEntity failing = config("stub_reset", type, stub.url("/reset"));
        NewsSourceConfigEntity empty = config("stub_empty", type, stub.url("/empty"));
        when(configRepo.findAllByEnabledTrue()).thenReturn(List.of(failing, empty));

        Instant before = Instant.now();
        service.collectAll();

        List<String> failLines = aggregatorLines("stub_reset");
        List<String> emptyLines = aggregatorLines("stub_empty");
        System.out.println("[repro " + type + "] reset 대역 요청 " + stub.hits("/reset") + "회 → 수집기 기록 " + failLines);
        System.out.println("[repro " + type + "] empty 대역 요청 " + stub.hits("/empty") + "회 → 수집기 기록 " + emptyLines);

        // 두 출처 모두 실제로 요청이 갔다(다음 출처 진행 포함)
        assertThat(stub.hits("/reset")).isGreaterThanOrEqualTo(1);
        assertThat(stub.hits("/empty")).isEqualTo(1);
        // 시도 시각 기준 갱신은 둘 다 유지
        assertThat(failing.getLastFetchedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(empty.getLastFetchedAt()).isNotNull().isAfterOrEqualTo(before);
        // 정상 빈 응답 = 수집 완료 0건
        assertThat(emptyLines).anyMatch(m -> m.startsWith("[NewsAggregator] 수집 완료") && m.contains("전체=0건"));
        // 실패 = 수집 완료가 아니다 (운영 ea44900 에서는 여기서 실패한다)
        assertThat(failLines).noneMatch(m -> m.startsWith("[NewsAggregator] 수집 완료"));
    }
}
