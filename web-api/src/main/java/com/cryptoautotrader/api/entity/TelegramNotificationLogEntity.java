package com.cryptoautotrader.api.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "telegram_notification_log")
public class TelegramNotificationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "session_label", length = 100)
    private String sessionLabel;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "sent_at")
    private Instant sentAt;

    public TelegramNotificationLogEntity() {}

    /** 서비스에서 신규 레코드 생성 시 사용하는 생성자 */
    public TelegramNotificationLogEntity(String type, String sessionLabel, String messageText, boolean success) {
        this.type         = type;
        this.sessionLabel = sessionLabel;
        this.messageText  = messageText;
        this.success      = success;
    }

    @PrePersist
    void prePersist() {
        if (sentAt == null) sentAt = Instant.now();
    }

    public Long    getId()           { return id; }
    public String  getType()         { return type; }
    public String  getSessionLabel() { return sessionLabel; }
    public String  getMessageText()  { return messageText; }
    public boolean isSuccess()       { return success; }
    public Instant getSentAt()       { return sentAt; }
}
