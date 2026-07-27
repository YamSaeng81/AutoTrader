package com.cryptoautotrader.api.news.source;

import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.NewsItem;
import com.cryptoautotrader.api.news.NewsSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 업비트 공지 수집 소스 (source_type = "UPBIT_NOTICE").
 *
 * <p>신규상장·유의종목·거래중단 등 업비트 트레이더에게 가장 중요한 이슈원.
 * 업비트 공지 API(비공식·웹에서 사용)에서 최근 공지를 가져온다.
 *
 * <p>응답 형태(예): {"success":true,"data":{"notices":[{"id":..,"title":..,"listed_at":..}], ...}}
 * 엔드포인트가 바뀔 수 있어 URL은 news_source_config.url로 설정 가능(코드 무수정 대응).
 * 실패 시 예외를 던지지 않고 빈 리스트 반환(수집기 계약).
 */
@Component
public class UpbitNoticeSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(UpbitNoticeSource.class);
    private static final String NOTICE_URL_PREFIX = "https://upbit.com/service_center/notice?id=";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public UpbitNoticeSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getSupportedType() {
        return "UPBIT_NOTICE";
    }

    @Override
    public List<NewsItem> fetch(NewsSourceConfigEntity config) {
        List<NewsItem> items = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    // 업비트 매니저 API는 UA 없으면 차단될 수 있어 브라우저 UA 지정
                    .header("User-Agent", "Mozilla/5.0 (compatible; CryptoAutoTrader/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[UpbitNoticeSource] HTTP {}: {}", response.statusCode(),
                        response.body() != null ? response.body().substring(0, Math.min(120, response.body().length())) : "");
                return items;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            // 응답 스키마 변형 대비: data.notices → data.list → 최상위 notices 순으로 탐색
            JsonNode notices = firstArray(data.path("notices"), data.path("list"), root.path("notices"));
            if (notices == null) {
                log.warn("[UpbitNoticeSource] 공지 배열을 찾지 못함 (스키마 변경 가능) — url={}", config.getUrl());
                return items;
            }

            for (JsonNode n : notices) {
                String id    = n.path("id").asText(null);
                String title = n.path("title").asText("");
                if (title.isBlank()) continue;
                Instant publishedAt = parseInstant(
                        firstText(n, "listed_at", "first_listed_at", "created_at", "published_at"));
                String url = id != null ? NOTICE_URL_PREFIX + id : null;

                items.add(NewsItem.builder()
                        .sourceId(config.getSourceId())
                        .externalId(id)
                        .title(title)
                        .url(url)
                        .category(config.getCategory())
                        .publishedAt(publishedAt)
                        .build());
            }
            log.debug("[UpbitNoticeSource] 수집 완료 — {}건", items.size());

        } catch (Exception e) {
            log.error("[UpbitNoticeSource] 수집 실패: {}", e.getMessage());
        }
        return items;
    }

    private static JsonNode firstArray(JsonNode... candidates) {
        for (JsonNode c : candidates) {
            if (c != null && c.isArray() && c.size() > 0) return c;
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Instant parseInstant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant(); // 예: 2024-06-01T10:00:00+09:00
        } catch (Exception ignore) {
            try {
                return Instant.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
