package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.strategy.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>캔들 최신성·결측 판정</b> — 엔진이 오래된 캐시를 정상 데이터로 쓰지 않게 한다 (2026-10-15 리뷰 7회차).
 *
 * <p>LIVE 는 캐시만 읽고 DYNAMIC 은 REST 수집이 실패하면 캐시를 읽는다(5-h 수정). 캐시는 수집이 멎은 시각에 멈춰 있을 수 있으므로
 * 봉 수만으로는 부족하다. 세션이 거래하는 코인의 캔들에 아래 두 조건을 건다 — 어기면 <b>판정 불가</b>로 보고 그 회차의 평가(진입·
 * 캔들 기반 청산)를 하지 않는다. 낡은 종가로 손절·익절을 내지 않고, 낡은 지표로 진입하지 않는다.</p>
 *
 * <pre>
 * ① 최신 완료 봉: 지금(− {@link #GRACE}) 기준 직전에 닫힌 봉이 있어야 한다.   H1, 10:05 → 09:00 봉 / 10:01 → 08:00 봉도 허용
 * ② 결측: 첫 완료 봉부터 마지막 완료 봉까지 빠진 칸이 {@link #MAX_MISSING_SLOTS} 개 이하
 * </pre>
 *
 * <p>{@link #GRACE} 는 수집 주기(60초)와 거래소 반영 지연의 여유다. 결측 허용 2칸은 캐시 충분 판정({@code shouldFetchFullRange})의
 * "거래소 쪽 정상적인 결측" 여유와 같다. 🔴 체결이 없던 칸은 업비트가 봉을 만들지 않으므로, 거래가 드문 코인은 판정 불가가 잦을 수
 * 있다 — 그 회차에는 진입·캔들 청산을 하지 않는다(로그·경보로 드러난다).</p>
 *
 * <p>🔴 범위: LIVE·DYNAMIC(실자금·페이퍼 모드 공통 — 엔진 동등)의 <b>세션 거래 코인</b>. 독립 자본 페이퍼 엔진(검증 66세션)은
 * 바꾸지 않는다. 실시간 시세 손절 경로는 캔들을 쓰지 않으므로 무관하다.</p>
 *
 * <p>🔴 <b>공식 점검 공백</b> (2026-10-19, V93): 업비트는 체결이 없는 구간에 봉을 만들지 않는다. 거래소 점검 중에는 전 마켓의 봉이 없고,
 * 이 규칙만으로는 점검이 끝나도 그 구간이 창에서 밀려날 때까지(M15 약 5일·H1 약 20일) 판정 불가가 된다. 그래서 ② 의 결측에서
 * <b>두 조건을 모두 만족하는 칸만</b> 뺀다 — (가) 봉 구간 전체가 운영자가 기록한 끝난 시장 공통 점검 구간({@link ExchangeMaintenanceWindows}) 안에
 * 있다(경계에 걸친 봉은 재개 뒤 시간도 포함하므로 일반 결측으로 센다)
 * (나) 결측 재수집의 거래소 조회에서 <b>마지막 조회 시점에</b> 봉이 없었다({@link CandleGapBackfillService#absentAtLastCheck}).
 * 점검 밖의 결측(무체결·수집 실패)은 종전대로 센다. ① 최신 완료 봉 조건은 예외가 없다 — 과거 점검 예외가 현재 적재 정지를 가리지 않는다.
 * 전략별 최소 봉 수 검사(엔진)도 그대로다.</p>
 *
 * <p>🔴 가정과 한계: 가짜 봉을 만들지 않는다. 지표는 <b>관측된 봉을 이어서</b> 계산한다 — 점검 전 마지막 봉과 점검 뒤 첫 봉이 이웃한
 * 봉으로 들어간다. 봉 개수로 정의된 지표(EMA·RSI·ATR·일목 등의 기간)는 점검 칸 수만큼 더 긴 실제 시간을 덮고, 점검 사이의 가격
 * 변화가 한 봉의 변화로 들어간다(ATR·변화율이 커질 수 있다). 시각 기준 지표(예: 일 단위 기준점)는 영향이 없다. 이 영향은 점검 구간이
 * 창에서 밀려나면 사라진다.</p>
 *
 * <p>🔴 BTC 급락 관문 (2026-10-15 리뷰 8회차): 같은 두 조건을 BTC 캔들에 건다({@link #btcGate}). 어기면 관문은 "미발동" 이 아니라
 * <b>판정 불가</b>이고, 이 관문을 요구하는 신규 진입(LIVE·DYNAMIC 매수)을 막는다. 데이터가 돌아오면 다음 평가에서 다시 판정한다.
 * 청산은 이 관문과 무관하다.</p>
 */
@Slf4j
@Component
public class CandleFreshnessGuard {

    static final Duration GRACE = Duration.ofMinutes(2);
    static final int MAX_MISSING_SLOTS = 2;

    @Autowired(required = false)
    private TelegramNotificationService telegram;

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Autowired(required = false)
    private ExchangeMaintenanceWindows maintenanceWindows;

    @Autowired(required = false)
    private CandleGapBackfillService gapBackfill;

    /** 점검 공백을 뺀 (코인:타임프레임) — 같은 점검 구간에 대해 로그는 한 번. */
    private final Map<String, Long> exemptionLogged = new ConcurrentHashMap<>();

    /** (코인:타임프레임) → 판정 불가가 시작된 시각과 사유. 경보는 판정 불가가 시작될 때 한 번. */
    private final Map<String, String> stale = new ConcurrentHashMap<>();

    /**
     * 판정 불가 사유 — 쓸 수 있으면 {@code null}. 캔들은 시각 오름차순, 시각은 봉의 시작 시각.
     */
    public static String problem(List<Candle> candles, String timeframe, Instant now) {
        return problem(candles, timeframe, now, null);
    }

    /**
     * @param exemptSlot 결측에서 뺄 수 있는 칸인가(공식 점검 공백) — {@code null} 이면 모든 결측을 센다. ① 최신 완료 봉 조건에는 쓰지 않는다.
     */
    public static String problem(List<Candle> candles, String timeframe, Instant now,
                                 java.util.function.Predicate<Instant> exemptSlot) {
        if (candles == null || candles.isEmpty()) return "캔들 없음";
        long tfSec = TimeframeUtils.toMinutes(timeframe) * 60L;
        long ref = now.minus(GRACE).getEpochSecond();
        Instant latestClosedStart = Instant.ofEpochSecond(ref - Math.floorMod(ref, tfSec) - tfSec);

        int closed = 0;
        Instant first = null;
        Instant last = null;
        for (Candle c : candles) {
            if (c.getTime().plusSeconds(tfSec).isAfter(now)) continue;   // 아직 닫히지 않은 봉
            if (first == null) first = c.getTime();
            last = c.getTime();
            closed++;
        }
        if (last == null) return "완료 봉 없음";
        if (last.isBefore(latestClosedStart)) {
            return String.format("최신 완료 봉 없음 — 마지막 완료 봉 %s, 필요 %s 이후(%d분 늦음)", last, latestClosedStart,
                    Duration.between(last, latestClosedStart).toMinutes());
        }
        long expected = Duration.between(first, last).getSeconds() / tfSec + 1;
        long missing = expected - closed;
        long exempt = 0;
        if (missing > 0 && exemptSlot != null) {
            java.util.Set<Long> have = new java.util.HashSet<>();
            for (Candle c : candles) have.add(c.getTime().getEpochSecond());
            for (long t = first.getEpochSecond(); t <= last.getEpochSecond(); t += tfSec) {
                if (!have.contains(t) && exemptSlot.test(Instant.ofEpochSecond(t))) exempt++;
            }
        }
        if (missing - exempt > MAX_MISSING_SLOTS) {
            return String.format("결측 %d칸(허용 %d) — 완료 봉 %d개 / 기대 %d개 (%s ~ %s)%s", missing - exempt, MAX_MISSING_SLOTS, closed, expected,
                    first, last, exempt > 0 ? " · 공식 점검 공백 " + exempt + "칸 제외 후" : "");
        }
        return null;
    }

    /**
     * 쓸 수 있으면 그대로, 판정 불가면 빈 목록을 돌려준다 — 엔진은 빈 목록을 "이번 회차 평가 안 함" 으로 이미 다룬다.
     *
     * @param engine 로그용(LIVE·DYNAMIC)
     */
    public List<Candle> filter(String engine, String coinPair, String timeframe, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return candles == null ? List.of() : candles;
        String key = coinPair + ":" + timeframe;
        Instant now = Instant.now();
        String p = problem(candles, timeframe, now, maintenanceExemption(coinPair, timeframe, now));
        if (p == null) {
            String prev = stale.remove(key);
            if (prev != null) {
                log.warn("[캔들 최신성] {} 판정 재개 ({}): 종전 {}", key, engine, prev);
            }
            return candles;
        }
        String prev = stale.putIfAbsent(key, Instant.now() + " " + p);
        if (prev == null) {
            log.error("🔴 [캔들 최신성] {} 판정 불가 ({}) — 이 회차의 진입·캔들 기반 청산을 하지 않는다: {}", key, engine, p);
            alertIfHeld(engine, coinPair, timeframe, p);
        } else {
            log.debug("[캔들 최신성] {} 판정 불가 지속: {}", key, p);
        }
        return List.of();
    }

    /**
     * BTC 급락 관문 판정 — BTC 캔들이 최신 완료 봉·결측 조건을 어기면 {@link BtcMarketGuard.Result#unknown 판정 불가}, 아니면
     * {@link BtcMarketGuard#check}. 시각은 호출 시점.
     *
     * @param raw      최신성을 볼 원래 캔들(진행 중 봉 포함 가능)
     * @param forCheck 관문 계산에 넣을 캔들(엔진이 닫힌 봉만 넣는다면 그 목록)
     */
    public BtcMarketGuard.Result btcGate(String engine, String timeframe, List<Candle> raw, List<Candle> forCheck) {
        Instant now = Instant.now();
        BtcMarketGuard.Result r = btcGate(timeframe, raw, forCheck, now, maintenanceExemption("KRW-BTC", timeframe, now));
        String key = "BTC관문:" + timeframe;
        if (!r.unknown()) {
            String prev = stale.remove(key);
            if (prev != null) {
                log.warn("[캔들 최신성] BTC 급락 관문 {} 판정 재개 ({}): 종전 {}", timeframe, engine, prev);
            }
            return r;
        }
        if (stale.putIfAbsent(key, Instant.now() + " " + r.reason()) == null) {
            log.error("🔴 [캔들 최신성] BTC 급락 관문 {} 판정 불가 ({}) — 이 관문을 요구하는 신규 진입을 막는다: {}", timeframe, engine, r.reason());
        }
        return r;
    }

    /** {@link #btcGate(String, String, List, List)} 의 판정 본체 — 시험용으로 시각을 받는다. */
    static BtcMarketGuard.Result btcGate(String timeframe, List<Candle> raw, List<Candle> forCheck, Instant now) {
        return btcGate(timeframe, raw, forCheck, now, null);
    }

    static BtcMarketGuard.Result btcGate(String timeframe, List<Candle> raw, List<Candle> forCheck, Instant now,
                                         java.util.function.Predicate<Instant> exemptSlot) {
        String p = problem(raw, timeframe, now, exemptSlot);
        if (p != null) {
            return BtcMarketGuard.Result.unknown("BTC 캔들 판정 불가(" + p + ") — 급락 여부를 모른다");
        }
        return BtcMarketGuard.check(forCheck);
    }

    /**
     * 공식 점검 공백 판정 — 봉 구간 전체가 끝난 시장 공통 점검 구간 안에 있고, 결측 재수집의 마지막 조회에서도 거래소에 봉이 없던 칸.
     * 둘 중 하나라도 없으면(구성 요소가 없거나 아직 조회 전) 예외가 없다.
     */
    java.util.function.Predicate<Instant> maintenanceExemption(String coinPair, String timeframe, Instant now) {
        if (maintenanceWindows == null || gapBackfill == null) return null;
        long tfSec = TimeframeUtils.toMinutes(timeframe) * 60L;
        return slot -> {
            ExchangeMaintenanceWindows.Window w = maintenanceWindows.covering(slot, tfSec, now);
            if (w == null) return false;
            CandleGapBackfillService.AbsentCheck check = gapBackfill.absentAtLastCheck(coinPair, timeframe, slot);
            if (check == null) return false;
            if (exemptionLogged.put(coinPair + ":" + timeframe, w.id()) == null) {
                log.info("[캔들 최신성] {}:{} 공식 점검 공백을 결측에서 뺀다 — 점검 {} · 칸 {} 거래소 조회 {}회(마지막 {})",
                        coinPair, timeframe, w.describe(), slot, check.checks(), check.lastCheckedAt());
            }
            return true;
        };
    }

    /** 실자금 보유가 있는 코인이면 경보한다 — 그 포지션의 60초 평가 손절·익절·시간 초과가 멈춘다. */
    private void alertIfHeld(String engine, String coinPair, String timeframe, String problem) {
        if (telegram == null || jdbc == null) return;
        try {
            Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM position WHERE coin_pair = ? AND session_kind IN ('LIVE', 'DYNAMIC') "
                    + "AND status IN ('OPEN', 'CLOSING') AND size > 0", Integer.class, coinPair);
            if (held != null && held > 0) {
                telegram.notifyRiskLimitBreached(String.format("캔들 판정 불가 %s %s (%s) — 보유 포지션 %d개의 60초 평가 청산이 멈췄다. "
                        + "캔들 수집을 확인하라(실시간 시세 손절은 별개): %s", coinPair, timeframe, engine, held, problem));
            }
        } catch (Exception e) {
            log.warn("캔들 판정 불가 경보 실패: {}", e.getMessage());
        }
    }

    /** 지금 판정 불가인 (코인:타임프레임) — 상태 확인·시험용. */
    public Map<String, String> staleKeys() {
        return Map.copyOf(stale);
    }
}
