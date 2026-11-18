package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.TelegramNotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 최소 알림 후보(총괄 v1.190 §4-2) — 대역 시험. 전송·HTTP·DB 없이 요약 메시지 생성만 본다.
 * 범위: 같은 ID 의 LIVE/DYNAMIC 분리, 재기동 첫 요약 표시, 알림 꺼짐 경계. 즉시 체결 알림·정지/위험 알림 정책은 바꾸지 않았다.
 */
@DisplayName("최소 알림 — 종류#ID·재기동 표시·꺼짐 버퍼")
class TelegramMinimalAlertTest {

    private TelegramNotificationService svc;

    // 2026-12-02 00:00:00 KST = 12-01 15:00Z (자정 요약 → 구간 12-01 12:00~24:00 KST)
    private static final Instant MIDNIGHT = Instant.parse("2026-12-01T15:00:00Z");
    // 2026-12-02 12:00:00.4 KST (정오 요약이 조금 늦게 돈 회차 → 구간 12-02 00:00~12:00 KST)
    private static final Instant NOON_LATE = Instant.parse("2026-12-02T03:00:00.400Z");

    @BeforeEach
    void setUp() {
        svc = new TelegramNotificationService(mock(TelegramNotificationLogRepository.class));
        ReflectionTestUtils.setField(svc, "enabled", true);
        svc.setStartedAtForTest(Instant.parse("2026-11-01T00:00:00Z"));   // 기본: 구간 훨씬 전에 기동
    }

    private void trade(String label, String side) {
        svc.bufferTradeEvent(label, "KRW-BTC", side, new BigDecimal("100000000"), new BigDecimal("0.0001"),
                new BigDecimal("5"), "SELL".equals(side) ? new BigDecimal("-12") : null, "시험");
    }

    @Test
    @DisplayName("표시 함수 — 종류#ID, 종류 없으면 '종류미상'")
    void label() {
        assertThat(TelegramNotificationService.sessionLabel("LIVE", 5L)).isEqualTo("LIVE#5");
        assertThat(TelegramNotificationService.sessionLabel("DYNAMIC", 5L)).isEqualTo("DYNAMIC#5");
        assertThat(TelegramNotificationService.sessionLabel("DYN_PAPER", 5L)).isEqualTo("DYN_PAPER#5");
        assertThat(TelegramNotificationService.sessionLabel(null, 5L)).isEqualTo("종류미상#5");
        assertThat(TelegramNotificationService.sessionLabel(" ", 5L)).isEqualTo("종류미상#5");
    }

