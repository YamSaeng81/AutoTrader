package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.util.TimeframeUtils;
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
 * 바꾸지 않는다. BTC 급락 관문의 BTC 캔들에는 걸지 않는다(관문은 캔들이 없으면 미발동으로 본다 — 별도 판단 사항).
 * 실시간 시세 손절 경로는 캔들을 쓰지 않으므로 무관하다.</p>
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

    /** (코인:타임프레임) → 판정 불가가 시작된 시각과 사유. 경보는 판정 불가가 시작될 때 한 번. */
    private final Map<String, String> stale = new ConcurrentHashMap<>();

    /**
     * 판정 불가 사유 — 쓸 수 있으면 {@code null}. 캔들은 시각 오름차순, 시각은 봉의 시작 시각.
     */
    public static String problem(List<Candle> candles, String timeframe, Instant now) {
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
        if (missing > MAX_MISSING_SLOTS) {
            return String.format("결측 %d칸(허용 %d) — 완료 봉 %d개 / 기대 %d개 (%s ~ %s)", missing, MAX_MISSING_SLOTS, closed, expected, first, last);
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
        String p = problem(candles, timeframe, Instant.now());
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
