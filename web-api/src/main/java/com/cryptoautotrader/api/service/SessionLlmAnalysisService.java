package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.llm.LlmResponse;
import com.cryptoautotrader.api.llm.LlmTask;
import com.cryptoautotrader.api.llm.LlmTaskRouter;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 특정 세션의 누적 로그를 LLM 에 보내 전략 분석을 받고, 결과를 텔레그램으로 전달한다
 * (2026-09-01 신규).
 *
 * <h3>왜 필요한가</h3>
 * <p>전략 로그 화면은 <b>개별 신호</b>를 보여준다. 그런데 "이 세션의 전략이 지금 잘 돌고
 * 있는가"는 수백 건을 <b>가로질러</b> 봐야 답이 나온다 — 어떤 코인에서 반복해서 지는지,
 * 차단 사유가 한쪽으로 쏠려 있는지, 청산이 손절에만 몰려 있는지. 사람이 매번 그걸 훑는
 * 대신 집계해서 LLM 에 넘긴다.</p>
 *
 * <h3>원문 로그를 통째로 보내지 않는 이유</h3>
 * <p>세션 하나에 로그가 수만 건씩 쌓이므로 그대로 넣으면 컨텍스트를 넘기고 비용도 커진다.
 * 더 중요한 건 <b>LLM 이 원문에서 다시 집계하면 그 숫자를 믿을 수 없다</b>는 점이다.
 * 통계는 여기 코드가 정확히 내고, LLM 에는 <b>해석</b>만 맡긴다. 원문은 판단 근거를 보여줄
 * 최근 몇 건만 표본으로 붙인다.</p>
 *
 * <h3>비동기인 이유</h3>
 * <p>LLM 응답이 수십 초 걸린다. HTTP 요청을 붙잡고 있으면 프록시 타임아웃에 걸리므로
 * 즉시 반환하고 결과는 텔레그램으로 보낸다. 호출 원문·응답은 {@code llm_call_log} 에
 * {@link LlmTaskRouter} 가 자동 저장하므로 텔레그램을 놓쳐도 화면에서 다시 볼 수 있다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLlmAnalysisService {

    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** 프롬프트에 붙일 최근 로그 표본 수 — 통계는 전수, 원문은 표본만. */
    private static final int SAMPLE_LOG_COUNT = 30;

    /** 텔레그램 메시지 1건 최대 길이 (API 한도 4096자에 이스케이프 여유를 뺀 값). */
    private static final int TELEGRAM_CHUNK = 3500;

    private final StrategyLogRepository strategyLogRepo;
    private final PositionRepository positionRepository;
    private final LlmTaskRouter llmTaskRouter;
    private final TelegramNotificationService telegramService;

    /**
     * 자기 자신을 프록시 경유해 부르기 위한 참조. {@code request()} 가 {@code analyzeAsync()} 를
     * 그냥 부르면 self-invocation 이라 {@code @Async} 가 <b>무시되고</b> LLM 호출이 HTTP
     * 쓰레드에서 동기로 돌아 응답이 수십 초 막힌다
     * ({@code DynamicTradingService.self} 와 같은 이유).
     */
    @Lazy
    @Autowired
    private SessionLlmAnalysisService self;

    /**
     * 진행 중인 분석 — 같은 세션에 대해 중복 요청이 쌓이는 것을 막는다.
     * LLM 호출은 비용이 붙으므로 버튼 연타가 그대로 과금되면 안 된다.
     */
    private final Map<String, Instant> inFlight = new ConcurrentHashMap<>();

    /** 같은 세션을 다시 분석할 수 있게 되기까지의 최소 간격. */
    private static final Duration REANALYZE_COOLDOWN = Duration.ofMinutes(2);

    /** 분석 요청 수락 결과 — 컨트롤러가 사용자에게 돌려줄 메시지를 담는다. */
    public record Accepted(boolean accepted, String message, int logCount, int positionCount) {}

    /**
     * 분석을 접수한다. 로그가 아예 없으면 LLM 을 호출하지 않고 거절한다 —
     * 빈 입력으로 부르면 그럴듯한 소설이 돌아오는데, 그게 가장 나쁜 결과다.
     */
    @Transactional(readOnly = true)
    public Accepted request(String sessionType, Long sessionId, int hours) {
        String key = sessionType + ":" + sessionId;
        Instant prev = inFlight.get(key);
        if (prev != null && prev.isAfter(Instant.now().minus(REANALYZE_COOLDOWN))) {
            return new Accepted(false, "이 세션은 방금 분석을 요청했습니다. 2분 뒤 다시 시도하세요.", 0, 0);
        }

        Instant from = Instant.now().minus(Duration.ofHours(hours));
        List<StrategyLogEntity> logs = strategyLogRepo
                .findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(sessionType, sessionId)
                .stream()
                .filter(l -> l.getCreatedAt() != null && l.getCreatedAt().isAfter(from))
                .toList();
        List<PositionEntity> positions = positionRepository
                .findBySessionKindAndSessionIdOrderByOpenedAtDesc(sessionType, sessionId);

        if (logs.isEmpty() && positions.isEmpty()) {
            return new Accepted(false,
                    String.format("최근 %d시간 안에 이 세션의 전략 로그도 포지션도 없습니다 — 분석할 근거가 없습니다.", hours),
                    0, 0);
        }

        inFlight.put(key, Instant.now());
        self.analyzeAsync(sessionType, sessionId, hours, logs, positions);
        return new Accepted(true,
                String.format("분석을 시작했습니다 (로그 %d건 · 포지션 %d건). 완료되면 텔레그램으로 전송됩니다.",
                        logs.size(), positions.size()),
                logs.size(), positions.size());
    }

    /**
     * 실제 LLM 호출 — {@code taskExecutor} 에서 돈다.
     *
     * <p>엔티티를 인자로 넘겨받는다. 비동기 스레드에는 트랜잭션·영속성 컨텍스트가 없으므로
     * 여기서 지연 로딩을 하면 터진다 — 필요한 데이터는 호출부에서 이미 다 읽어 왔다.</p>
     */
    @Async("taskExecutor")
    public void analyzeAsync(String sessionType, Long sessionId, int hours,
                             List<StrategyLogEntity> logs, List<PositionEntity> positions) {
        String label = sessionLabel(sessionType, sessionId);
        try {
            String userPrompt = buildUserPrompt(sessionType, sessionId, hours, logs, positions);
            LlmResponse resp = llmTaskRouter.route(LlmTask.SIGNAL_ANALYSIS, SYSTEM_PROMPT, userPrompt);

            if (!resp.isSuccess()) {
                telegramService.sendCustomNotification(String.format(
                        "❌ %s LLM 분석 실패: %s", label, resp.getErrorMessage()));
                return;
            }
            sendChunked(String.format("🧠 %s 전략 분석 (최근 %d시간 · 로그 %d건 · 포지션 %d건 · %s)\n\n%s",
                    label, hours, logs.size(), positions.size(),
                    resp.getModelUsed() != null ? resp.getModelUsed() : resp.getProviderName(),
                    resp.getContent()));
            log.info("[SessionLlm] 분석 완료 — {} 로그={} 포지션={} 토큰={}/{}",
                    label, logs.size(), positions.size(), resp.getPromptTokens(), resp.getCompletionTokens());
        } catch (Exception e) {
            log.error("[SessionLlm] 분석 중 오류 — {}", label, e);
            telegramService.sendCustomNotification(
                    String.format("❌ %s LLM 분석 중 오류: %s", label, e.getMessage()));
        } finally {
            inFlight.remove(sessionType + ":" + sessionId);
        }
    }

    // ── 프롬프트 ──────────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            당신은 암호화폐 자동매매 전략을 검증하는 퀀트 애널리스트입니다.
            주어진 것은 실제로 운영 중인 세션 하나의 누적 통계입니다. 다음을 한국어로 작성하세요.

            1. 한 줄 진단 — 이 전략이 지금 작동하고 있는가, 아닌가.
            2. 근거 — 위 진단을 뒷받침하는 수치 3가지. 반드시 주어진 숫자만 인용하고 새로 만들지 마세요.
            3. 진입 신호의 질 — BUY 대비 실제 체결 비율, 차단 사유의 쏠림이 뜻하는 것.
            4. 청산의 질 — 손절/익절/시간초과 비율. 손절에 쏠려 있으면 손절폭이 좁은 것인지
               진입이 이른 것인지 구분해서 판단하세요.
            5. 코인별 편차 — 특정 종목에서만 지고 있다면 그 종목을 지목하세요.
            6. 다음 행동 — 계속 둘 것인가, 파라미터를 볼 것인가, 정지할 것인가. 하나만 고르세요.

            규칙:
            - 표본이 적으면(청산 20건 미만) 반드시 "표본 부족"이라고 먼저 말하고, 승률·수익률을
              결론의 근거로 쓰지 마세요. 부호와 사유 분포만 언급하세요.
            - 주어지지 않은 지표(샤프, MDD 등)를 추정하지 마세요.
            - 듣기 좋은 말 대신 문제를 지목하세요. 문제가 없으면 없다고 하세요.
            - 마크다운 표는 쓰지 마세요(텔레그램에서 깨집니다). 800자 이내.
            """;

    private String buildUserPrompt(String sessionType, Long sessionId, int hours,
                                   List<StrategyLogEntity> logs, List<PositionEntity> positions) {
        StringBuilder sb = new StringBuilder();
        sb.append("[세션] ").append(sessionLabel(sessionType, sessionId))
          .append("  (").append(isPaperKind(sessionType) ? "모의매매 — 실전과 매매 규칙 동일" : "실전매매").append(")\n");
        sb.append("[분석 구간] 최근 ").append(hours).append("시간\n\n");

        appendSignalStats(sb, logs);
        appendPositionStats(sb, positions);
        appendSampleLogs(sb, logs);
        return sb.toString();
    }

    private void appendSignalStats(StringBuilder sb, List<StrategyLogEntity> logs) {
        sb.append("[신호 통계] 총 ").append(logs.size()).append("건\n");
        if (logs.isEmpty()) {
            sb.append("  (구간 내 전략 로그 없음)\n\n");
            return;
        }
        Map<String, Long> bySignal = counted(logs, l -> l.getSignal() == null ? "UNKNOWN" : l.getSignal());
        sb.append("  신호 분포: ").append(fmtCounts(bySignal)).append('\n');

        long buys = logs.stream().filter(l -> "BUY".equalsIgnoreCase(l.getSignal())).count();
        long executed = logs.stream().filter(StrategyLogEntity::isWasExecuted).count();
        sb.append("  BUY ").append(buys).append("건 중 실제 체결 ").append(executed).append("건");
        if (buys > 0) {
            sb.append(String.format(" (%.1f%%)", executed * 100.0 / buys));
        }
        sb.append('\n');

        Map<String, Long> blocked = counted(
                logs.stream().filter(l -> l.getBlockedReason() != null && !l.getBlockedReason().isBlank()).toList(),
                StrategyLogEntity::getBlockedReason);
        sb.append("  차단 사유: ").append(blocked.isEmpty() ? "없음" : fmtCounts(top(blocked, 6))).append('\n');

        Map<String, Long> byRegime = counted(logs, l -> l.getMarketRegime() == null ? "미상" : l.getMarketRegime());
        sb.append("  레짐 분포: ").append(fmtCounts(byRegime)).append('\n');

        Map<String, Long> byCoin = counted(logs, StrategyLogEntity::getCoinPair);
        sb.append("  평가 종목 ").append(byCoin.size()).append("개, 상위: ").append(fmtCounts(top(byCoin, 8))).append('\n');

        // 신호 품질(4h/24h 수익률)은 평가가 끝난 건만 집계한다 — 미평가분을 0으로 섞으면 희석된다.
        List<StrategyLogEntity> eval4h = logs.stream().filter(l -> l.getReturn4hPct() != null).toList();
        if (!eval4h.isEmpty()) {
            BigDecimal avg = avg(eval4h.stream().map(StrategyLogEntity::getReturn4hPct).toList());
            long hit = eval4h.stream().filter(l -> l.getReturn4hPct().signum() > 0).count();
            sb.append(String.format("  4h 사후 수익률: 평균 %s%% · 양수 %d/%d (%.1f%%)%n",
                    avg.toPlainString(), hit, eval4h.size(), hit * 100.0 / eval4h.size()));
        }
        List<StrategyLogEntity> eval24h = logs.stream().filter(l -> l.getReturn24hPct() != null).toList();
        if (!eval24h.isEmpty()) {
            BigDecimal avg = avg(eval24h.stream().map(StrategyLogEntity::getReturn24hPct).toList());
            long hit = eval24h.stream().filter(l -> l.getReturn24hPct().signum() > 0).count();
            sb.append(String.format("  24h 사후 수익률: 평균 %s%% · 양수 %d/%d (%.1f%%)%n",
                    avg.toPlainString(), hit, eval24h.size(), hit * 100.0 / eval24h.size()));
        }
        sb.append('\n');
    }

    private void appendPositionStats(StringBuilder sb, List<PositionEntity> positions) {
        sb.append("[포지션] 총 ").append(positions.size()).append("건 (세션 전체 기간)\n");
        if (positions.isEmpty()) {
            sb.append("  (진입한 포지션 없음 — 스캔만 하고 한 번도 사지 않았다)\n\n");
            return;
        }

        List<PositionEntity> closed = positions.stream()
                .filter(p -> "CLOSED".equals(p.getStatus())
                        && p.getRealizedPnl() != null
                        && p.getSize() != null && p.getSize().signum() > 0)
                .toList();
        long open = positions.stream().filter(p -> !"CLOSED".equals(p.getStatus())).count();

        BigDecimal realized = closed.stream().map(PositionEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long wins = closed.stream().filter(p -> p.getRealizedPnl().signum() > 0).count();

        sb.append("  청산 ").append(closed.size()).append("건 · 보유 중 ").append(open).append("건\n");
        sb.append("  실현손익 합계: ").append(realized.setScale(0, RoundingMode.HALF_UP)).append("원");
        sb.append("  ※ 매수·매도 수수료를 이미 뺀 순손익\n");
        if (!closed.isEmpty()) {
            sb.append(String.format("  승/패: %d승 %d패 (승률 %.1f%%)%n",
                    wins, closed.size() - wins, wins * 100.0 / closed.size()));
            sb.append("  건당 평균: ")
              .append(realized.divide(BigDecimal.valueOf(closed.size()), 0, RoundingMode.HALF_UP))
              .append("원\n");

            Map<String, Long> byExit = counted(closed,
                    p -> p.getExitReason() == null ? "미분류" : p.getExitReason().name());
            sb.append("  청산 사유: ").append(fmtCounts(byExit)).append('\n');

            // 코인별 손익 — 특정 종목에만 손실이 몰려 있는지 보기 위한 축.
            Map<String, BigDecimal> byCoin = new LinkedHashMap<>();
            for (PositionEntity p : closed) {
                byCoin.merge(p.getCoinPair(), p.getRealizedPnl(), BigDecimal::add);
            }
            String coinLine = byCoin.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(e -> e.getKey().replace("KRW-", "") + " " + e.getValue().setScale(0, RoundingMode.HALF_UP) + "원")
                    .collect(Collectors.joining(", "));
            sb.append("  코인별 실현손익(손실 큰 순): ").append(coinLine).append('\n');

            double avgHold = closed.stream()
                    .filter(p -> p.getOpenedAt() != null && p.getClosedAt() != null)
                    .mapToLong(p -> Duration.between(p.getOpenedAt(), p.getClosedAt()).toMinutes())
                    .average().orElse(0);
            sb.append(String.format("  평균 보유: %.0f분%n", avgHold));

            Map<String, Long> byHash = counted(closed,
                    p -> p.getRulesetHash() == null ? "미상" : p.getRulesetHash());
            if (byHash.size() > 1) {
                sb.append("  ⚠️ 규칙 지문이 ").append(byHash.size())
                  .append("종 섞여 있다 — 서로 다른 규칙의 결과라 한 표본으로 합산하면 안 된다: ")
                  .append(fmtCounts(byHash)).append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendSampleLogs(StringBuilder sb, List<StrategyLogEntity> logs) {
        if (logs.isEmpty()) return;
        // 판단 근거를 눈으로 확인할 수 있게 최근 원문 몇 건. 통계 대체용이 아니다.
        sb.append("[최근 로그 원문 표본 ").append(Math.min(SAMPLE_LOG_COUNT, logs.size())).append("건]\n");
        logs.stream().limit(SAMPLE_LOG_COUNT).forEach(l -> {
            sb.append("  ").append(KST.format(l.getCreatedAt()))
              .append(' ').append(l.getCoinPair())
              .append(' ').append(l.getSignal());
            if (l.getMarketRegime() != null) sb.append('/').append(l.getMarketRegime());
            if (l.getReason() != null) sb.append(" — ").append(trim(l.getReason(), 160));
            if (l.getBlockedReason() != null && !l.getBlockedReason().isBlank()) {
                sb.append(" [차단: ").append(trim(l.getBlockedReason(), 80)).append(']');
            }
            sb.append('\n');
        });
    }

    // ── 전송 ──────────────────────────────────────────────────────────────────

    /** 텔레그램은 메시지당 4096자 한도라 길면 나눠 보낸다 — 잘려서 결론이 사라지면 안 된다. */
    private void sendChunked(String text) {
        if (text.length() <= TELEGRAM_CHUNK) {
            telegramService.sendCustomNotification(text);
            return;
        }
        int total = (text.length() + TELEGRAM_CHUNK - 1) / TELEGRAM_CHUNK;
        for (int i = 0, part = 1; i < text.length(); i += TELEGRAM_CHUNK, part++) {
            String chunk = text.substring(i, Math.min(text.length(), i + TELEGRAM_CHUNK));
            telegramService.sendCustomNotification(String.format("(%d/%d)\n%s", part, total, chunk));
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 화면·알림에서 세션을 가리키는 이름. session_kind 어휘를 사람이 읽는 말로 옮긴다. */
    public static String sessionLabel(String sessionType, Long sessionId) {
        String kind = switch (sessionType == null ? "" : sessionType) {
            case "DYNAMIC"   -> "동적";
            case "DYN_PAPER" -> "동적(모의)";
            case "LIVE"      -> "실전";
            case "PAPER"     -> "모의";
            default          -> sessionType;
        };
        return kind + " 세션 #" + sessionId;
    }

    private static boolean isPaperKind(String sessionType) {
        return "DYN_PAPER".equals(sessionType) || "PAPER".equals(sessionType);
    }

    private static <T> Map<String, Long> counted(List<T> items, java.util.function.Function<T, String> key) {
        return items.stream().collect(Collectors.groupingBy(
                t -> {
                    String k = key.apply(t);
                    return k == null ? "미상" : k;
                },
                LinkedHashMap::new, Collectors.counting()));
    }

    private static Map<String, Long> top(Map<String, Long> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private static String fmtCounts(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> trim(e.getKey(), 60) + " " + e.getValue() + "건")
                .collect(Collectors.joining(", "));
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 3, RoundingMode.HALF_UP);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

}
