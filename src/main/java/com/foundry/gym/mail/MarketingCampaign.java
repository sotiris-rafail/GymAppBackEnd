package com.foundry.gym.mail;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A marketing email the admin wrote on the Marketing page. A draft can be edited; once sent it is a record. */
@Entity
@Table(name = "marketing_campaign")
public class MarketingCampaign {

    public enum Status { DRAFT, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String subject;

    @Column(nullable = false, length = 3000)
    private String body;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** How many members it was queued for when it was sent. */
    @Column(nullable = false)
    private int recipients;

    /** Who it goes to: the members of this gym; empty = every member. */
    @Column(name = "gym_id")
    private Long gymId;

    protected MarketingCampaign() {
    }

    public MarketingCampaign(String subject, String body, String linkUrl, Instant createdAt) {
        this.subject = subject;
        this.body = body;
        this.linkUrl = linkUrl;
        this.createdAt = createdAt;
    }

    public Long getGymId() { return gymId; }

    public void setGymId(Long gymId) { this.gymId = gymId; }

    public void edit(String subject, String body, String linkUrl) {
        this.subject = subject;
        this.body = body;
        this.linkUrl = linkUrl;
    }

    public void markSent(Instant at, int recipients) {
        this.status = Status.SENT;
        this.sentAt = at;
        this.recipients = recipients;
    }

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getLinkUrl() { return linkUrl; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public int getRecipients() { return recipients; }
}
