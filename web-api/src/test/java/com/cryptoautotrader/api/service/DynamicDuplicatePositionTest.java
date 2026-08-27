package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.PositionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.cryptoautotrader.api.service.DynamicTradingService.pickMonitoredPosition;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-27 P0 회귀 테스트 — <b>중복 OPEN 포지션에서 세션이 죽지 않는다</b>.
 *
 * <p><b>사고 재현</b>: 세션 60(DYN_PAPER, COMPOSITE_MOMENTUM_ICHIMOKU_V2 H1)이 KRW-STX 포지션
 * 2852(08-23 13:46 진입)를 든 채 {@code scan_state} 가 SCANNING 으로 어긋나 있었다. 그 결과:</p>
 * <ol>
 *   <li>포지션 2852 가 SL/TP/time stop 감시에서 <b>통째로 빠져</b> 59시간+ 방치됐다
 *       ({@code maxHoldHours=24} 였는데도 — time stop 은 감시 틱에서만 체크된다)</li>
 *   <li>세션이 자기가 포지션 없다고 알고 08-26 03:00 <b>같은 코인을 또 매수</b>해 포지션 2997 생성</li>
 *   <li>{@code processMonitoringTick} 의 {@code Optional} 조회가 2건에서
 *       {@code IncorrectResultSizeDataAccessException} 을 던져 <b>세션이 21시간 영구 정지</b>했다.
 *       {@code tick()} 이 세션별로 예외를 삼키므로 앱은 멀쩡했고 아무 알림도 없었다.</li>
 * </ol>
 *
 * <p>①②는 {@code processTick} 의 자가복구 가드가, ③은 {@code pickMonitoredPosition} 이 막는다.
 * 이 테스트는 ③의 선택 규칙을 잠근다.</p>
 */
class DynamicDuplicatePositionTest {

    private static PositionEntity pos(Long id, String coin, Instant openedAt) {
        PositionEntity p = new PositionEntity();
        p.setId(id);
        p.setCoinPair(coin);
        p.setOpenedAt(openedAt);
        p.setStatus("OPEN");
        return p;
    }

    /** 운영 실측 재현 — 목록은 리포지터리와 같이 openedAt ASC 순서다. */
    private static final PositionEntity OLDER =
            pos(2852L, "KRW-STX", Instant.parse("2026-08-23T13:46:20Z"));
    private static final PositionEntity NEWER =
            pos(2997L, "KRW-STX", Instant.parse("2026-08-26T03:00:35Z"));

    @Test
    @DisplayName("포지션이 없으면 빈 값 — 기존 SCANNING 복귀 경로가 그대로 동작한다")
    void empty_returnsEmpty() {
        assertThat(pickMonitoredPosition(60L, null, List.of())).isEmpty();
    }

    @Test
    @DisplayName("정상(1건)이면 그 포지션을 그대로 쓴다 — 부작용 없음")
    void single_returnsIt() {
        assertThat(pickMonitoredPosition(60L, 2997L, List.of(NEWER))).containsSame(NEWER);
    }

    @Test
    @DisplayName("🔴 중복 2건이어도 예외 없이 하나를 고른다 — 세션 60의 21시간 정지 재발 방지")
    void duplicate_doesNotThrow() {
        Optional<PositionEntity> picked =
                pickMonitoredPosition(60L, 2997L, List.of(OLDER, NEWER));
        assertThat(picked).isPresent();
    }

    @Test
    @DisplayName("중복이면 세션이 가리키는 currentPositionId 를 우선 채택한다 — 회계가 그 기준으로 맞춰져 있다")
    void duplicate_prefersCurrentPositionId() {
        assertThat(pickMonitoredPosition(60L, 2997L, List.of(OLDER, NEWER))).containsSame(NEWER);
    }

    @Test
    @DisplayName("currentPositionId 가 목록에 없으면 가장 오래된 것 — time stop·손절이 더 급한 쪽이다")
    void duplicate_fallsBackToOldest() {
        // 세션이 이미 사라진 포지션을 가리키는 상태 (9999)
        assertThat(pickMonitoredPosition(60L, 9999L, List.of(OLDER, NEWER)))
                .as("목록은 openedAt ASC 이므로 첫 원소가 가장 오래된 것")
                .containsSame(OLDER);
    }

    @Test
    @DisplayName("currentPositionId 가 null 이어도 죽지 않고 가장 오래된 것을 쓴다")
    void duplicate_nullCurrentId_usesOldest() {
        assertThat(pickMonitoredPosition(60L, null, List.of(OLDER, NEWER))).containsSame(OLDER);
    }

    @Test
    @DisplayName("3건 이상이어도 규칙이 유지된다 — 상한 없이 방어한다")
    void triplicate_stillPicks() {
        PositionEntity third = pos(3100L, "KRW-STX", Instant.parse("2026-08-27T01:00:00Z"));
        assertThat(pickMonitoredPosition(60L, 3100L, List.of(OLDER, NEWER, third)))
                .containsSame(third);
        assertThat(pickMonitoredPosition(60L, null, List.of(OLDER, NEWER, third)))
                .containsSame(OLDER);
    }
}
