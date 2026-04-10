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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * CryptoPanic API 뉴스 소스 (source_type = "API", source_id = "cryptopanic").
 *
 * <p>무료 플랜: auth_token 없이도 기본 사용 가능 (rate limit 있음).
 * api_key 설정 시 더 많은 요청 허용.
 *
 * <p>config_json 예: {"filter": "hot", "currencies": "BTC,ETH", "kind": "news"}
 */
@Component
public class CryptoPanicSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(CryptoPanicSource.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CryptoPanicSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getSupportedType() {
        return "CRYPTOPANIC";
    }

    @Override
    public List<NewsItem> fetch(NewsSourceConfigEntity config) {
        List<NewsItem> items = new ArrayList<>();
        try {
            String baseUrl = config.getUrl();
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?public=true");

            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                urlBuilder.append("&auth_token=").append(config.getApiKey());
            }

            // config_json 파싱하여 파라미터 추가
            if (config.getConfigJson() != null) {
                JsonNode cfg = objectMapper.readTree(config.getConfigJson());
                if (cfg.has("filter"))     urlBuilder.append("&filter=").append(cfg.get("filter").asText());
                if (cfg.has("currencies")) urlBuilder.append("&currencies=").append(cfg.get("currencies").asText());
                if (cfg.has("kind"))       urlBuilder.append("&kind=").append(cfg.get("kind").asText());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[CryptoPanicSource] HTTP {}: {}", response.statusCode(), response.body());
                return items;
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode results = json.get("results");
            if (results == null || !results.isArray()) return items;

            for (JsonNode node : results) {
                String id    = node.path("id").asText(null);
                String title = node.path("title").asText("");
                String url   = node.path("url").asText(null);
                Instant publishedAt = parseInstant(node.path("published_at").asText(null));

                if (title.isBlank()) continue;

                items.add(NewsItem.builder()
                        .sourceId(config.getSourceId())
                        .externalId(id)
                        .title(title)
                        .url(url)
                        .category(config.getCategory())
                        .publishedAt(publishedAt)
                        .build());
            }

            log.debug("[CryptoPanicSource] 수집 완료 — {}건", items.size());

        } catch (Exception e) {
            log.error("[CryptoPanicSource] 수집 실패: {}", e.getMessage());
        }
        return items;
    }

    private Instant parseInstant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
