package com.cryptoautotrader.core.risk;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 전략 폐기 기준(kill criteria) 임계값 — 단일 출처.
 *
 * <p>정책 문서: {@code docs/KILL_CRITERIA.md}. <b>임계값을 여기서 바꾸기 전에 그 문서의
 * §7(기준 자체를 바꾸는 절차)을 먼저 읽을 것</b> — 완화는 발동 이력이 있는 전략을 살리려는
 * 목적으로는 허용되지 않으며, 독립적 근거를 문서에 함께 기록해야 한다.
 *
 * <h3>왜 필요한가</h3>
 * <p>2026-08-06 벤치마크 측정에서 실전 검증을 통과한 전략이 22개 중 0개, 알파는 음수,
 * 11일 실현 거래 승률 0/7 로 드러났다. 문제는 성적 자체가 아니라 <b>폐기 조건이 없어
 * 나쁜 전략을 무한히 고쳐 쓰는 루프</b>였다. 기준이 없으면 손실은 항상 "아직 표본이
 * 부족해서"로 설명되고, 표본은 영원히 부족하다.
 *
 * <h3>두 종류를 섞지 않는다</h3>
 * <ul>
 *   <li><b>A. 자본 보호</b>({@code capitalLossPct}, {@code maxDrawdownPct},
 *       {@code circuitBreakerRepeatLimit}) — "더 잃어도 되는가". 손실 한도는 사전 약속이므로
 *       통계적 유의성이 필요 없다. 여기에 "표본이 부족하다"를 적용하면 표본을 모으는 동안
 *       자본이 소진된다.</li>
 *   <li><b>B. 엣지 안전망</b>({@code minTradesForEdgeTest}) — "우위가 있는가". 표본이 필요하다.</li>
 * </ul>
 *
 * <h3>B의 임계값이 느슨한 이유</h3>
 * <p>현재 거래 속도는 10세션 11일에 실현 거래 7건 — 세션당 연 23건 수준이다. EV의 부호를
 * 신뢰할 표본은 실전에서 수년이 걸린다. 따라서 <b>실자본 운영으로는 엣지를 통계적으로
 * 검증할 수 없고</b>, 그 역할은 {@code WalkForwardValidationGate}(out-of-sample 기대값)가
 * 맡는다. 여기 B 기준은 엣지를 증명하는 시험이 아니라 <b>실전이 백테스트를 명백히
 * 배신했을 때 잡는 안전망</b>이다.
 */
@Getter
@Builder
public class KillCriteriaConfig {

    // ── A. 자본 보호 (표본 무관) ──────────────────────────────────

    /**
     * 누적 손실 한도 (%) — 총자산이 초기자본 대비 이 비율 이하로 떨어지면 폐기.
     *
     * <p>세션당 10,000원 기준 −1,500원. 원금의 1/6을 잃고도 유지할 근거는
     * "곧 회복될 것"이라는 기대뿐이고, 그 기대에는 근거가 없다.
     */
    @Builder.Default
    private final BigDecimal capitalLossPct = new BigDecimal("-15.0");

    /**
     * 최대 낙폭 한도 (%) — 세션 <b>최고</b> 총자산 대비 하락폭이 이 이하면 폐기.
     *
     * <p>초기자본이 아니라 고점 기준이라 "한 번 벌었다가 토해내는" 패턴을 잡는다.
     * {@link #capitalLossPct}보다 5%p 느슨한 것은 고점이 초기자본보다 높기 때문이다.
     */
    @Builder.Default
    private final BigDecimal maxDrawdownPct = new BigDecimal("-20.0");

    /**
     * 서킷브레이커 <b>누적</b> 발동 한도 — {@code circuit_breaker_trip_count}(V69)가 이 값 이상이면 폐기.
     *
     * <p>서킷브레이커(연속 손실 5회, {@code RiskConfigEntity.consecutiveLossLimit})는
     * <i>일시정지</i>다. 발동 즉시 세션이 EMERGENCY_STOPPED 되고 재시작은 수동이므로,
     * 누적 3회는 "사람이 세 번 되살렸는데 세 번 다시 죽었다"는 뜻이다 — 일시적 국면이 아니라
     * 구조적 결함이다. 연속 손실 자체를 여기서 다시 세지 않는 이유는 서킷브레이커와
     * 중복 판정이 되기 때문이다.
     */
    @Builder.Default
    private final int circuitBreakerRepeatLimit = 3;

    // ── B. 엣지 안전망 (표본 필요) ────────────────────────────────

    /**
     * 엣지 판정 최소 표본 — 세션의 CLOSED 포지션(invested_krw &gt; 0) 수가 이 미만이면
     * {@code NEGATIVE_EV}/{@code NEGATIVE_ALPHA} 판정을 생략한다.
     *
     * <p>승률이 아니라 <b>기대값</b>으로 본다. 승률 30%라도 손익비 3:1이면 정상이므로
     * 승률 단독 폐기 기준은 두지 않는다.
     */
    @Builder.Default
    private final int minTradesForEdgeTest = 20;

    // ── C. 판정 불가 (경보만) ─────────────────────────────────────

    /** {@code NO_SIGNAL} 경보 기준 — 이 일수 이상 운영. */
    @Builder.Default
    private final int noSignalDays = 30;

    /** {@code NO_SIGNAL} 경보 기준 — 종료 거래가 이 건수 미만. */
    @Builder.Default
    private final int noSignalMinTrades = 5;

    /** 기본 설정 — 운영에서 쓰는 값. */
    public static KillCriteriaConfig defaults() {
        return KillCriteriaConfig.builder().build();
    }
}
