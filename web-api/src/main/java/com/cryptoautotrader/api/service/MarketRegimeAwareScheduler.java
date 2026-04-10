package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.entity.RegimeChangeLogEntity;
import com.cryptoautotrader.api.entity.StrategyConfigEntity;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.api.repository.RegimeChangeLogRepository;
import com.cryptoautotrader.api.repository.StrategyConfigRepository;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.regime.MarketRegimeFilter;
import com.cryptoautotrader.strategy.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 시장 상태(MarketRegime) 감지 결과에 따라 전략을 자동 활성/비활성하는 스케줄러.
 *
 * <p>동작 방식:
 * <ol>
 *   <li>1시간마다 {@link MarketRegimeDetector}를 실행해 현재 시장 상태를 감지한다.</li>
 *   <li>DB에서 {@code manual_override = false} 인 전략 설정 목록을 조회한다.</li>
 *   <li>{@link MarketRegimeFilter} 매핑 테이블을 참조해 각 전략을 활성/비활성 처리한다.</li>
 *   <li>변경이 발생한 전략은 로그에 기록한다.</li>
 *   <li>레짐 전환이 발생하면 {@code regime_change_log} 테이블에 이력을 저장한다.</li>
 * </ol>
 *
 * <p>수동 오버라이드:
 * 사용자가 {@code PATCH /api/v1/strategies/{id}/toggle} API를 호출하면
 * {@code manual_override = true} 가 설정되어 자동 스위칭 대상에서 제외된다.
 * 이후 {@code PATCH /api/v1/strategies/{id}/toggle-override} 로 해제 가능하다.
 */
