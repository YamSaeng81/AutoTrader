package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * <b>활성 위험 관문이 요구하는 캔들</b> — 수집 대상의 하한 (2026-10-15 리뷰 8회차 후속).
 *
 * <p>BTC 급락 관문은 LIVE·DYNAMIC 의 모든 매수에 걸리고, BTC 캔들이 없거나 낡으면 판정 불가로 진입을 막는다(§6.9-②). 그런데 캔들
 * 수집({@link MarketDataSyncService})은 <b>실행 중 세션이 거래하는 (코인, 타임프레임)</b>만 받아 왔다 — KRW-BTC 세션이 없는 타임프레임의
 * LIVE 세션은 BTC 캔들이 갱신되지 않아 진입이 계속 막힌다. 관문이 요구하는 데이터를 세션 구성과 무관하게 수집 대상에 넣는다(BTC 세션을
 * 임시로 만드는 방식에 의존하지 않는다).</p>
 *
 * <p>요구: 실행 중인 LIVE 세션과 DYNAMIC 세션(실자금·페이퍼 모드 — 관문이 엔진 동등하게 걸린다)의 타임프레임마다 KRW-BTC.
 * 🔴 독립 자본 페이퍼 엔진(검증 66세션)의 타임프레임은 넣지 않는다 — 그 엔진의 관문 입력을 이 변경이 새로 만들지 않게 한다. 다만 같은
 * 캐시를 읽으므로 <b>LIVE·DYNAMIC 과 같은 타임프레임의 검증 세션은 새로 갱신되는 BTC 캔들을 보게 된다</b> — 배포 전 영향 확인 대상
 * (§6.10).</p>
 */
@Component
public class RiskGateDataRequirements {

    /** BTC 급락 관문의 대상 코인. */
    public static final String BTC = "KRW-BTC";

    private final LiveTradingSessionRepository liveRepo;
    private final DynamicSessionRepository dynamicRepo;

    public RiskGateDataRequirements(LiveTradingSessionRepository liveRepo, DynamicSessionRepository dynamicRepo) {
        this.liveRepo = liveRepo;
        this.dynamicRepo = dynamicRepo;
    }

    /** (코인, 타임프레임) 목록 — 중복 없음, 타임프레임 순. */
    public List<String[]> requiredCandles() {
        TreeSet<String> timeframes = new TreeSet<>();
        liveRepo.findByStatus("RUNNING").forEach(s -> {
            if (s.getTimeframe() != null) timeframes.add(s.getTimeframe());
        });
        dynamicRepo.findByStatus("RUNNING").forEach(s -> {
            if (s.getTimeframe() != null) timeframes.add(s.getTimeframe());
        });
        List<String[]> out = new ArrayList<>();
        for (String tf : timeframes) out.add(new String[]{BTC, tf});
        return out;
    }
}
