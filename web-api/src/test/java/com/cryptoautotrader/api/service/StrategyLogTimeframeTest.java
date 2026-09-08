package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.StrategyLogEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신호 로그 집계의 <b>타임프레임 축</b> — 2026-09-08 신설.
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code strategy_log} 에 {@code timeframe} 컬럼이 없어서, 이 테이블을 집계하는 경로가
 * 전부 (전략, 코인) 으로만 묶고 <b>H1 과 M15 를 한 통계로 합쳤다.</b> 운영은 두 타임프레임을
 * 동시에 돌리고 M15 가 3~5배 많아 사실상 M15 통계에 H1 이 잡음으로 섞이는 구조였다.</p>
 *
 * <p><b>합치면 결론이 실제로 뒤집힌다</b> (운영 DB, 2026-08-01~ BUY 신호 사후 4h 수익률):</p>
 * <pre>
 *   전략                             H1        M15      합산(수정 전)
 *   COMPOSITE_MTF_BTC              -1.428    +0.046    -0.319   ← 부호가 반대
 *   COMPOSITE_MTF_BTC_STRICT       -0.509    +0.518    +0.266   ← 합산은 양수, H1 은 음수
 *   COMPOSITE_MOMENTUM_ICHIMOKU_V2 -0.367    +0.142    +0.052   ← 같은 패턴
 * </pre>
 *
 * <p>09-04 "전략 검토" · 09-07 "전략 순위가 통제하면 무너진다" 분석 모두 코인·시각은
 * 통제했지만 <b>타임프레임은 통제한 적이 없다.</b> 08-24 에 WF 게이트가 코인 축만 좁히고
 * 타임프레임을 놓친 것과 같은 결함이다.</p>
 */
class StrategyLogTimeframeTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V76__add_timeframe_to_strategy_log.sql");

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("파일을 읽을 수 없습니다: " + p, e);
        }
    }

    private static StrategyLogEntity log(String strategy, String coin, String timeframe) {
        return StrategyLogEntity.builder()
                .strategyName(strategy).coinPair(coin).timeframe(timeframe).build();
    }

    @Test
    @DisplayName("엔티티가 timeframe 을 갖는다 — 없으면 집계가 구조적으로 섞인다")
    void 엔티티에_타임프레임이_있다() {
        StrategyLogEntity e = log("COMPOSITE_MTF_BTC", "KRW-BTC", "M15");
        assertThat(e.getTimeframe()).isEqualTo("M15");
    }

    @Test
    @DisplayName("같은 전략·코인이라도 타임프레임이 다르면 다른 그룹이다 (핵심 회귀)")
    void 타임프레임이_다르면_다른_그룹이다() {
        List<StrategyLogEntity> logs = List.of(
                log("COMPOSITE_MTF_BTC", "KRW-BTC", "H1"),
                log("COMPOSITE_MTF_BTC", "KRW-BTC", "H1"),
                log("COMPOSITE_MTF_BTC", "KRW-BTC", "M15"));

        // LogController.buildByStrategy · StrategyDegradationWatchdog.groupByKey 와 같은 키 규칙
        Map<String, List<StrategyLogEntity>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l -> l.getStrategyName() + "|" + l.getCoinPair()
                        + "|" + (l.getTimeframe() != null ? l.getTimeframe() : "?")));

        assertThat(grouped)
                .as("H1 −1.428%% / M15 +0.046%% 처럼 방향이 반대인 경우가 실제로 있다 — 합치면 안 된다")
                .hasSize(2);
        assertThat(grouped.get("COMPOSITE_MTF_BTC|KRW-BTC|H1")).hasSize(2);
        assertThat(grouped.get("COMPOSITE_MTF_BTC|KRW-BTC|M15")).hasSize(1);
    }

    @Test
    @DisplayName("V76 이전 행(timeframe NULL)은 '?' 로 분리된다 — 아무 그룹에나 합쳐지지 않는다")
    void 과거행은_별도_그룹이다() {
        List<StrategyLogEntity> logs = List.of(
                log("COMPOSITE_MTF_BTC", "KRW-BTC", "H1"),
                log("COMPOSITE_MTF_BTC", "KRW-BTC", null));

        Map<String, List<StrategyLogEntity>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l -> l.getStrategyName() + "|" + l.getCoinPair()
                        + "|" + (l.getTimeframe() != null ? l.getTimeframe() : "?")));

        assertThat(grouped).containsOnlyKeys(
                "COMPOSITE_MTF_BTC|KRW-BTC|H1", "COMPOSITE_MTF_BTC|KRW-BTC|?");
    }

    @Test
    @DisplayName("소비자 두 곳이 타임프레임을 키에 포함한다 — 한쪽만 고치면 화면과 경보가 어긋난다")
    void 소비자_두곳이_타임프레임을_쓴다() {
        String logController = read(Path.of(
                "src/main/java/com/cryptoautotrader/api/controller/LogController.java"));
        String watchdog = read(Path.of(
                "src/main/java/com/cryptoautotrader/api/service/StrategyDegradationWatchdog.java"));

        assertThat(logController)
                .as("LogController.buildByStrategy 가 타임프레임 없이 그룹핑한다")
                .contains("l.getTimeframe()");
        assertThat(watchdog)
                .as("StrategyDegradationWatchdog.groupByKey 가 타임프레임 없이 그룹핑한다")
                .contains("l.getTimeframe()");
    }

    @Test
    @DisplayName("세 매매 엔진이 로그를 쓸 때 timeframe 을 채운다 — 하나라도 빠지면 그 엔진 신호가 '?' 로 샌다")
    void 세_엔진이_타임프레임을_기록한다() {
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            String src = read(Path.of(
                    "src/main/java/com/cryptoautotrader/api/service/" + engine + "TradingService.java"));
            assertThat(src)
                    .as("%sTradingService 가 StrategyLogEntity 에 timeframe 을 채우지 않는다", engine)
                    .contains(".timeframe(session.getTimeframe())");
        }
    }

    @Test
    @DisplayName("V76 마이그레이션이 컬럼과 인덱스를 함께 만든다")
    void 마이그레이션이_존재한다() {
        String sql = read(MIGRATION);
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS timeframe");
        assertThat(sql)
                .as("(전략, 코인, 타임프레임) 집계가 기본 경로가 되므로 인덱스가 함께 있어야 한다")
                .contains("idx_strategy_log_strategy_coin_tf");
    }
}
