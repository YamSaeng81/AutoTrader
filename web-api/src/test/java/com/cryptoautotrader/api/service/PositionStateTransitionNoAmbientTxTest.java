package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회귀 테스트 — <b>트랜잭션 없는 호출부에서 포지션 원자적 전환이 동작해야 한다</b> (2026-10-07).
 *
 * <p>{@code LiveTradingService.executeStrategies()} 와 {@code DynamicTradingService.tick()} 은
 * {@code @Transactional} 이 없는 60초 스케줄러다. 그 경로에서
 * {@code PositionRepository.markClosingIfOpen}({@code @Modifying(flushAutomatically = true)})을
 * <b>직접</b> 부르면 flush 가 활성 트랜잭션을 요구해 실패했고, 그 결과 <b>LIVE 201 KRW-DOGE 가
 * 126시간(한도 24시간) 동안 청산되지 못했다.</b> 사고 경위는 {@link PositionStateTransition}
 * javadoc 에 있다.</p>
 *
 * <p>🔴 이 테스트는 <b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트가
 * 트랜잭션을 제공해 버려서 정확히 재현하려는 조건이 사라지고, 결함이 있어도 통과한다.</p>
 */
@DisplayName("포지션 원자적 전환은 트랜잭션 없는 호출부에서도 동작한다")
class PositionStateTransitionNoAmbientTxTest extends IntegrationTestBase {

    @Autowired
    private PositionStateTransition positionStateTransition;

    @Autowired
    private PositionRepository positionRepository;

    private Long newOpenPosition() {
        PositionEntity pos = PositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("100.00000000"))
                .status("OPEN")
                .sessionKind("LIVE")
                .sessionId(201L)
                .openedAt(Instant.now())
                .build();
        return positionRepository.save(pos).getId();
    }

    @Test
    @DisplayName("markClosingIfOpen — 트랜잭션 없이 불러도 CLOSING 으로 전환되고 사유가 남는다")
    void markClosingIfOpen_withoutAmbientTransaction() {
        Long id = newOpenPosition();

        int marked = positionStateTransition.markClosingIfOpen(
                id, Instant.now(), ExitReason.TIME_STOP);

        assertThat(marked).isEqualTo(1);
        PositionEntity after = positionRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("CLOSING");
        assertThat(after.getExitReason()).isEqualTo(ExitReason.TIME_STOP);
        assertThat(after.getClosingAt()).isNotNull();
    }

    @Test
    @DisplayName("두 번째 호출은 0 을 돌려준다 — 시장가 이중 매도를 막는 보장이 유지된다")
    void markClosingIfOpen_isIdempotentGuard() {
        Long id = newOpenPosition();

        assertThat(positionStateTransition.markClosingIfOpen(id, Instant.now(), ExitReason.TIME_STOP))
                .isEqualTo(1);
        // 이미 CLOSING 이므로 두 번째 경로는 주문을 내지 않아야 한다
        assertThat(positionStateTransition.markClosingIfOpen(id, Instant.now(), ExitReason.STOP_LOSS))
                .isZero();
    }

    @Test
    @DisplayName("closeIfOpen — 트랜잭션 없이 불러도 CLOSED 로 전환된다")
    void closeIfOpen_withoutAmbientTransaction() {
        Long id = newOpenPosition();

        assertThat(positionStateTransition.closeIfOpen(id, Instant.now())).isEqualTo(1);

        PositionEntity after = positionRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("CLOSED");
        assertThat(after.getClosedAt()).isNotNull();
    }

    /**
     * 🔴 <b>결함 자체를 고정한다.</b> 리포지토리를 직접 부르면 여전히 실패해야 한다 — 그래야 누군가
     * 나중에 {@code positionStateTransition} 을 우회해 {@code positionRepository} 를 직접 쓰는
     * 변경을 넣었을 때 이 테스트가 <b>왜 헬퍼를 거쳐야 하는지</b>를 설명해 준다.
     *
     * <p>만약 Spring Data 의 동작이 바뀌어 이 호출이 더는 던지지 않게 되면 이 테스트가 깨진다.
     * 그때는 헬퍼가 불필요해졌다는 뜻이므로 <b>테스트를 지우기 전에 그 사실을 확인</b>해야 한다.</p>
     */
    @Test
    @DisplayName("리포지토리 직접 호출은 트랜잭션 없이 실패한다 — 헬퍼를 거쳐야 하는 이유")
    void repositoryDirectCall_stillFailsWithoutTransaction() {
        Long id = newOpenPosition();

        assertThatThrownBy(() -> positionRepository.markClosingIfOpen(
                id, Instant.now(), ExitReason.TIME_STOP))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("flush");

        // 전환이 일어나지 않았음을 확인 — 실패가 조용히 성공으로 보이지 않아야 한다
        assertThat(positionRepository.findById(id).orElseThrow().getStatus()).isEqualTo("OPEN");
    }
}
