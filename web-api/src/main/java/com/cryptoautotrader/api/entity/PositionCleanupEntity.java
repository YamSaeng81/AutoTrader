package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 정지가 남긴 포지션의 <b>정리 대기</b> (V87, 2026-10-13).
 *
 * <p>정지는 그 시점에 열린 포지션만 청산한다. 진행 중이던 매수가 정지 후에 체결되면 수량이 생긴
 * 포지션이 정지된 세션에 남고, 평가 루프도 정지 청산도 다시 보지 않는다. 이 행이 있는 포지션은
 * 정리 작업({@code LiveTradingService}·{@code DynamicTradingService} 의 정리 스케줄)이 정지의 청산
 * 정책으로 닫을 때까지 맡는다.</p>
 *
 * <p>🔴 포지션 행의 열이 아니라 별도 행이다 — 포지션을 전 열로 저장하는 경로가 여럿이라 그 저장이
 * 대기 표시를 이전 값으로 되돌릴 수 있다.</p>
 *
 * <p>🔴 {@code doneAt} 은 포지션이 {@code CLOSED} 로 <b>확인될 때만</b> 쓴다. 매도를 제출했다는
 * 사실로 완료 처리하지 않는다.</p>
 */
@Entity
@Table(name = "position_cleanup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionCleanupEntity {

    /** 정지 시점에 열려 있던 포지션. */
    public static final String REASON_STOPPED_OPEN = "STOPPED_OPEN";
    /** 정지 후 늦은 체결이 반영된 포지션. */
    public static final String REASON_LATE_FILL = "LATE_FILL";

    @Id
    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "session_kind", nullable = false, length = 10)
    private String sessionKind;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "pending_at", nullable = false)
    private Instant pendingAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_note", length = 500)
    private String lastNote;
}
