package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "discord_send_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscordSendLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_type", nullable = false, length = 30)
    private String channelType;

    /** MORNING_BRIEFING / ALERT / MANUAL */
    @Column(name = "message_type", nullable = false, length = 30)
    @Builder.Default
    private String messageType = "MORNING_BRIEFING";

    /** PENDING / SUCCESS / FAILED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "message_preview", columnDefinition = "TEXT")
    private String messagePreview;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
