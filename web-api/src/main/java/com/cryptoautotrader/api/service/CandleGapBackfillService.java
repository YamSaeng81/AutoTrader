package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>판정 창 안의 결측 재수집</b> (2026-10-19 운영 관측 후속).
 *
 * <p>동기화({@link MarketDataSyncService})는 <b>마지막 저장 봉 이후</b>만 다시 받고, 전량 재수집은 가장 오래된 봉이 창을 덮는지만 본다.
 * 그래서 마지막 봉보다 앞에 생긴 구멍(수집 중 일부 페이지 유실 등)은 다시 받지 않는다 — 결측이 판정 창에서 밀려날 때까지
 * {@link CandleFreshnessGuard} 가 판정 불가를 낸다(M15 약 5일, H1 약 20일).</p>
 *
 * <p>이 작업은 캐시의 최근 {@link #WINDOW_CANDLES} 칸에서 <b>저장된 첫 봉과 마지막 봉 사이의 빈 칸</b>을 찾아 거래소에서 그 구간을 다시
 * 받는다. 받은 봉 중 <b>빈 칸에 해당하는 것만</b> 저장한다 — 이미 있는 봉은 바꾸지 않는다. 다시 받아도 없는 칸은 <b>조회 시점에
 * 거래소에도 없던 칸</b>(체결 없음·거래소 점검)으로 기록한다. 2026-10-19 업비트 점검 구간이 그 예다 — 업비트 자신에게 봉이 없어
 * 재수집으로 메워지지 않는다.</p>
 *
 * <p>🔴 이 기록은 <b>영구 확정이 아니다</b>(리뷰 2026-10-19). API 조회가 성공했다고 그 뒤에도 봉이 추가되지 않는다는 보장은 없다.
 * 최근 {@link #RECENT_HORIZON} 안의 칸은 {@link #RECHECK_INTERVAL} 마다 최대 {@link #MAX_CHECKS} 회까지 다시 묻는다. 그보다 오래된 칸은
 * 프로세스마다 한 번 묻는다(재기동하면 기록이 비어 다시 묻는다). 기록은 {@link CandleFreshnessGuard} 가 공식 점검 구간과 <b>함께</b>
 * 볼 때만 쓰인다 — 점검 밖의 "거래소에도 없음" 은 결측 규칙을 바꾸지 않는다.</p>
 *
 * <p>🔴 REST 호출은 트랜잭션·DB 잠금 밖에서 한다(이 메서드는 트랜잭션이 아니다). 저장만 {@link MarketDataUpsertService} 의 짧은
 * 트랜잭션이다. 수집이 실패하면 그 (코인, 타임프레임) 은 {@link #RETRY_AFTER_FAILURE} 뒤에 다시 시도한다.</p>
 *
 * <p>🔴 이 작업이 채운 봉은 같은 캐시를 읽는 모든 엔진(검증 66세션 포함)의 입력이 된다 — 운영 적용은 개입 기록에 넣는다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleGapBackfillService {

    /** 찾는 범위 — 동기화 범위(520)와 같다. 소비 측 창(500)을 덮는다. */
    static final int WINDOW_CANDLES = 520;
    /** 수집 실패 뒤 다시 시도하는 간격. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    /** 이보다 최근 칸은 "거래소에도 없음" 으로 기록하지 않는다 — 거래소 반영 지연 여유(봉 2개). */
    static final int CONFIRM_MIN_AGE_CANDLES = 2;
    /** 이 안의 칸은 "거래소에도 없음" 기록 뒤에도 제한된 재확인을 한다. */
    static final Duration RECENT_HORIZON = Duration.ofHours(6);
    static final Duration RECHECK_INTERVAL = Duration.ofMinutes(30);
    static final int MAX_CHECKS = 4;

    private final MarketDataCacheRepository repo;
    private final MarketDataUpsertService upsertService;

    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    /** 조회 시점에 거래소에도 없던 칸의 기록 — 처음·마지막 조회 시각과 조회 횟수. */
    record AbsentCheck(Instant firstCheckedAt, Instant lastCheckedAt, int checks) {
    }

    /** 코인:타임프레임:칸 시작(epoch 초) → 조회 기록. */
    private final Map<String, AbsentCheck> absentChecks = new ConcurrentHashMap<>();
    /** 코인:타임프레임 → 마지막 수집 실패 시각. */
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    /** 한 회차의 결과 — 시험·로그용. */
    record Result(int missing, int filled, int absentAtCheck, boolean fetchFailed, boolean waitingRetry) {
        static Result none() {
            return new Result(0, 0, 0, false, false);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void backfillActivePairs() {
        if (upbitRestClient == null) return;
        Instant now = Instant.now();
        for (Object[] p : repo.findActivePairs(now.minus(1, ChronoUnit.DAYS))) {
            try {
                backfill((String) p[0], (String) p[1], now);
            } catch (Exception e) {
                log.error("[결측 재수집] {} {} 실패: {}", p[0], p[1], e.getMessage());
            }
        }
        prune(now);
    }

    /** 한 (코인, 타임프레임) 의 창 안 결측을 다시 받는다. */
    Result backfill(String coinPair, String timeframe, Instant now) {
        if (upbitRestClient == null) return Result.none();
        long tfSec = TimeframeUtils.toMinutes(timeframe) * 60L;
        Instant windowFrom = now.minusSeconds(WINDOW_CANDLES * tfSec);
        List<MarketDataCacheEntity> stored = repo.findCandles(coinPair, timeframe, windowFrom, now);
        if (stored.size() < 2) return Result.none();

        Set<Long> have = new HashSet<>();
        stored.forEach(c -> have.add(c.getTime().getEpochSecond()));
        long first = stored.get(0).getTime().getEpochSecond();
        long last = stored.get(stored.size() - 1).getTime().getEpochSecond();
        String pairKey = coinPair + ":" + timeframe;

        TreeSet<Long> missing = new TreeSet<>();
        for (long t = first + tfSec; t < last; t += tfSec) {
            if (!have.contains(t) && dueForQuery(absentChecks.get(pairKey + ":" + t), t, now)) missing.add(t);
        }
        if (missing.isEmpty()) return Result.none();

        Instant failedAt = lastFailure.get(pairKey);
        if (failedAt != null && failedAt.plus(RETRY_AFTER_FAILURE).isAfter(now)) {
            log.debug("[결측 재수집] {} 결측 {}칸 — 직전 수집 실패({}) 뒤 재시도 대기", pairKey, missing.size(), failedAt);
            return new Result(missing.size(), 0, 0, false, true);
        }

        // ── 수집: 트랜잭션 밖. 빈 칸의 처음부터 끝까지 한 번에(페이지 나눔은 수집기가 한다).
        Instant from = Instant.ofEpochSecond(missing.first());
        Instant to = Instant.ofEpochSecond(missing.last() + tfSec);
        List<Candle> fetched;
        try {
            fetched = new UpbitCandleCollector(upbitRestClient).fetchCandles(coinPair, timeframe, from, to);
        } catch (RuntimeException e) {
            lastFailure.put(pairKey, now);
            log.warn("[결측 재수집] {} 결측 {}칸({} ~ {}) 수집 실패 — {}분 뒤 다시 시도: {}", pairKey, missing.size(), from,
                    Instant.ofEpochSecond(missing.last()), RETRY_AFTER_FAILURE.toMinutes(), e.getMessage());
            return new Result(missing.size(), 0, 0, true, false);
        }
        lastFailure.remove(pairKey);

        // 빈 칸에 해당하는 봉만 — 이미 있는 봉은 바꾸지 않는다.
        List<MarketDataCacheEntity> fill = new ArrayList<>();
        Set<Long> filled = new HashSet<>();
        for (Candle c : fetched) {
            long t = c.getTime().getEpochSecond();
            if (!missing.contains(t) || !filled.add(t)) continue;
            fill.add(MarketDataCacheEntity.builder().time(c.getTime()).coinPair(coinPair).timeframe(timeframe)
                    .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume()).build());
        }
        if (!fill.isEmpty()) upsertService.upsert(coinPair, timeframe, fill);

        // 다시 받아도 없는 칸 — 조회 시점에 거래소에도 없다. 너무 최근 칸은 기록하지 않는다(다음 회차에 다시 본다).
        long confirmBefore = now.getEpochSecond() - CONFIRM_MIN_AGE_CANDLES * tfSec;
        List<Long> absent = new ArrayList<>();
        for (long t : missing) {
            String key = pairKey + ":" + t;
            if (filled.contains(t)) {
                absentChecks.remove(key);
                continue;
            }
            if (t >= confirmBefore) continue;
            absentChecks.merge(key, new AbsentCheck(now, now, 1),
                    (old, n) -> new AbsentCheck(old.firstCheckedAt(), now, old.checks() + 1));
            absent.add(t);
        }
        if (!fill.isEmpty()) {
            log.warn("[결측 재수집] {} 결측 {}칸 중 {}칸을 거래소에서 다시 받아 채웠다 ({} ~ {})", pairKey, missing.size(), fill.size(),
                    from, Instant.ofEpochSecond(missing.last()));
        }
        if (!absent.isEmpty()) {
            log.info("[결측 재수집] {} {}칸은 조회 시점({})에 거래소에도 봉이 없다(체결 없음·거래소 점검): {}", pairKey, absent.size(),
                    now, describe(absent, tfSec));
        }
        return new Result(missing.size(), fill.size(), absent.size(), false, false);
    }

    /**
     * 이 칸을 거래소에 (다시) 물어야 하는가 — 기록이 없으면 묻는다. 기록이 있으면 최근 칸만, 간격·횟수 한도 안에서 다시 묻는다.
     */
    static boolean dueForQuery(AbsentCheck check, long slotEpoch, Instant now) {
        if (check == null) return true;
        boolean recent = Instant.ofEpochSecond(slotEpoch).isAfter(now.minus(RECENT_HORIZON));
        return recent && check.checks() < MAX_CHECKS && !check.lastCheckedAt().plus(RECHECK_INTERVAL).isAfter(now);
    }

    /**
     * 이 칸이 <b>마지막 조회 시점에</b> 거래소에도 없었는가. 판정 관문이 공식 점검 구간과 함께 볼 때 쓴다.
     *
     * @return 조회 기록, 없으면 {@code null}(묻지 않았거나 그 뒤 채워졌다)
     */
    public AbsentCheck absentAtLastCheck(String coinPair, String timeframe, Instant slotStart) {
        return absentChecks.get(coinPair + ":" + timeframe + ":" + slotStart.getEpochSecond());
    }

    /** 창에서 밀려난 기록을 지운다. */
    private void prune(Instant now) {
        absentChecks.keySet().removeIf(k -> {
            String[] parts = k.split(":");
            long t = Long.parseLong(parts[parts.length - 1]);
            long tfSec = TimeframeUtils.toMinutes(parts[parts.length - 2]) * 60L;
            return t < now.getEpochSecond() - WINDOW_CANDLES * tfSec;
        });
    }

    /** 연속 칸을 구간으로 묶어 적는다 — "시작 ~ 끝(n칸)". */
    static String describe(List<Long> slots, long tfSec) {
        List<String> out = new ArrayList<>();
        long start = slots.get(0);
        long prev = start;
        for (int i = 1; i <= slots.size(); i++) {
            if (i < slots.size() && slots.get(i) == prev + tfSec) {
                prev = slots.get(i);
                continue;
            }
            out.add(Instant.ofEpochSecond(start) + " ~ " + Instant.ofEpochSecond(prev) + "(" + ((prev - start) / tfSec + 1) + "칸)");
            if (i < slots.size()) {
                start = slots.get(i);
                prev = start;
            }
        }
        return String.join(", ", out);
    }
}
