package com.cryptoautotrader.api.news.source;

import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.NewsItem;
import com.cryptoautotrader.api.news.NewsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 범용 RSS 피드 뉴스 소스 (source_type = "RSS").
 * CoinDesk, Bloomberg, Naver 경제 등 RSS URL을 지원하는 모든 소스에 재사용된다.
 *
 * <p>하나의 구현체로 여러 RSS 소스를 처리 (설정만 다름).
 */
@Component
public class RssNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(RssNewsSource.class);
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final HttpClient httpClient;

    public RssNewsSource() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getSupportedType() {
        return "RSS";
    }

    @Override
    public List<NewsItem> fetch(NewsSourceConfigEntity config) {
        List<NewsItem> items = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "CryptoAutoTrader/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.warn("[RssNewsSource] HTTP {} — sourceId={}", response.statusCode(), config.getSourceId());
                return items;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 방지
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(response.body());
            doc.getDocumentElement().normalize();

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element item = (Element) itemNodes.item(i);
                String title   = getText(item, "title");
                String link    = getText(item, "link");
                String pubDate = getText(item, "pubDate");
                String guid    = getText(item, "guid");

                if (title == null || title.isBlank()) continue;

                // externalId: guid 우선, 없으면 link 사용
                String externalId = (guid != null && !guid.isBlank()) ? guid : link;

                items.add(NewsItem.builder()
                        .sourceId(config.getSourceId())
                        .externalId(externalId)
                        .title(title.trim())
                        .url(link)
                        .category(config.getCategory())
                        .publishedAt(parseRfc822(pubDate))
                        .build());
            }

            log.debug("[RssNewsSource] 수집 완료 — sourceId={} {}건", config.getSourceId(), items.size());

        } catch (Exception e) {
            log.error("[RssNewsSource] 수집 실패 — sourceId={}: {}", config.getSourceId(), e.getMessage());
        }
        return items;
    }

    private String getText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent();
    }

    private Instant parseRfc822(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return ZonedDateTime.parse(text.trim(), RFC_822).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
