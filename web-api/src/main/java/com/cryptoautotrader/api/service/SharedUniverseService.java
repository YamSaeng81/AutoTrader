package com.cryptoautotrader.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전 세션 공용 종목 유니버스 — 전략 비교를 성립시키기 위한 장치.
 *
 * <p><b>왜 필요한가 (2026-09-07 운영 DB 실측)</b>: 세션마다
 * {@link DynamicTradingService#resolveWatchlist} 가 <b>각자의 갱신 시계</b>로 워치리스트를 따로
 * 만들고 있었다. 그 결과 같은 전략·같은 타임프레임 세션들끼리도 서로 다른 코인을 보게 되고,
 * 성적 차이가 "전략이 좋아서"인지 "그날 그 세션이 잡은 코인이 좋아서"인지 분리되지 않았다.
 *
 * <p>실측: 같은 코인·±1시간에 둘 이상의 전략이 BUY 를 낸 건만 골라 코인·타이밍을 통제하니
 * 전략 순위가 무너졌다 — MTF_CONFIRMED 24h 사후수익 −1.25% → −0.16%,
 * <b>MTF_BTC 는 −0.48% → +1.21% 로 부호까지 뒤집혔다.</b> 코인·일자 조합의 38.9%만
 * 다중 세션이 겹쳤고, 나머지는 비교 자체가 불가능한 표본이었다.
 *
 * <h3>어떻게 고정하는가</h3>
 * <ol>
 *   <li><b>벽시계 버킷</b>: 세션별 갱신 시각 대신 {@code epochSecond / (refreshMin*60)} 로
 *       끊는다. 세션들이 서로 다른 시각에 tick 해도 같은 버킷이면 <b>같은 스냅샷</b>을 받는다.
 *       세션별 시계를 쓰면 1분 차이로 다른 유니버스가 나와 통제가 깨진다.</li>
 *   <li><b>기준 동일성</b>: 캐시 키에 필터 파라미터를 전부 넣는다. 설정이 다른 세션은 애초에
 *       비교 대상이 아니므로 유니버스를 공유하면 안 된다.</li>
 *   <li><b>타임프레임 강제</b>({@code force-timeframe}): 비우면 타임프레임별로 1개씩
 *       (H1 유니버스, M15 유니버스)이라 <b>타임프레임 간</b> 비교는 여전히 교란된다.
 *       값을 주면 전 세션이 그 타임프레임으로 유니버스를 만들어 함대 전체가 <b>단일 유니버스</b>가
 *       된다. ATR 하한은 {@code normalizeAtrPct} 가 타임프레임에 비례해 환산하므로
 *       강제해도 변동성 기준의 의미는 유지된다.</li>
 * </ol>
 *
 * <p><b>되돌리기</b>: {@code trading.shared-universe.enabled: false} 한 줄이면 세션별 워치리스트
 * (2026-09-07 이전 동작)로 복귀한다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SharedUniverseService {

    private final WatchlistFilterService watchlistFilterService;

    @Value("${trading.shared-universe.enabled:true}")
    private boolean enabled;

    /**
     * 유니버스 구성에 쓸 타임프레임. 비우면 세션 자신의 타임프레임을 쓴다(= 타임프레임별 유니버스).
     * 값을 주면 함대 전체가 단일 유니버스를 공유한다.
     */
    @Value("${trading.shared-universe.force-timeframe:}")
    private String forceTimeframe;

    /**
     * 버킷 폭(분) — 함대 <b>공통</b> 갱신 주기. 세션의 {@code watchlist_refresh_min} 은 쓰지 않는다.
     *
     * <p><b>왜 세션 값을 안 쓰나 (2026-09-07 운영 확인)</b>: 처음엔 버킷 폭을 세션의 갱신 주기로
     * 잡았는데, 운영 함대가 30분(8세션)/60분(6세션) 두 그룹으로 갈려 있어 <b>같은 순간에도 서로
     * 다른 버킷 번호</b>가 나왔다. 그 결과 유니버스가 둘로 쪼개져 통제가 절반만 됐다
     * (실측: 30분 그룹 86·81 은 같은 목록, 60분 그룹 89 는 다른 목록).
     * 공유의 목적이 "전 세션이 같은 코인을 본다"인 이상 버킷은 함대 전체에 하나여야 한다.</p>
     */
    @Value("${trading.shared-universe.refresh-minutes:30}")
    private int refreshMinutes;

    /** 버킷 + 필터 기준이 같으면 같은 유니버스. */
    private record UniverseKey(
            long bucket, int maxCandidates, int targetSize,
            String minAtrPct, String maxSpreadPct, String timeframe, String criteria) {}

    private final Map<UniverseKey, List<String>> cache = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    /** 유니버스 구성에 실제로 쓰이는 타임프레임 — 강제값이 있으면 그것, 없으면 세션 것. */
    public String effectiveTimeframe(String sessionTimeframe) {
        return (forceTimeframe == null || forceTimeframe.isBlank())
                ? sessionTimeframe : forceTimeframe.trim().toUpperCase();
    }

    /**
     * 이번 버킷의 공용 유니버스를 반환한다. 같은 버킷·같은 기준의 첫 호출만 실제로 구성하고,
     * 나머지 세션은 그 결과를 그대로 받는다.
     *
     * <p>버킷 폭은 {@code trading.shared-universe.refresh-minutes} 하나로 정해진다 —
     * 세션별 {@code watchlist_refresh_min} 은 의도적으로 무시한다. 이유는 해당 필드 주석 참조.</p>
     */
    public List<String> resolve(int maxCandidates, int targetSize,
                                BigDecimal minAtrPct, BigDecimal maxSpreadPct,
                                String sessionTimeframe,
                                WatchlistFilterService.QualityCriteria criteria) {

        String timeframe = effectiveTimeframe(sessionTimeframe);
        long bucketWidthSec = (refreshMinutes > 0 ? refreshMinutes : 30) * 60L;
        long bucket = Instant.now().getEpochSecond() / bucketWidthSec;

        UniverseKey key = new UniverseKey(
                bucket, maxCandidates, targetSize,
                Objects.toString(minAtrPct, ""), Objects.toString(maxSpreadPct, ""),
                timeframe, Objects.toString(criteria, ""));

        // 지난 버킷 정리 — 캐시가 무한히 자라지 않게 한다.
        cache.keySet().removeIf(k -> k.bucket() < bucket);

        List<String> universe = cache.computeIfAbsent(key, k -> {
            List<String> fresh = watchlistFilterService.buildWatchlist(
                    maxCandidates, targetSize, minAtrPct, maxSpreadPct, timeframe, criteria);
            log.info("[Universe] 공용 유니버스 구성 (bucket={}, tf={}, {}종목)={}",
                    bucket, timeframe, fresh.size(), fresh);
            return fresh;
        });

        // 빈 결과는 캐시하지 않는다 — Upbit 일시 장애로 빈 목록이 나오면 그 버킷 내내
        // (최대 refreshMin 분) 함대 전체가 스캔을 못 한다. 다음 세션이 다시 시도하게 둔다.
        if (universe.isEmpty()) {
            cache.remove(key);
        }
        return universe;
    }
}
