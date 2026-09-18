package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code candle_data} 자동 갱신 — 2026-09-14 신설.
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code candle_data} 는 <b>배치 수집 API 가 호출될 때만</b> 채워진다. 스케줄러가 없어서
 * 아무도 수집을 돌리지 않으면 그대로 멈춰 있다. 2026-09-14 실측:</p>
 *
 * <pre>
 *   market_data_cache (매매용)   최종 2026-09-13   ← 매 틱 갱신되므로 항상 최신
 *   candle_data       (백테스트용) 최종 2026-09-07   ← 일주일 정지
 * </pre>
 *
 * <p><b>백테스트·Walk Forward 는 {@code candle_data} 를 읽는다.</b> 그래서 그대로 두면
 * WF 가 <b>최근 구간을 통째로 못 보고</b> 판정을 낸다 — 하필 그 일주일은 시장이 4% 빠진
 * 구간이었다. 게다가 조용히 그렇게 된다: 에러도, 경고도 없고 결과는 그럴듯하게 나온다.</p>
 *
 * <p>같은 문제를 2026-08-24 에 이미 겪었다 — 48조합 중 25개가 데이터 부족으로 조용히
 * 누락됐다({@code scripts/backfill_candle_data.sh} 주석 참조). 그때는 수동 백필로 메웠고,
 * 이 스케줄러가 그 수동 작업을 없앤다.</p>
 *
 * <h3>레이트리밋을 건드리지 않는 설계</h3>
 * <p>2026-09-08 의 교훈: 수집 실패를 가른 것은 타임프레임이 아니라 <b>코인당 요청 사슬 길이</b>
 * 였다(M15 822회 → 6/6 실패, H1 206회 → 8/8 성공). {@code UpbitCandleCollector.fetchCandles}
 * 의 수집 루프가 all-or-nothing 이라 822회 중 1회만 실패해도 전부 버린다.</p>
 *
 * <p>그래서 이 스케줄러는:</p>
 * <ul>
 *   <li><b>갭만</b> 요청한다 — 마지막 캔들 이후부터 오늘까지. 매일 돌면 하루치(M15 96개 = 1요청)다.</li>
 *   <li>갭이 {@value #MAX_GAP_CHUNK_DAYS} 일을 넘으면 <b>그 길이로 잘라 여러 번</b> 요청한다
 *       (2026-09-18 변경). 종전에는 건너뛰고 경고만 했는데, <b>갭은 매일 커지기만 하므로
 *       한 번 한계를 넘으면 영원히 돌아오지 못했다</b> — 09-18 실측에서 대상 8종 중 6종이
 *       이미 그 상태였고 30종이 08-30 에 멈춰 있었다. 사슬 길이를 짧게 유지한다는 원래 목적은
 *       "요청을 안 한다"가 아니라 "한 번에 길게 안 한다"로 달성한다.</li>
 *   <li>수집 사이에 {@code candle-freshness.spacing-seconds} 초를 둔다. 함대 52세션이 같은 Upbit
 *       초당 10회 예산을 나눠 쓴다. 한 실행의 총 요청 수는 {@value #MAX_REQUESTS_PER_RUN} 로
 *       막는다 — 갭 분할을 넣은 뒤 밀린 코인이 많으면 실행이 끝없이 길어질 수 있다.</li>
 * </ul>
 *
 * <h3>대상 코인 — 하드코딩이 아니라 실제 감시 기록에서 뽑는다 (2026-09-18)</h3>
 * <p>종전에는 8종이 설정 기본값으로 박혀 있었다. 그래서 <b>동적 워치리스트 코인이 통째로
 * 빠졌다</b> — NEAR 는 60일간 3,885회 평가됐는데 캔들이 0건이었고, ONDO·TRUMP·ENA·MIRA·WLFI 는
 * 몇 주씩 밀려 있었다. <b>감시는 하면서 검증할 수단이 없는 상태</b>였다.</p>
 * <p>워치리스트는 메모리에서 계산되고 테이블에 남지 않으므로, 유일한 영속 기록인
 * {@code strategy_log} 의 최근 평가 이력을 대상으로 삼는다. 설정값은 <b>하한</b>으로만
 * 남긴다(로그가 비어도 핵심 코인은 갱신되도록).</p>
 *
 * <p>새벽에 돈다 — 매매 틱이 가장 한가한 시간대다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleDataFreshnessScheduler {

    /** 한 요청이 덮는 최대 일수. 이보다 긴 갭은 이 길이로 <b>잘라서 여러 번</b> 요청한다. */
    private static final int MAX_GAP_CHUNK_DAYS = 7;

    /** 한 실행의 총 요청 상한 — 밀린 코인이 많아도 실행이 무한정 길어지지 않게 한다. */
    private static final int MAX_REQUESTS_PER_RUN = 40;

    /** 감시 기록을 얼마나 거슬러 볼 것인가. */
    private static final int WATCHED_LOOKBACK_DAYS = 30;

    /** 이 횟수 미만 평가된 코인은 대상에서 뺀다 — 한두 번 스쳐 간 코인까지 매일 받지 않는다. */
    private static final long WATCHED_MIN_EVALS = 100;

    /** 최근 이 일수 이내면 최신으로 본다 — 매일 1일짜리 꼬리 요청이 반복되는 것을 막는다. */
    private static final int FRESH_TOLERANCE_DAYS = 1;


    private final CandleDataRepository candleDataRepository;
    private final StrategyLogRepository strategyLogRepository;
    private final DataCollectionService dataCollectionService;
    private final TelegramNotificationService telegramNotificationService;

    /** 갱신 대상의 <b>하한</b> — 감시 기록이 비어도 이 코인들은 항상 갱신한다. 쉼표로 구분. */
    @Value("${candle-freshness.coins:KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-ADA,KRW-DOGE,KRW-LINK,KRW-AVAX}")
    private String coinsCsv;

    @Value("${candle-freshness.timeframes:H1,M15}")
    private String timeframesCsv;

    /** 수집 요청 사이 간격(초) — 운영 함대와 레이트리밋 예산을 나눠 쓴다. 테스트는 0 으로 둔다. */
    @Value("${candle-freshness.spacing-seconds:45}")
    private int spacingSeconds;

    /** 기본 활성. 끄려면 {@code candle-freshness.enabled=false}. */
    @Value("${candle-freshness.enabled:true}")
    private boolean enabled;

    /** 매일 04:10 KST (UTC 19:10) — 매매 틱이 가장 한가한 시간대. */
    @Scheduled(cron = "0 10 19 * * *")
    public void refreshDaily() {
        if (!enabled) {
            log.info("[CandleFreshness] 비활성 상태 — 건너뜀");
            return;
        }
        refreshNow();
    }

    /**
     * 갭을 찾아 채운다. 스케줄과 무관하게 수동 호출해도 안전하다 — <b>멱등</b>하며,
     * 채울 갭이 없으면 아무 요청도 하지 않는다.
     */
    public void refreshNow() {
        List<String> coins = resolveTargetCoins();
        List<String> timeframes = split(timeframesCsv);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // (coin|tf) → 마지막 캔들 시각
        Map<String, Instant> lastSeen = new HashMap<>();
        for (Object[] row : candleDataRepository.findDataSummary()) {
            if (row[3] instanceof Instant max) {
                lastSeen.put(row[0] + "|" + row[1], max);
            }
        }

        List<String> needsBackfill = new ArrayList<>();
        int requested = 0;
        boolean hitRequestCap = false;

        outer:
        for (String tf : timeframes) {
            for (String coin : coins) {
                Instant last = lastSeen.get(coin + "|" + tf);
                if (last == null) {
                    // 이력이 아예 없는 조합은 하루치만 당겨서는 의미가 없다 — 백필 스크립트 몫이다.
                    // 감시는 하는데 캔들이 0건인 코인이 여기 걸린다(09-18 실측: NEAR·KAITO·SHIB 등).
                    needsBackfill.add(coin + " " + tf + " (이력 없음)");
                    continue;
                }
                long gapDays = Duration.between(last, Instant.now()).toDays();
                if (gapDays <= FRESH_TOLERANCE_DAYS) {
                    continue; // 최신
                }

                // 긴 갭은 잘라서 여러 번 요청한다. 종전에는 여기서 건너뛰었는데, 갭은 매일
                // 커지기만 하므로 한 번 한계를 넘으면 영원히 돌아오지 못했다.
                LocalDate cursor = last.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                while (cursor.isBefore(today)) {
                    if (requested >= MAX_REQUESTS_PER_RUN) {
                        hitRequestCap = true;
                        break outer;
                    }
                    LocalDate chunkEnd = cursor.plusDays(MAX_GAP_CHUNK_DAYS);
                    if (chunkEnd.isAfter(today)) chunkEnd = today;

                    log.info("[CandleFreshness] 갭 수집: {} {} {} ~ {} (남은 갭 {}일)",
                            coin, tf, cursor, chunkEnd, gapDays);
                    dataCollectionService.collectCandles(coin, tf, cursor, chunkEnd);
                    requested++;

                    cursor = chunkEnd;
                    sleepBetweenRequests();
                }
            }
        }

        log.info("[CandleFreshness] 대상 {}코인 × {}타임프레임 · 수집 요청 {}건 · 백필 필요 {}건{}",
                coins.size(), timeframes.size(), requested, needsBackfill.size(),
                hitRequestCap ? " (요청 상한 도달 — 나머지는 다음 실행에서)" : "");

        // 로그만 남기면 아무도 보지 않는다. 09-14 에 같은 문제를 고치고도 09-18 에 30종이
        // 08-30 에 멈춰 있었던 이유가 정확히 이것이다 — 조용히 실패했다.
        if (!needsBackfill.isEmpty()) {
            String msg = String.format(
                    "⚠️ 캔들 수집 불가 %d건 — 평가는 하는데 캔들 이력이 아예 없습니다.%n%s%n%n"
                            + "백테스트·WF 가 이 코인을 검증할 수 없습니다. 수동 백필이 필요합니다:%n"
                            + "bash scripts/backfill_candle_data.sh",
                    needsBackfill.size(), String.join(", ", needsBackfill));
            log.warn("[CandleFreshness] {}", msg);
            notify(msg);
        }
        if (hitRequestCap) {
            notify(String.format(
                    "⚠️ 캔들 갱신이 한 실행 요청 상한(%d건)에 걸렸습니다. 밀린 구간이 많다는 뜻입니다 — "
                            + "며칠 연속 이 알림이 오면 수동 백필을 고려하세요.", MAX_REQUESTS_PER_RUN));
        }
    }

    /**
     * 갱신 대상 코인 — <b>설정 하한 ∪ 최근 실제 감시 기록</b>.
     *
     * <p>하드코딩 목록만 쓰면 동적 워치리스트가 통째로 빠진다(09-18 에 실제로 그랬다).
     * 반대로 로그만 쓰면 로그가 비었을 때 아무것도 안 받는다. 둘의 합집합으로 둔다.</p>
     */
    private List<String> resolveTargetCoins() {
        Set<String> coins = new LinkedHashSet<>(split(coinsCsv));
        try {
            coins.addAll(strategyLogRepository.findRecentlyWatchedCoins(
                    Instant.now().minus(Duration.ofDays(WATCHED_LOOKBACK_DAYS)), WATCHED_MIN_EVALS));
        } catch (Exception e) {
            // 감시 기록 조회가 실패해도 설정 하한으로는 계속 돈다 — 갱신이 통째로 멈추는 것보다 낫다.
            log.warn("[CandleFreshness] 감시 기록 조회 실패 — 설정 목록만 사용한다", e);
        }
        return List.copyOf(coins);
    }

    private void notify(String message) {
        try {
            telegramNotificationService.sendCustomNotification(message);
        } catch (Exception e) {
            log.warn("[CandleFreshness] 알림 전송 실패", e);
        }
    }

    private void sleepBetweenRequests() {
        if (spacingSeconds <= 0) return;
        try {
            Thread.sleep(spacingSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
