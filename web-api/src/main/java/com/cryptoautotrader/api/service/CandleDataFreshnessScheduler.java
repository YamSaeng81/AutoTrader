package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.CandleDataRepository;
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
import java.util.List;
import java.util.Map;

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
 *   <li>갭이 {@value #MAX_GAP_DAYS} 일을 넘으면 <b>건너뛰고 경고만</b> 한다. 긴 구간은
 *       {@code scripts/revalidate_walk_forward_0908.sh} 의 STEP=1(연 단위 분할)이 담당한다 —
 *       여기서 무리하게 당기면 운영 함대와 예산을 다투다 전부 실패한다.</li>
 *   <li>수집 사이에 {@value #SPACING_SECONDS} 초를 둔다. 함대 52세션이 같은 Upbit
 *       초당 10회 예산을 나눠 쓴다.</li>
 * </ul>
 *
 * <p>새벽에 돈다 — 매매 틱이 가장 한가한 시간대다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleDataFreshnessScheduler {

    /** 이 일수를 넘는 갭은 건너뛴다 — 긴 구간은 연 단위로 쪼개는 백필 스크립트가 맡는다. */
    private static final int MAX_GAP_DAYS = 7;

    /** 최근 이 일수 이내면 최신으로 본다 — 매일 1일짜리 꼬리 요청이 반복되는 것을 막는다. */
    private static final int FRESH_TOLERANCE_DAYS = 1;

    /** 수집 요청 사이 간격(초) — 운영 함대와 레이트리밋 예산을 나눠 쓴다. */
    private static final int SPACING_SECONDS = 45;

    private final CandleDataRepository candleDataRepository;
    private final DataCollectionService dataCollectionService;

    /** 갱신 대상 — 운영 함대가 실제로 쓰는 조합만. 쉼표로 구분. */
    @Value("${candle-freshness.coins:KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-ADA,KRW-DOGE,KRW-LINK,KRW-AVAX}")
    private String coinsCsv;

    @Value("${candle-freshness.timeframes:H1,M15}")
    private String timeframesCsv;

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
        List<String> coins = split(coinsCsv);
        List<String> timeframes = split(timeframesCsv);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // (coin|tf) → 마지막 캔들 시각
        Map<String, Instant> lastSeen = new HashMap<>();
        for (Object[] row : candleDataRepository.findDataSummary()) {
            if (row[3] instanceof Instant max) {
                lastSeen.put(row[0] + "|" + row[1], max);
            }
        }

        List<String> skipped = new ArrayList<>();
        int requested = 0;

        for (String tf : timeframes) {
            for (String coin : coins) {
                Instant last = lastSeen.get(coin + "|" + tf);
                if (last == null) {
                    // 이력이 아예 없는 조합은 하루치만 당겨서는 의미가 없다 — 백필 스크립트 몫이다.
                    skipped.add(coin + " " + tf + " (이력 없음)");
                    continue;
                }
                LocalDate from = last.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                long gapDays = Duration.between(last, Instant.now()).toDays();

                if (gapDays <= FRESH_TOLERANCE_DAYS) {
                    continue; // 최신
                }
                if (gapDays > MAX_GAP_DAYS) {
                    // 무리하게 당기면 요청 사슬이 길어져 all-or-nothing 수집이 통째로 실패한다.
                    skipped.add(String.format("%s %s (갭 %d일 > %d일)", coin, tf, gapDays, MAX_GAP_DAYS));
                    continue;
                }

                log.info("[CandleFreshness] 갭 수집: {} {} {} ~ {} ({}일)", coin, tf, from, today, gapDays);
                dataCollectionService.collectCandles(coin, tf, from, today);
                requested++;

                sleepBetweenRequests();
            }
        }

        if (requested == 0 && skipped.isEmpty()) {
            log.info("[CandleFreshness] 전 조합 최신 — 요청 없음");
        } else {
            log.info("[CandleFreshness] 수집 요청 {}건, 건너뜀 {}건{}",
                    requested, skipped.size(),
                    skipped.isEmpty() ? "" : " — " + String.join(", ", skipped));
        }
        if (!skipped.isEmpty()) {
            log.warn("[CandleFreshness] 건너뛴 조합은 수동 백필이 필요하다: "
                    + "STEP=1 bash scripts/revalidate_walk_forward_0908.sh");
        }
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(SPACING_SECONDS * 1000L);
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
