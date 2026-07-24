package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * 워치리스트 품질 게이트 — 동적 세션의 코인 유니버스를 "거래 가능한 품질"로 큐레이션하는
 * 단일 진실 소스.
 *
 * <h3>배경 (2026-07-24 운영 DB 분석)</h3>
 * <p>동적 멀티코인 세션이 2주 넘게 실거래 0건이었다. 원인 분해(BUY 신호 86건 전량 게이트 차단):</p>
 * <ul>
 *   <li>BLACK_SWAN_GUARD 79% — BUY 신호를 낸 코인이 거의 전부 "1시간 내 -5~-19% 급락 중"인
 *       소형·신규상장 잡코인(ZKC/BIRB/SOPH/BONK…)이었다. 대형주(BTC/ETH/XRP/SOL)는 BUY 신호를
 *       한 번도 내지 않았다(HOLD/SELL만).</li>
 *   <li>EMA200 게이트 17% — 하락 추세 코인의 역추세 진입 시도.</li>
 * </ul>
 * <p>진입 게이트(BlackSwanGuard/EMA200)는 <b>신호가 난 뒤</b> 하나씩 차단하는 사후 방어라,
 * "거래대금 상위" 원시 유니버스가 펌프-덤프 잡코인으로 채워지는 근본 문제를 못 막았다. 이
 * 게이트는 그 앞단에서 유니버스 자체를 큐레이션한다 — 거래 가능한 코인만 스캔에 넘겨,
 * 진입 게이트와 신호가 상쇄되는 구조를 해소한다.</p>
 *
 * <h3>통과 조건 (AND)</h3>
 * <ol>
 *   <li><b>유동성</b>: 24h 누적 거래대금 ≥ {@code minTradeValueKrw} (미소형·신규상장 배제)</li>
 *   <li><b>변동성 상한</b>: ATR(14)/현재가 % ≤ {@code maxAtrPct} (초고변동 잡코인 배제)</li>
 *   <li><b>상승 추세</b>: 종가 &gt; EMA200 ({@link Ema200RegimeGate}) — 하락장 나이프 캐칭 배제</li>
 *   <li><b>급락 아님</b>: {@link BlackSwanGuard#check}가 미발동 — 이미 급락 중인 코인 사전 배제</li>
 * </ol>
 *
 * <p>모든 기준은 호출부(risk_config)에서 조정 가능하며, 개별 기준을 끄면(null/false) 해당
 * 조건만 건너뛴다. 진입 게이트({@link Ema200RegimeGate}·{@link BlackSwanGuard})와 동일한
 * 순수 정적 클래스로, 네트워크·DB 없이 단위 테스트된다.</p>
 */
public final class WatchlistQualityGate {

    private WatchlistQualityGate() {}

    /** 큐레이션 판정 결과 — 탈락 시 {@code reason}에 사유(로그·진단용)가 담긴다. */
    public record Decision(boolean accepted, String reason) {
        private static final Decision ACCEPT = new Decision(true, null);

        static Decision accept() {
            return ACCEPT;
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * 코인 하나가 워치리스트에 포함될 품질을 갖췄는지 판정한다.
     *
     * @param tradeValue24h    24시간 누적 거래대금(KRW). null이면 유동성 검사 생략.
     * @param atrPct           ATR(14)/현재가 % (타임프레임 정규화 완료값). null이면 변동성 검사 생략.
     * @param candles          시간 오름차순 캔들(마지막=현재) — EMA200·급락 판정용.
     * @param minTradeValueKrw 유동성 하한(KRW). null·0이하면 유동성 검사 생략.
     * @param maxAtrPct        변동성 상한 %. null·0이하면 변동성 검사 생략.
     * @param requireUptrend   true면 종가가 EMA200 위일 때만 통과.
     * @param excludeCrashing  true면 1시간 내 급락(BLACK_SWAN) 중인 코인 탈락.
     * @return 통과 시 {@link Decision#accept()}, 탈락 시 사유 포함 {@link Decision#reject}.
     */
    public static Decision evaluate(BigDecimal tradeValue24h, BigDecimal atrPct, List<Candle> candles,
                                    BigDecimal minTradeValueKrw, BigDecimal maxAtrPct,
                                    boolean requireUptrend, boolean excludeCrashing) {
        if (minTradeValueKrw != null && minTradeValueKrw.signum() > 0
                && tradeValue24h != null && tradeValue24h.compareTo(minTradeValueKrw) < 0) {
            return Decision.reject(String.format(
                    "유동성 미달 — 24h 거래대금 %s < %s KRW", plain(tradeValue24h), plain(minTradeValueKrw)));
        }

        if (maxAtrPct != null && maxAtrPct.signum() > 0
                && atrPct != null && atrPct.compareTo(maxAtrPct) > 0) {
            return Decision.reject(String.format(
                    "변동성 과다 — ATR %s%% > 상한 %s%%", plain(atrPct), plain(maxAtrPct)));
        }

        if (requireUptrend && !Ema200RegimeGate.allowsBuy(candles, null)) {
            return Decision.reject("하락 추세 — 종가 ≤ EMA200 (상승추세만 진입)");
        }

        if (excludeCrashing) {
            BlackSwanGuard.Result crash = BlackSwanGuard.check(candles);
            if (crash.triggered()) {
                return Decision.reject("급락 중 — " + crash.reason());
            }
        }

        return Decision.accept();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