@Service
public class MarketRegimeAwareScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeAwareScheduler.class);

    /** MarketRegimeDetector.MIN_CANDLE_COUNT = 50, BB_LOOKBACK = 30 — 여유분 포함 */
    private static final int REGIME_CANDLE_COUNT = 80;

    /**
     * 시장 상태 감지 기준 코인/타임프레임.
     * KRW-BTC 1시간봉을 대표 지표로 사용한다.
     */
    private static final String REGIME_COIN_PAIR = "KRW-BTC";
    private static final String REGIME_TIMEFRAME  = "H1";

    private final StrategyConfigRepository strategyConfigRepo;
    private final CandleDataRepository candleDataRepo;
    private final MarketRegimeDetector regimeDetector;
    private final RegimeChangeLogRepository regimeChangeLogRepo;

    public MarketRegimeAwareScheduler(StrategyConfigRepository strategyConfigRepo,
                                      CandleDataRepository candleDataRepo,
                                      MarketRegimeDetector regimeDetector,
                                      RegimeChangeLogRepository regimeChangeLogRepo) {
        this.strategyConfigRepo  = strategyConfigRepo;
        this.candleDataRepo      = candleDataRepo;
        this.regimeDetector      = regimeDetector;
        this.regimeChangeLogRepo = regimeChangeLogRepo;
    }

    /**
     * 1시간마다 시장 상태를 감지하고 전략 활성/비활성을 자동 조정한다.
     * initialDelay 60초: 애플리케이션 기동 직후 데이터가 미비할 수 있어 잠시 지연한다.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    @Transactional
    public void adjustStrategiesByRegime() {
        log.info("[RegimeScheduler] 시장 상태 감지 시작 ({} {})", REGIME_COIN_PAIR, REGIME_TIMEFRAME);

        List<Candle> candles = fetchCandles();
        if (candles.size() < REGIME_CANDLE_COUNT) {
            log.warn("[RegimeScheduler] 캔들 데이터 부족 ({}/{}개) — 자동 스위칭 건너뜀",
                    candles.size(), REGIME_CANDLE_COUNT);
            return;
        }

        MarketRegime regime = regimeDetector.detect(candles);
        log.info("[RegimeScheduler] 감지된 시장 상태: {}", regime);

        // manual_override=false 인 전략 설정만 자동 조정 대상
        List<StrategyConfigEntity> targets = strategyConfigRepo.findAllByManualOverrideFalse();
        if (targets.isEmpty()) {
            log.debug("[RegimeScheduler] 자동 조정 대상 전략 없음");
            // 전략이 없어도 레짐 전환은 기록한다
            recordTransitionIfChanged(regime, List.of());
            return;
        }

        int activated   = 0;
        int deactivated = 0;
        List<String> changeDescriptions = new ArrayList<>();

        for (StrategyConfigEntity config : targets) {
            String  strategyType   = config.getStrategyType();
            boolean currentlyActive = Boolean.TRUE.equals(config.getIsActive());

            boolean suitable   = MarketRegimeFilter.isSuitable(regime, strategyType);
            boolean unsuitable = MarketRegimeFilter.isUnsuitable(regime, strategyType);

            if (unsuitable && currentlyActive) {
                config.setIsActive(Boolean.FALSE);
                deactivated++;
                changeDescriptions.add("DEACTIVATED:" + strategyType);
                log.info("[RegimeScheduler] 전략 비활성화: id={} type={} reason=시장상태={}에_부적합",
                        config.getId(), strategyType, regime);

            } else if (suitable && !currentlyActive) {
                config.setIsActive(Boolean.TRUE);
                activated++;
                changeDescriptions.add("ACTIVATED:" + strategyType);
                log.info("[RegimeScheduler] 전략 활성화: id={} type={} reason=시장상태={}에_적합",
                        config.getId(), strategyType, regime);

            } else {
                log.debug("[RegimeScheduler] 변경 없음: id={} type={} active={} regime={}",
                        config.getId(), strategyType, currentlyActive, regime);
            }
        }

        // 일괄 저장 (변경된 항목만 dirty-check로 저장됨)
        strategyConfigRepo.saveAll(targets);

        log.info("[RegimeScheduler] 자동 스위칭 완료 — 활성화: {}개, 비활성화: {}개, 시장상태: {}",
                activated, deactivated, regime);

        // 레짐 전환 이력 기록
        recordTransitionIfChanged(regime, changeDescriptions);
    }

    /**
     * 이전 레짐과 비교해 전환이 발생한 경우에만 {@code regime_change_log}에 기록한다.
     * 최초 감지(이전 기록 없음)도 초기 상태로 저장한다.
     */
    private void recordTransitionIfChanged(MarketRegime currentRegime, List<String> strategyChanges) {
        String currentRegimeName = currentRegime.name();

        String previousRegimeName = regimeChangeLogRepo
                .findLatestOne(REGIME_COIN_PAIR, REGIME_TIMEFRAME)
                .map(RegimeChangeLogEntity::getToRegime)
                .orElse(null);

        // 이전과 동일한 레짐이면 기록하지 않음
        if (currentRegimeName.equals(previousRegimeName)) {
            log.debug("[RegimeScheduler] 레짐 변화 없음 ({}), 이력 기록 생략", currentRegimeName);
            return;
        }

        String changesJson = strategyChanges.isEmpty()
                ? "[]"
                : "[\"" + String.join("\",\"", strategyChanges) + "\"]";

        RegimeChangeLogEntity changeLog = RegimeChangeLogEntity.builder()
                .coinPair(REGIME_COIN_PAIR)
                .timeframe(REGIME_TIMEFRAME)
                .fromRegime(previousRegimeName)
                .toRegime(currentRegimeName)
                .strategyChangesJson(changesJson)
                .detectedAt(Instant.now())
                .build();

        regimeChangeLogRepo.save(changeLog);

        log.info("[RegimeScheduler] 레짐 전환 이력 저장: {} → {} (전략변경: {}건)",
                previousRegimeName == null ? "INITIAL" : previousRegimeName,
                currentRegimeName, strategyChanges.size());
    }

    /**
     * DB에서 기준 (coinPair, timeframe) 캔들을 최신순으로 조회해 오름차순으로 반환한다.
     */
    private List<Candle> fetchCandles() {
        Instant to   = Instant.now();
        Instant from = to.minusSeconds((long) REGIME_CANDLE_COUNT * 3600); // H1 캔들 기준

        List<CandleDataEntity> rows = candleDataRepo.findCandles(
                REGIME_COIN_PAIR, REGIME_TIMEFRAME, from, to);

        return rows.stream()
                .map(e -> new Candle(
                        e.getTime(),
                        e.getOpen(),
                        e.getHigh(),
                        e.getLow(),
                        e.getClose(),
                        e.getVolume()))
                .toList();
    }
}
