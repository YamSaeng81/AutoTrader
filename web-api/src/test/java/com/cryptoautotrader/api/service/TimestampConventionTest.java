package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타임스탬프 규약 — <b>신규 컬럼은 예외 없이 {@code TIMESTAMPTZ}</b> (2026-09-08 신설).
 *
 * <h3>왜 기계로 강제하나</h3>
 * <p>{@code ENGINE_PARITY.md} §4 가 이 규칙을 <b>문장으로만</b> 적어 두고 경고 목록을 유지해 왔다.
 * 그 사이 naive {@code timestamp} 컬럼이 9개 테이블 17개까지 늘었고, 두 번 사고가 났다:</p>
 *
 * <ul>
 *   <li>2026-08-18 — {@code flyway_schema_history.installed_on} 에서 오판</li>
 *   <li>2026-08-19 — 폐기 판정 확인 중 {@code discord_send_log} 조회가 0건.
 *       알림은 정상 발송된 상태였다</li>
 * </ul>
 *
 * <p>둘 다 <b>에러가 아니라 조용히 틀린 답</b>이라 알아채기 어려웠다. 문장으로 적힌 규칙은
 * 이 저장소의 반복 결함(= 사람이 확인하다 놓친다)에 그대로 노출된다 — 그래서 테스트로 옮긴다.</p>
 *
 * <p>기존 17개는 V77 이 일괄 변환했다. 이 테스트는 <b>새로 생기는 것</b>을 막는다.</p>
 */
class TimestampConventionTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** {@code TIMESTAMP} 로 끝나되 {@code TIMESTAMPTZ}·{@code TIMESTAMP WITH TIME ZONE} 은 제외. */
    private static final Pattern NAIVE_TIMESTAMP = Pattern.compile(
            "\\bTIMESTAMP\\b(?!\\s*TZ)(?!\\s+WITH\\s+TIME\\s+ZONE)", Pattern.CASE_INSENSITIVE);

    /**
     * V77 이전 마이그레이션은 naive 를 만든 이력이 있어 검사 대상에서 뺀다 — 그 결과물은
     * V77 이 이미 변환했다. 새 파일(V78~)부터 규약이 적용된다.
     */
    private static final int CONVENTION_FROM_VERSION = 78;

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("파일을 읽을 수 없습니다: " + p, e);
        }
    }

    /** SQL 주석(`--`)을 지운다 — 설명문에 등장하는 TIMESTAMP 단어가 오탐을 내지 않도록. */
    private static String stripComments(String sql) {
        return sql.lines()
                .map(l -> {
                    int i = l.indexOf("--");
                    return i >= 0 ? l.substring(0, i) : l;
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("V78 이후 마이그레이션은 naive TIMESTAMP 를 만들지 않는다")
    void 신규_마이그레이션은_timestamptz_만_쓴다() {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                 .filter(p -> versionOf(p) >= CONVENTION_FROM_VERSION)
                 .forEach(p -> {
                     String sql = stripComments(read(p));
                     if (NAIVE_TIMESTAMP.matcher(sql).find()) {
                         violations.add(p.getFileName().toString());
                     }
                 });
        } catch (IOException e) {
            throw new IllegalStateException("마이그레이션 디렉터리를 읽을 수 없습니다", e);
        }

        assertThat(violations)
                .as("naive TIMESTAMP 는 `WHERE created_at > now() - interval '90 minutes'` 같은 조회에서%n"
                        + "에러 없이 **틀린 답**을 준다 (2026-08-18·08-19 두 번 사고).%n"
                        + "TIMESTAMPTZ 를 쓸 것. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("V77 이 naive 컬럼 17개를 전부 변환한다 — 하나라도 빠지면 규약에 구멍이 남는다")
    void v77이_모든_naive_컬럼을_변환한다() {
        // 주석을 반드시 먼저 지운다 — 이 파일은 머리말에서 테이블명을 여럿 언급하므로,
        // 주석을 남기면 실제 ALTER 문이 사라져도 설명문에 걸려 통과한다
        // (2026-09-08 뮤테이션 테스트로 실제 확인한 구멍: regime_change_log 변환을 주석 처리했는데
        //  테스트가 통과했다).
        String sql = stripComments(read(MIGRATION_DIR.resolve("V77__timestamptz_for_naive_tables.sql")))
                .toLowerCase(Locale.ROOT);

        // 2026-09-08 운영 DB 실측 — flyway_schema_history(=Flyway 소유) 제외 9개 테이블 17개 컬럼
        record Col(String table, String column) {}
        List<Col> expected = List.of(
                new Col("discord_channel_config", "created_at"),
                new Col("discord_channel_config", "updated_at"),
                new Col("discord_send_log", "created_at"),
                new Col("llm_provider_config", "created_at"),
                new Col("llm_provider_config", "updated_at"),
                new Col("llm_task_config", "updated_at"),
                new Col("news_item_cache", "fetched_at"),
                new Col("news_item_cache", "published_at"),
                new Col("news_source_config", "created_at"),
                new Col("news_source_config", "last_fetched_at"),
                new Col("news_source_config", "updated_at"),
                new Col("notion_report_config", "updated_at"),
                new Col("notion_report_log", "created_at"),
                new Col("notion_report_log", "completed_at"),
                new Col("notion_report_log", "period_start"),
                new Col("notion_report_log", "period_end"),
                new Col("regime_change_log", "detected_at"));

        assertThat(expected).hasSize(17);

        // 공백 개수는 정렬 때문에 달라지므로 하나로 눌러 비교한다.
        String flat = sql.replaceAll("\\s+", " ");
        for (Col c : expected) {
            assertThat(flat)
                    .as("%s 테이블이 V77 에 없다", c.table())
                    .contains("alter table " + c.table());
            assertThat(flat)
                    .as("%s.%s 가 V77 에서 timestamptz 로 변환되지 않는다", c.table(), c.column())
                    .contains("alter column " + c.column() + " type timestamptz");
        }
        // 변환은 반드시 UTC 기준이어야 한다 — 저장값이 UTC 임을 운영 DB 로 확인했다.
        assertThat(sql).contains("at time zone 'utc'");
        // Flyway 소유 테이블은 건드리지 않는다.
        assertThat(sql)
                .as("flyway_schema_history 는 Flyway 가 소유한다 — 변환 대상이 아니다")
                .doesNotContain("alter table flyway_schema_history");
    }
}
