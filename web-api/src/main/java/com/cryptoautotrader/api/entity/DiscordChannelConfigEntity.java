package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "discord_channel_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscordChannelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TRADING_REPORT / CRYPTO_NEWS / ECONOMY_NEWS / ALERT */
    @Column(name = "channel_type", nullable = false, unique = true, length = 30)
    private String channelType;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
