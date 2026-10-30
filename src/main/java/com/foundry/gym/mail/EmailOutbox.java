package com.foundry.gym.mail;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An email waiting to be sent, or already sent. The jobs that decide <em>who gets what</em> only add rows here; a second
 * job sends them. The pair (user, dedupe key) is unique, which is what stops anyone getting the same email twice
 * (for example "plan 7 is new", or "the visits reminder for 2026-10"). The address is not stored: it is read, and
 * decrypted, from the account at the moment of sending.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailKind kind;

    @Column(name = "dedupe_key", nullable = false, length = 60)
    private String dedupeKey;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    protected EmailOutbox() {
    }

    public EmailOutbox(Long userId, EmailKind kind, String dedupeKey, String subject, String body, Instant now) {
        this.userId = userId;
        this.kind = kind;
        this.dedupeKey = dedupeKey;
        this.subject = subject.length() > 200 ? subject.substring(0, 200) : subject;
        this.body = body.length() > 4000 ? body.substring(0, 4000) : body;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markSent(Instant now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    /** A failed try: pause (1, 2, 4... minutes, at most an hour), and give up after maxAttempts. */
    public void markFailed(Instant now, String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? "failed" : error.length() > 300 ? error.substring(0, 300) : error;
        if (attempts >= maxAttempts) {
            this.status = Status.FAILED;
        } else {
            this.nextAttemptAt = now.plusSeconds(Math.min(3600L, 60L << Math.min(attempts - 1, 6)));
        }
    }

    /** The member switched the email off, or no longer exists, before it was sent. */
    public void markSkipped() {
        this.status = Status.SKIPPED;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public EmailKind getKind() { return kind; }
    public String getDedupeKey() { return dedupeKey; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
}
