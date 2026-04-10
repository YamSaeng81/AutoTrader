package com.cryptoautotrader.api.report;

import com.cryptoautotrader.api.entity.NotionReportConfigEntity;
import com.cryptoautotrader.api.repository.NotionReportConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Notion REST API 클라이언트.
 * 토큰·데이터베이스 ID는 DB(notion_report_config)에서 런타임 로드.
 *
 * <p>주요 기능:
 * <ul>
 *   <li>{@link #createPage} — 데이터베이스에 새 페이지 생성</li>
 *   <li>{@link #buildBlocks} — Notion 블록 빌더 헬퍼</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class NotionApiClient {

    private static final Logger log = LoggerFactory.getLogger(NotionApiClient.class);
    private static final String NOTION_API = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    private final NotionReportConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Notion 데이터베이스에 새 페이지를 생성한다.
     *
     * @param title  페이지 제목
     * @param blocks 본문 블록 목록
     * @return 생성된 페이지 ID (실패 시 null)
     */
    public String createPage(String title, List<ObjectNode> blocks) {
        String token = getConfig("notion_token");
        String databaseId = getConfig("database_id");

        if (token == null || token.isBlank() || databaseId == null || databaseId.isBlank()) {
            log.warn("[NotionApiClient] notion_token 또는 database_id가 설정되지 않았습니다.");
            return null;
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();

            // parent: database
            body.putObject("parent").put("database_id", databaseId);

            // properties: title
            ObjectNode titleProp = body.putObject("properties")
                    .putObject("title")
                    .putArray("title")
                    .addObject();
            titleProp.putObject("text").put("content", title);

            // children blocks
            ArrayNode children = body.putArray("children");
            blocks.forEach(children::add);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/pages"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[NotionApiClient] 페이지 생성 실패: HTTP {} — {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.body());
            String pageId = json.path("id").asText(null);
            log.info("[NotionApiClient] 페이지 생성 완료: {}", pageId);
            return pageId;

        } catch (Exception e) {
            log.error("[NotionApiClient] 페이지 생성 오류", e);
            return null;
        }
    }

    /** 생성된 페이지 URL 반환 */
    public String pageUrl(String pageId) {
        if (pageId == null) return null;
        return "https://notion.so/" + pageId.replace("-", "");
    }

    /** 설정값 조회 */
    public String getConfig(String key) {
        return configRepo.findByConfigKey(key)
                .map(NotionReportConfigEntity::getConfigValue)
                .orElse(null);
    }

    public boolean isEnabled() {
        return "true".equalsIgnoreCase(getConfig("report_enabled"));
    }

    // ── 블록 빌더 헬퍼 ────────────────────────────────────────────────────────

    public ObjectNode heading1(String text) {
        return blockNode("heading_1", richText(text));
    }

    public ObjectNode heading2(String text) {
        return blockNode("heading_2", richText(text));
    }

    public ObjectNode heading3(String text) {
        return blockNode("heading_3", richText(text));
    }

    public ObjectNode paragraph(String text) {
        return blockNode("paragraph", richText(text));
    }

    public ObjectNode divider() {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("object", "block");
        block.put("type", "divider");
        block.putObject("divider");
        return block;
    }

    /**
     * Callout 블록 (강조 박스).
     *
     * @param emoji 이모지 아이콘
     * @param text  내용
     * @param color background color (blue_background, green_background 등)
     */
    public ObjectNode callout(String emoji, String text, String color) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("object", "block");
        block.put("type", "callout");
        ObjectNode calloutNode = block.putObject("callout");
        calloutNode.putObject("icon").put("type", "emoji").put("emoji", emoji);
        ArrayNode rt = calloutNode.putArray("rich_text");
        rt.addObject().put("type", "text").putObject("text").put("content", text);
        calloutNode.put("color", color != null ? color : "default");
        return block;
    }

    /**
     * 간단한 테이블 블록.
     *
     * @param headers 헤더 행
     * @param rows    데이터 행 목록
     */
    public ObjectNode table(List<String> headers, List<List<String>> rows) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("object", "block");
        block.put("type", "table");
        ObjectNode tableNode = block.putObject("table");
        tableNode.put("table_width", headers.size());
        tableNode.put("has_column_header", true);
        tableNode.put("has_row_header", false);

        ArrayNode children = tableNode.putArray("children");
        // 헤더 행
        addTableRow(children, headers);
        // 데이터 행
        rows.forEach(row -> addTableRow(children, row));
        return block;
    }

    private void addTableRow(ArrayNode parent, List<String> cells) {
        ObjectNode row = parent.addObject();
        row.put("type", "table_row");
        ArrayNode rowCells = row.putObject("table_row").putArray("cells");
        cells.forEach(cell -> {
            ArrayNode cellArr = rowCells.addArray();
            cellArr.addObject().put("type", "text").putObject("text").put("content", cell != null ? cell : "");
        });
    }

    private ObjectNode blockNode(String type, ArrayNode richTextArr) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("object", "block");
        block.put("type", type);
        block.putObject(type).set("rich_text", richTextArr);
        return block;
    }

    private ArrayNode richText(String text) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.addObject().put("type", "text").putObject("text").put("content", text != null ? text : "");
        return arr;
    }
}
