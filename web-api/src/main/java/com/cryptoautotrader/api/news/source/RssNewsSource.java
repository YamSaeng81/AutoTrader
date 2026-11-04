package com.cryptoautotrader.api.news.source;

import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.NewsFetchResult;
import com.cryptoautotrader.api.news.NewsItem;
import com.cryptoautotrader.api.news.NewsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
 * 루트가 rss(0.9x/2.0) 또는 RDF(1.0)이고 바로 아래 channel 이 있어야 정상 응답이다({@link #isSupportedFeed}).
 */
@Component
public class RssNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(RssNewsSource.class);
    static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
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
    public NewsFetchResult fetch(NewsSourceConfigEntity config) {
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
                return NewsFetchResult.httpStatus(response.statusCode());
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 방지
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(response.body());
            doc.getDocumentElement().normalize();
            // 지원하는 피드 구조가 아니면 정상 0건이 아니라 잘못된 응답이다
            if (!isSupportedFeed(doc.getDocumentElement())) {
                log.warn("[RssNewsSource] 지원하는 RSS 구조 아님(루트 rss 또는 rdf:RDF 바로 아래 channel 필요) — sourceId={}",
                        config.getSourceId());
                return NewsFetchResult.failed(NewsFetchResult.INVALID_RESPONSE);
            }

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
            return NewsFetchResult.success(items);

        } catch (Exception e) {
            log.error("[RssNewsSource] 수집 실패 — sourceId={}: {}", config.getSourceId(), e.getMessage());
            return NewsFetchResult.fromException(items, e);
        }
    }

    /**
     * 지원하는 피드 구조 (N11). 기존에 읽던 RSS 형식만 받는다 — Atom 등 새 형식은 추가하지 않는다.
     * <ul>
     *   <li>RSS 0.9x/2.0: 루트가 접두어 없는 {@code rss} 이고 바로 아래 {@code channel} 이 있다.</li>
     *   <li>RSS 1.0: 루트가 RDF 네임스페이스({@value #RDF_NS})의 {@code RDF} 이고 바로 아래 {@code channel} 이 있다.</li>
     * </ul>
     * 파서는 기존처럼 네임스페이스를 해석하지 않으므로(미선언 접두어 허용) 접두어와 xmlns 속성을 직접 대조한다.
     */
    static boolean isSupportedFeed(Element root) {
        if (root == null) return false;
        String tag = root.getTagName();
        boolean rss = "rss".equals(tag);
        boolean rdf = false;
        int colon = tag.indexOf(':');
        String local = colon < 0 ? tag : tag.substring(colon + 1);
        if ("RDF".equals(local)) {
            String nsAttr = colon < 0 ? "xmlns" : "xmlns:" + tag.substring(0, colon);
            rdf = RDF_NS.equals(root.getAttribute(nsAttr));
        }
        if (!rss && !rdf) return false;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && "channel".equals(((Element) n).getTagName())) return true;
        }
        return false;
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
