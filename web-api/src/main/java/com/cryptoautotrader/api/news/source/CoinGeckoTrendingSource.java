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
import java.util.ArrayList;
import java.util.List;

/**
 * CoinGecko 트렌딩 코인 소스 (source_type = "COINGECKO").
 * API 키 불필요 (무료 플랜).
 * 트렌딩 코인 목록을 뉴스 형태로 변환한다.
 */
@Component
public class CoinGeckoTrendingSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoTrendingSource.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CoinGeckoTrendingSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getSupportedType() {
        return "COINGECKO";
    }

    @Override
    public List<NewsItem> fetch(NewsSourceConfigEntity config) {
        List<NewsItem> items = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[CoinGeckoTrendingSource] HTTP {}", response.statusCode());
                return items;
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode coins = json.path("coins");
            if (!coins.isArray()) return items;

            Instant now = Instant.now();
            for (JsonNode coinNode : coins) {
                JsonNode item = coinNode.path("item");
                String id     = item.path("id").asText(null);
                String name   = item.path("name").asText("");
                String symbol = item.path("symbol").asText("").toUpperCase();
                String rank   = item.path("market_cap_rank").asText("?");

                if (name.isBlank()) continue;

                String title = String.format("[CoinGecko 트렌딩] %s (%s) — 시총 순위 %s위", name, symbol, rank);

                items.add(NewsItem.builder()
                        .sourceId(config.getSourceId())
                        .externalId("trending_" + id + "_" + now.getEpochSecond() / 3600) // 1시간 단위 중복 방지
                        .title(title)
                        .url("https://www.coingecko.com/en/coins/" + id)
                        .category(config.getCategory())
                        .publishedAt(now)
                        .build());
            }

            log.debug("[CoinGeckoTrendingSource] 수집 완료 — {}건", items.size());

        } catch (Exception e) {
            log.error("[CoinGeckoTrendingSource] 수집 실패: {}", e.getMessage());
        }
        return items;
    }
}
