package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.BatchBacktestRequest;
import com.cryptoautotrader.api.dto.NightlySchedulerConfigDto;
import com.cryptoautotrader.api.dto.WalkForwardBatchRequest;
import com.cryptoautotrader.api.entity.NightlySchedulerConfigEntity;
import com.cryptoautotrader.api.repository.NightlySchedulerConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NightlySchedulerConfigService {

    private static final long SINGLETON_ID = 1L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NightlySchedulerConfigRepository configRepo;
    private final BacktestJobService backtestJobService;

    // ── 설정 조회 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NightlySchedulerConfigDto getConfig() {
        NightlySchedulerConfigEntity entity = getOrCreate();
        return toDto(entity);
    }

    // ── 설정 저장 ────────────────────────────────────────────────────────────────

    @Transactional
    public NightlySchedulerConfigDto updateConfig(NightlySchedulerConfigDto dto) {
        NightlySchedulerConfigEntity entity = getOrCreate();

        if (dto.getEnabled()           != null) entity.setEnabled(dto.getEnabled());
        if (dto.getRunHour()           != null) entity.setRunHour(dto.getRunHour());
        if (dto.getRunMinute()         != null) entity.setRunMinute(dto.getRunMinute());
        if (dto.getTimeframe()         != null) entity.setTimeframe(dto.getTimeframe());
        if (dto.getStartDate()         != null) entity.setStartDate(dto.getStartDate());
        if (dto.getEndDate()           != null) entity.setEndDate(dto.getEndDate());
        if (dto.getCoinPairs()         != null) entity.setCoinPairs(String.join(",", dto.getCoinPairs()));
        if (dto.getStrategyTypes()     != null) entity.setStrategyTypes(String.join(",", dto.getStrategyTypes()));
        if (dto.getIncludeBacktest()   != null) entity.setIncludeBacktest(dto.getIncludeBacktest());
        if (dto.getIncludeWalkForward() != null) entity.setIncludeWalkForward(dto.getIncludeWalkForward());
        if (dto.getInSampleRatio()     != null) entity.setInSampleRatio(dto.getInSampleRatio());
        if (dto.getWindowCount()       != null) entity.setWindowCount(dto.getWindowCount());
        if (dto.getInitialCapital()    != null) entity.setInitialCapital(dto.getInitialCapital());
        if (dto.getSlippagePct()       != null) entity.setSlippagePct(dto.getSlippagePct());
        if (dto.getFeePct()            != null) entity.setFeePct(dto.getFeePct());

        configRepo.save(entity);
        log.info("[SchedulerConfig] 설정 저장 완료 — enabled={}, {}:{} KST, {}개 코인 × {}개 전략",
                entity.getEnabled(), entity.getRunHour(), entity.getRunMinute(),
                entity.coinPairList().size(), entity.strategyTypeList().size());
        return toDto(entity);
    }

    // ── 수동 즉시 실행 ────────────────────────────────────────────────────────────

    /**
     * @Transactional 제거 이유: submitBatchJob/submitWalkForwardBatchJob 내부에서 job을 저장한 뒤
     * 즉시 @Async 스레드를 dispatch한다. 바깥 트랜잭션이 열려 있으면 job 저장이 커밋되지 않아
     * async 스레드가 findById에서 job을 찾지 못하고 영구 PENDING이 된다.
     * 트랜잭션 없이 실행하면 각 repository.save()가 자체 트랜잭션으로 즉시 커밋된다.
     */
    public Map<String, Object> triggerNow() {
        NightlySchedulerConfigEntity cfg = getOrCreate();
        return executeJobs(cfg, true);
    }

    // ── 스케줄 체크 (1분마다 호출됨) ─────────────────────────────────────────────

    /** @Transactional 제거 — triggerNow()와 동일한 이유 */
    public void checkAndRun() {
        NightlySchedulerConfigEntity cfg = getOrCreate();
        if (!Boolean.TRUE.equals(cfg.getEnabled())) return;

        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        if (nowKst.getHour() != cfg.getRunHour() || nowKst.getMinute() != cfg.getRunMinute()) return;

        // 당일 중복 실행 방지: 마지막 실행이 23시간 이내면 건너뜀
        if (cfg.getLastTriggeredAt() != null) {
            long hoursSinceLast = (Instant.now().getEpochSecond() - cfg.getLastTriggeredAt().getEpochSecond()) / 3600;
            if (hoursSinceLast < 23) {
                log.debug("[SchedulerConfig] 중복 실행 방지 — 마지막 실행 {}시간 전", hoursSinceLast);
                return;
            }
        }

        log.info("[SchedulerConfig] 야간 자동 분석 실행 — {}:{} KST", cfg.getRunHour(), cfg.getRunMinute());
        executeJobs(cfg, false);
    }

    // ── 내부 실행 로직 ────────────────────────────────────────────────────────────

    private Map<String, Object> executeJobs(NightlySchedulerConfigEntity cfg, boolean manual) {
        List<String> coins      = cfg.coinPairList();
        List<String> strategies = cfg.strategyTypeList();

        if (coins.isEmpty() || strategies.isEmpty()) {
            throw new IllegalStateException("코인 또는 전략 목록이 비어있습니다.");
        }

        Long batchJobId = null;
        Long wfJobId    = null;

        if (Boolean.TRUE.equals(cfg.getIncludeBacktest())) {
            BatchBacktestRequest batchReq = new BatchBacktestRequest();
            batchReq.setCoinPairs(coins);
            batchReq.setStrategyTypes(strategies);
            batchReq.setTimeframe(cfg.getTimeframe());
            batchReq.setStartDate(cfg.getStartDate());
            batchReq.setEndDate(cfg.getEndDate());
            batchReq.setInitialCapital(cfg.getInitialCapital());
            batchReq.setSlippagePct(cfg.getSlippagePct());
            batchReq.setFeePct(cfg.getFeePct());
            batchJobId = backtestJobService.submitBatchJob(batchReq);
            log.info("[SchedulerConfig] 배치 백테스트 제출 — jobId={}", batchJobId);
        }

        if (Boolean.TRUE.equals(cfg.getIncludeWalkForward())) {
            WalkForwardBatchRequest wfReq = new WalkForwardBatchRequest();
            wfReq.setCoinPairs(coins);
            wfReq.setStrategyTypes(strategies);
            wfReq.setTimeframe(cfg.getTimeframe());
            wfReq.setStartDate(cfg.getStartDate());
            wfReq.setEndDate(cfg.getEndDate());
            wfReq.setInSampleRatio(cfg.getInSampleRatio());
            wfReq.setWindowCount(cfg.getWindowCount());
            wfReq.setInitialCapital(cfg.getInitialCapital());
            wfReq.setSlippagePct(cfg.getSlippagePct());
            wfReq.setFeePct(cfg.getFeePct());
            wfJobId = backtestJobService.submitWalkForwardBatchJob(wfReq);
            log.info("[SchedulerConfig] Walk-Forward 배치 제출 — jobId={}", wfJobId);
        }

        cfg.setLastTriggeredAt(Instant.now());
        cfg.setLastBatchJobId(batchJobId);
        cfg.setLastWfJobId(wfJobId);
        configRepo.save(cfg);

        String mode = manual ? "수동" : "자동";
        log.info("[SchedulerConfig] {} 실행 완료 — batchJobId={}, wfJobId={}", mode, batchJobId, wfJobId);
        return Map.of(
                "batchJobId",       batchJobId != null ? batchJobId : "skip",
                "wfJobId",          wfJobId    != null ? wfJobId    : "skip",
                "triggeredAt",      Instant.now().toString(),
                "coins",            coins.size(),
                "strategies",       strategies.size()
        );
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    private NightlySchedulerConfigEntity getOrCreate() {
        return configRepo.findById(SINGLETON_ID).orElseGet(() -> {
            NightlySchedulerConfigEntity def = NightlySchedulerConfigEntity.builder()
                    .id(SINGLETON_ID)
                    .enabled(false)
                    .runHour(0).runMinute(0)
                    .timeframe("H1")
                    .startDate(java.time.LocalDate.of(2023, 1, 1))
                    .endDate(java.time.LocalDate.of(2025, 12, 31))
                    .coinPairs("KRW-BTC,KRW-ETH,KRW-SOL,KRW-XRP,KRW-DOGE")
                    .strategyTypes("COMPOSITE_BREAKOUT,COMPOSITE_MOMENTUM,COMPOSITE_MOMENTUM_ICHIMOKU,COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                    .includeBacktest(true).includeWalkForward(true)
                    .inSampleRatio(0.7).windowCount(5)
                    .initialCapital(new java.math.BigDecimal("1000000"))
                    .slippagePct(new java.math.BigDecimal("0.05"))
                    .feePct(new java.math.BigDecimal("0.05"))
                    .build();
            return configRepo.save(def);
        });
    }

    private NightlySchedulerConfigDto toDto(NightlySchedulerConfigEntity e) {
        NightlySchedulerConfigDto dto = new NightlySchedulerConfigDto();
        dto.setEnabled(e.getEnabled());
        dto.setRunHour(e.getRunHour());
        dto.setRunMinute(e.getRunMinute());
        dto.setTimeframe(e.getTimeframe());
        dto.setStartDate(e.getStartDate());
        dto.setEndDate(e.getEndDate());
        dto.setCoinPairs(e.coinPairList());
        dto.setStrategyTypes(e.strategyTypeList());
        dto.setIncludeBacktest(e.getIncludeBacktest());
        dto.setIncludeWalkForward(e.getIncludeWalkForward());
        dto.setInSampleRatio(e.getInSampleRatio());
        dto.setWindowCount(e.getWindowCount());
        dto.setInitialCapital(e.getInitialCapital());
        dto.setSlippagePct(e.getSlippagePct());
        dto.setFeePct(e.getFeePct());
        dto.setLastBatchJobId(e.getLastBatchJobId());
        dto.setLastWfJobId(e.getLastWfJobId());
        if (e.getLastTriggeredAt() != null) {
            dto.setLastTriggeredAt(ZonedDateTime.ofInstant(e.getLastTriggeredAt(), KST)
                    .format(DT_FMT));
        }
        // 다음 실행 시각 계산
        if (Boolean.TRUE.equals(e.getEnabled())) {
            dto.setNextRunAt(calcNextRun(e.getRunHour(), e.getRunMinute()));
        }
        return dto;
    }

    private String calcNextRun(int hour, int minute) {
        ZonedDateTime now  = ZonedDateTime.now(KST);
        ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return next.format(DT_FMT) + " KST";
    }
}
