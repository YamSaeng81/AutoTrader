package com.cryptoautotrader.api.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-01 P0 — <b>LLM 응답 본문이 조용히 사라지고 있었다.</b>
 *
 * <h3>무슨 일이 있었나</h3>
 * <p>{@code ClaudeProvider} 가 {@code /content/0/text} 로 본문을 읽었다. 그런데 Messages API 의
 * {@code content} 는 <b>블록 배열</b>이고 첫 블록이 항상 텍스트가 아니다 — 모델이 생각하면
 * {@code thinking} 블록이 앞에 붙는다. 그러면 {@code /content/0/text} 가 존재하지 않아
 * {@code asText("")} 가 <b>빈 문자열</b>을 돌려준다.</p>
 *
 * <p>더 나쁜 건 그게 {@code success=true} 로 나갔다는 점이다. 호출부는 "분석 결과가 빈 것"을
 * 정상으로 받아들여 <b>빈 리포트를 발송</b>했다.</p>
 *
 * <h3>실측 피해 (2026-09-01 운영 DB, 최근 14일)</h3>
 * <pre>
 *   태스크              호출  빈응답  평균 출력토큰
 *   LOG_SUMMARY         28     0      315
 *   NEWS_SUMMARY        36    10      471
 *   REPORT_NARRATION    20    11      724
 *   SIGNAL_ANALYSIS     27    24      977    ← 89%
 * </pre>
 * <p><b>출력이 길수록 빈 응답이 많다</b> — 길고 어려운 요청일수록 모델이 생각하기 때문이다.
 * 12시간 리포트의 AI 분석 섹션이 몇 주간 비어 있었고, 토큰 비용은 그대로 나갔다.</p>
 */
class ClaudeResponseParsingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    // ── 🔴 회귀 방지 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 thinking 블록이 앞에 와도 본문을 읽는다 — 이게 24/27 건을 날린 원인")
    void thinkingBlockFirst_stillExtractsText() throws Exception {
        String body = """
            {"content":[
               {"type":"thinking","thinking":"먼저 표본 수를 확인하자..."},
               {"type":"text","text":"1. 한 줄 진단 — 표본 부족으로 판단 보류."}
             ],
             "usage":{"input_tokens":4441,"output_tokens":1474},
             "stop_reason":"end_turn"}
            """;
        assertThat(ClaudeProvider.extractText(json(body)))
                .as("content[0] 만 보면 thinking 블록이라 본문이 통째로 사라진다")
                .isEqualTo("1. 한 줄 진단 — 표본 부족으로 판단 보류.");
    }

    @Test
    @DisplayName("텍스트가 여러 블록으로 쪼개져 와도 순서대로 이어 붙인다")
    void multipleTextBlocks_areJoinedInOrder() throws Exception {
        String body = """
            {"content":[
               {"type":"text","text":"첫 문단"},
               {"type":"text","text":"둘째 문단"}
             ],
             "usage":{"input_tokens":10,"output_tokens":20}}
            """;
        assertThat(ClaudeProvider.extractText(json(body)))
                .isEqualTo("첫 문단\n둘째 문단");
    }

    @Test
    @DisplayName("평범한 단일 텍스트 응답은 그대로 읽는다 — 기존 동작을 깨지 않았다")
    void plainSingleTextBlock_unchanged() throws Exception {
        String body = """
            {"content":[{"type":"text","text":"오늘의 시장 요약입니다."}],
             "usage":{"input_tokens":10,"output_tokens":20}}
            """;
        assertThat(ClaudeProvider.extractText(json(body)))
                .isEqualTo("오늘의 시장 요약입니다.");
    }

    @Test
    @DisplayName("tool_use 등 텍스트가 아닌 블록은 건너뛴다")
    void nonTextBlocks_areSkipped() throws Exception {
        String body = """
            {"content":[
               {"type":"tool_use","id":"t1","name":"calc","input":{}},
               {"type":"text","text":"결과는 42 입니다."}
             ],
             "usage":{"input_tokens":10,"output_tokens":20}}
            """;
        assertThat(ClaudeProvider.extractText(json(body)))
                .isEqualTo("결과는 42 입니다.");
    }

    // ── 경계 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("텍스트 블록이 하나도 없으면 빈 문자열 — 호출부가 오류로 처리한다")
    void noTextBlock_returnsBlank() throws Exception {
        String body = """
            {"content":[{"type":"thinking","thinking":"..."}],
             "usage":{"input_tokens":10,"output_tokens":700}}
            """;
        assertThat(ClaudeProvider.extractText(json(body))).isEmpty();
    }

    @Test
    @DisplayName("content 가 배열이 아니거나 없어도 터지지 않는다")
    void malformedResponse_doesNotThrow() throws Exception {
        assertThat(ClaudeProvider.extractText(json("{}"))).isEmpty();
        assertThat(ClaudeProvider.extractText(json("{\"content\":\"문자열\"}"))).isEmpty();
        assertThat(ClaudeProvider.extractText(json("{\"content\":[]}"))).isEmpty();
    }

    @Test
    @DisplayName("max_tokens 로 잘린 응답도 있는 만큼은 살린다 — 통째로 버리지 않는다")
    void truncatedResponse_keepsWhatArrived() throws Exception {
        String body = """
            {"content":[
               {"type":"thinking","thinking":"길게 생각하는 중"},
               {"type":"text","text":"1. 진단 — 이 전략은"}
             ],
             "usage":{"input_tokens":100,"output_tokens":2000},
             "stop_reason":"max_tokens"}
            """;
        assertThat(ClaudeProvider.extractText(json(body)))
                .isEqualTo("1. 진단 — 이 전략은");
    }
}
