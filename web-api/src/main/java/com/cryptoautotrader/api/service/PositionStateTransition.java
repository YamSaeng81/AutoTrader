package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.repository.PositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 포지션 상태의 <b>원자적 전환</b>을 항상 트랜잭션 안에서 실행한다 (2026-10-07 신설).
 *
 * <h3>왜 필요한가 — 실자금 청산이 7주 넘게 실패하고 있었다</h3>
 *
 * <p>{@link PositionRepository#markClosingIfOpen} 과 {@link PositionRepository#closeIfOpen} 은
 * {@code @Modifying(clearAutomatically = true, flushAutomatically = true)} 다. {@code flushAutomatically}
 * 는 UPDATE 전에 {@code EntityManager.flush()} 를 부르고, <b>flush 는 활성 트랜잭션을 요구한다.</b>
 * 트랜잭션 없는 곳에서 호출하면 다음으로 실패한다:</p>
 *
 * <pre>
 * InvalidDataAccessApiUsageException: No EntityManager with actual transaction
 *   available for current thread - cannot reliably process 'flush' call
 * </pre>
 *
 * <p>사고 경위 — 두 변경이 7개월 간격으로 겹쳐 생긴 결함이다:</p>
 * <ol>
 *   <li><b>2026-03-17</b> {@code 31df1b8} 이 {@code LiveTradingService.executeStrategies()}(60초
 *       스케줄러)에서 {@code @Transactional} 을 지웠다. 그때 청산 경로는
 *       {@code setStatus("CLOSING") + save()} 였고, {@code save()} 는 스스로 트랜잭션을 열기
 *       때문에 <b>아무 문제가 드러나지 않았다.</b></li>
 *   <li><b>2026-08-18</b> 중복 시장가 매도(LIVE 198 이 60초 간격으로 SELL 8724·8725 를 연달아
 *       제출한 사고)를 막으려고 그 자리를 {@code markClosingIfOpen} 으로 교체했다. 이 순간부터
 *       <b>스케줄러가 부르는 모든 청산이 실패한다</b> — 손절·익절·time stop·신호 매도 전부.</li>
 * </ol>
 *
 * <p>실측 피해: <b>LIVE 세션 201 KRW-DOGE</b> 가 보유 <b>126시간</b>(한도 24시간) 동안 청산되지
 * 못했다. 매 60초 time stop 분기를 다시 타면서 텔레그램 알림이 <b>하루 1,442건</b>(4일간 약
 * 5,800건) 쏟아졌고, 같은 예외로 평가 루프가 중단돼 <b>그 세션은 다른 신호도 처리하지 못했다.</b>
 * 알림 홍수는 증상이고 본질은 <b>실자금 청산 불능</b>이다.</p>
 *
 * <h3>왜 스케줄러에 {@code @Transactional} 을 다시 붙이지 않는가</h3>
 *
 * <p>{@code executeStrategies()} 는 RUNNING 세션 <b>전체</b>를 한 메서드에서 순회한다. 거기에
 * 트랜잭션을 걸면 ① 60초 주기 작업 전체가 한 트랜잭션이 되어 커넥션을 오래 잡고, ② 한 세션의
 * 예외가 트랜잭션을 rollback-only 로 만들어 <b>다른 세션의 처리까지 되돌린다.</b> 2026-03-17 에
 * 지운 이유도 이 계열일 것이다. 그래서 <b>작업 단위를 전환 그 자체로 좁혀</b> 여기서 감싼다.</p>
 *
 * <p>전파는 {@link TransactionDefinition#PROPAGATION_REQUIRED} 다. 이미 트랜잭션 안인 호출부
 * (reconcile 스케줄러, {@code stopSession}, 웹소켓 손절 경로)는 <b>그 트랜잭션에 그대로
 * 참여하므로 기존 동작이 바뀌지 않는다.</b> REQUIRES_NEW 를 쓰면 바깥이 롤백돼도 전환만 커밋돼
 * 포지션이 CLOSING 인데 나머지 처리가 없는 상태가 생길 수 있다 — 그래서 쓰지 않는다.</p>
 *
 * <p>🔴 private 메서드에 {@code @Transactional} 을 붙이는 방법은 쓸 수 없다. 자기호출은 Spring
 * 프록시를 타지 않아 애노테이션이 <b>조용히 무시된다</b>({@code RulesetRegistry} 가 같은 이유로
 * {@link TransactionTemplate} 을 쓴다). 프록시를 거치지 않는 이 방식이 유일하게 확실하다.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PositionStateTransition {

    private final PositionRepository positionRepository;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate requiredTx;

    @PostConstruct
    void initTxTemplate() {
        this.requiredTx = new TransactionTemplate(txManager);
        this.requiredTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * {@code status='OPEN'} 인 경우에만 {@code CLOSING} 으로 전환하고 청산 사유를 기록한다.
     *
     * @return 전환된 행 수. <b>0 이면 이미 다른 경로가 매도 중이므로 주문을 내지 말아야 한다.</b>
     */
    public int markClosingIfOpen(Long positionId, Instant now, ExitReason reason) {
        return requiredTx.execute(st ->
                positionRepository.markClosingIfOpen(positionId, now,
                        reason != null ? reason : ExitReason.UNKNOWN));
    }

    /** 사유 없이 {@code CLOSING} 전환 — 사유를 모르는 호출부용. */
    public int markClosingIfOpen(Long positionId, Instant now) {
        return requiredTx.execute(st -> positionRepository.markClosingIfOpen(positionId, now));
    }

    /**
     * {@code status='OPEN'} 인 경우에만 {@code CLOSED} 로 전환한다.
     *
     * @return 전환된 행 수. 0 이면 이미 정리된 포지션이므로 <b>KRW 복원을 중복 실행하지 말아야 한다.</b>
     */
    public int closeIfOpen(Long positionId, Instant now) {
        return requiredTx.execute(st -> positionRepository.closeIfOpen(positionId, now));
    }
}