    @Test
    @DisplayName("같은 ID 5 의 LIVE·DYNAMIC 거래는 두 요약으로 나뉜다(종전 '세션#5' 하나로 섞임)")
    void sameIdSplit() {
        trade(TelegramNotificationService.sessionLabel("LIVE", 5L), "BUY");
        trade(TelegramNotificationService.sessionLabel("DYNAMIC", 5L), "BUY");
        trade(TelegramNotificationService.sessionLabel("DYNAMIC", 5L), "SELL");
        trade(TelegramNotificationService.sessionLabel("LIVE", 5L), "SELL");
        List<String[]> msgs = svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT);
        assertThat(msgs).extracting(m -> m[0]).containsExactly("LIVE#5", "DYNAMIC#5");
        assertThat(msgs.get(0)[1]).contains("세션: `LIVE\\#5`").contains("매수: `1회` / 매도: `1회`");
        assertThat(msgs.get(1)[1]).contains("세션: `DYNAMIC\\#5`").contains("매수: `1회` / 매도: `1회`");
        assertThat(svc.bufferedTradeEvents()).isZero();
    }

    @Test
    @DisplayName("구간 경계 — 0시/12시로 내린 뒤 12시간 앞, 늦게 돈 회차도 같은 구간")
    void periodStart() {
        assertThat(TelegramNotificationService.periodStart(MIDNIGHT)).isEqualTo(Instant.parse("2026-12-01T03:00:00Z"));
        assertThat(TelegramNotificationService.periodStart(NOON_LATE)).isEqualTo(Instant.parse("2026-12-01T15:00:00Z"));
    }

    @Test
    @DisplayName("구간 안 재기동 — 첫 요약(거래 있음·없음 모두) 머리에 재기동 줄, 구간 전 기동이면 없음")
    void restartNote() {
        svc.setStartedAtForTest(Instant.parse("2026-12-01T09:30:00Z"));   // 12-01 18:30 KST — 자정 요약 구간 안
        List<String[]> empty = svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT);
        assertThat(empty).hasSize(1);
        assertThat(empty.get(0)[0]).isNull();
        assertThat(empty.get(0)[1]).contains("`18:30` 재기동").contains("그 앞 거래는 이 요약에 없음").contains("매매 없음");

        trade(TelegramNotificationService.sessionLabel("LIVE", 1L), "BUY");
        List<String[]> withTrade = svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT);
        assertThat(withTrade.get(0)[1]).contains("`18:30` 재기동");

        // 다음 구간(정오 요약)에는 같은 기동 시각이 구간 전이므로 표시 없음
        List<String[]> next = svc.buildDailySummaries("오전 거래 요약 (자정~정오)", NOON_LATE);
        assertThat(next.get(0)[1]).doesNotContain("재기동");

        // 구간 시작 정각 기동(12:00:00 KST)은 구간 '안'이 아니다 — 앞 구간 거래가 버퍼에 남을 수 없으므로 표시하지 않는다
        svc.setStartedAtForTest(Instant.parse("2026-12-01T03:00:00Z"));
        assertThat(svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT).get(0)[1]).doesNotContain("재기동");
    }

    @Test
    @DisplayName("재기동 줄은 MarkdownV2 예약 문자(. ( ) - 등)를 새로 쓰지 않는다")
    void restartNoteMarkdownSafe() {
        svc.setStartedAtForTest(Instant.parse("2026-12-01T09:30:00Z"));
        String m = svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT).get(0)[1];
        String line = m.lines().filter(l -> l.contains("재기동")).findFirst().orElseThrow();
        assertThat(line).doesNotContainPattern("[_\\[\\]()~>#+\\-=|{}.!]");
    }

    @Test
    @DisplayName("알림 꺼짐 — 적재하지 않고 개수만 세며, 요약은 만들지 않고 개수를 0 으로 되돌린다")
    void disabledDoesNotAccumulate() {
        ReflectionTestUtils.setField(svc, "enabled", false);
        for (int i = 0; i < 1000; i++) trade(TelegramNotificationService.sessionLabel("LIVE", 1L), "BUY");
        assertThat(svc.bufferedTradeEvents()).isZero();
        assertThat(svc.droppedWhileDisabled()).isEqualTo(1000);
        assertThat(svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT)).isEmpty();
        assertThat(svc.droppedWhileDisabled()).isZero();
    }

    @Test
    @DisplayName("켜진 상태에서 쌓인 뒤 꺼지면 요약 시각에 버린다(남지 않음)")
    void disabledAfterBufferedClears() {
        trade(TelegramNotificationService.sessionLabel("DYNAMIC", 2L), "BUY");
        assertThat(svc.bufferedTradeEvents()).isEqualTo(1);
        ReflectionTestUtils.setField(svc, "enabled", false);
        assertThat(svc.buildDailySummaries("오후 거래 요약 (정오~자정)", MIDNIGHT)).isEmpty();
        assertThat(svc.bufferedTradeEvents()).isZero();
    }

    @Test
    @DisplayName("호출 5곳이 표시 함수를 쓴다 — 종전 '세션#'·'동적#'·'[모의투자] 세션#' 문자열이 버퍼 호출에 남지 않음")
    void callSitesUseLabel() throws Exception {
        Path dir = Path.of("src/main/java/com/cryptoautotrader/api/service");
        int uses = 0;
        for (String f : List.of("OrderExecutionEngine.java", "DynamicTradingService.java", "LiveTradingService.java", "PaperTradingService.java")) {
            List<String> lines = Files.readAllLines(dir.resolve(f), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("telegramService.bufferTradeEvent(")) {
                    String arg = lines.get(i + 1);
                    assertThat(arg).as("%s:%d", f, i + 2).contains("TelegramNotificationService.sessionLabel(")
                            .doesNotContain("\"세션#\"").doesNotContain("\"동적#\"").doesNotContain("[모의투자] 세션#");
                    uses++;
                }
            }
        }
        assertThat(uses).isEqualTo(5);
    }
}
