package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-04 회귀 테스트 — <b>재기동 시 BLACK_SWAN 쿨다운 복원</b>.
 *
 * <p><b>사고 재현</b>: 쿨다운 맵은 인메모리라 재기동으로 사라진다. 08-04 13:0x 배포 재기동 때
 * KRW-META2가 10:00에 가드로 차단돼 쿨다운이 약 40분 남아 있었는데 그 잔여분이 통째로
 * 소실됐다. 재기동 직후는 급락이 이미 지나가 <b>가드 본체는 안 걸리고 쿨다운만 필요한 구간</b>
 * 이라, "1차 방어가 있으니 괜찮다"는 기존 판단이 성립하지 않는다 — 08-03 ELSA 사고
 * (가드 4회 차단 → 해제 직후 진입 → −8.33%)의 창이 재기동마다 다시 열린다.</p>
 *
 * <p>가장 중요한 잠금은 <b>자기 연장 차단</b>이다. 쿨다운이 남긴 차단 로그까지 복원 대상에
 * 넣으면 쿨다운이 스스로를 갱신해 영구 차단으로 굳는다.</p>
 */
class DynamicBlackSwanCooldownRestoreTest extends IntegrationTestBase {

    private static final String GUARD_BLOCK = "BLACK_SWAN_GUARD 발동 — 거래량 급증 47.9배 + 1시간 내 하락 -2.15%";
    private static final String COOLDOWN_BLOCK = "BLACK_SWAN 쿨다운 — 5분 전 차단된 종목 (해제까지 235분)";

    @Autowired
    private DynamicTradingService injectedService;

    @Autowired
    private StrategyLogRepository strategyLogRepository;

    /**
     * 주입된 빈은 {@code @Transactional}/{@code @Async} 때문에 CGLIB 프록시라 필드가 비어 있다
     * (프록시 인스턴스에는 값이 없고 타깃에만 있다). 쿨다운 맵을 직접 검증하려면 타깃을 꺼내야 한다.
     */
    private DynamicTradingService dynamicTradingService;

    @BeforeEach
    @AfterEach
    void cleanup() {
        dynamicTradingService = AopTestUtils.getUltimateTargetObject(injectedService);
        strategyLogRepository.deleteAll();
        dynamicTradingService.blackSwanBlockedAt.clear();
        dynamicTradingService.blackSwanBlockedPrice.clear();
    }

    private void log(String coinPair, String sessionType, String blockedReason, long minutesAgo) {
        log(coinPair, sessionType, blockedReason, minutesAgo, null);
    }

    private void log(String coinPair, String sessionType, String blockedReason,
                     long minutesAgo, BigDecimal signalPrice) {
        strategyLogRepository.saveAndFlush(StrategyLogEntity.builder()
                .strategyName("COMPOSITE_MTF_BTC")
                .coinPair(coinPair)
                .signal("BUY")
                .reason("테스트 신호")
                .sessionType(sessionType)
                .sessionId(43L)
                .blockedReason(blockedReason)
                .signalPrice(signalPrice)
                .createdAt(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
                .build());
    }

    @Test
    @DisplayName("재기동 후 쿨다운 구간 내 가드 차단 종목이 복원된다")
    void 가드_차단_종목_복원() {
        log("KRW-META2", "DYNAMIC", GUARD_BLOCK, 40);

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).containsKey("KRW-META2");
    }

    @Test
    @DisplayName("차단이 여러 번이면 가장 최근 시각을 채택한다 — 쿨다운이 조기 만료되지 않는다")
    void 최근_차단_시각_채택() {
        log("KRW-ELSA", "DYNAMIC", GUARD_BLOCK, 200);
        log("KRW-ELSA", "DYNAMIC", GUARD_BLOCK, 30);

        dynamicTradingService.restoreBlackSwanCooldown();

        Instant restored = dynamicTradingService.blackSwanBlockedAt.get("KRW-ELSA");
        assertThat(restored).isNotNull();
        // 30분 전(최근)이 채택돼야 한다. 200분 전을 잡으면 쿨다운이 40분 만에 풀려버린다.
        assertThat(restored).isAfter(Instant.now().minus(60, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("쿨다운이 만든 차단 로그는 복원 대상이 아니다 — 자기 연장으로 영구 차단되는 것을 막는다")
    void 쿨다운_자기연장_차단() {
        log("KRW-SHIB", "DYNAMIC", COOLDOWN_BLOCK, 10);

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).doesNotContainKey("KRW-SHIB");
    }

    @Test
    @DisplayName("진입가 가드 기간(24시간)이 지난 차단은 복원하지 않는다")
    void 만료된_차단_제외() {
        log("KRW-DOGE", "DYNAMIC", GUARD_BLOCK, 1500);

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).doesNotContainKey("KRW-DOGE");
    }

    @Test
    @DisplayName("쿨다운(240분)은 지났지만 24시간 내면 진입가 가드용으로 계속 복원한다")
    void 쿨다운_만료분도_진입가_가드용으로_복원() {
        // 2026-08-05 진입가 가드 도입 전에는 이 구간을 복원하지 않았다. 그 결과 재기동만 하면
        // "차단가보다 비싸게 재진입"을 막을 기준가가 사라졌다 (META2 −7.05% 패턴).
        log("KRW-META2", "DYNAMIC", GUARD_BLOCK, 300, new BigDecimal("8630"));

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).containsKey("KRW-META2");
        // DB numeric은 scale이 붙어 돌아오므로(8630.00000000) 값으로만 비교한다.
        assertThat(dynamicTradingService.blackSwanBlockedPrice.get("KRW-META2"))
                .as("차단 시점가가 있어야 진입가 가드가 작동한다")
                .isEqualByComparingTo(new BigDecimal("8630"));
    }

    @Test
    @DisplayName("차단 시점가가 없는 구 로그도 복원은 되지만 기준가는 남기지 않는다")
    void 기준가_없는_구로그() {
        log("KRW-XRP", "DYNAMIC", GUARD_BLOCK, 30, null);

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).containsKey("KRW-XRP");
        assertThat(dynamicTradingService.blackSwanBlockedPrice).doesNotContainKey("KRW-XRP");
    }

    @Test
    @DisplayName("LIVE 세션의 차단 로그는 동적 세션 쿨다운에 섞이지 않는다")
    void 세션종류_격리() {
        log("KRW-BTC", "LIVE", GUARD_BLOCK, 10);

        dynamicTradingService.restoreBlackSwanCooldown();

        assertThat(dynamicTradingService.blackSwanBlockedAt).doesNotContainKey("KRW-BTC");
    }
}
