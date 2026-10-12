package com.foundry.gym.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One period during which a member held a plan. The plan's name, price and visits are copied at the time, so the
 * history stays correct when the plan is later renamed, repriced or deleted. endedAt is null while it is active.
 */
@Entity
@Table(name = "plan_history", indexes = @Index(name = "idx_plan_history_user", columnList = "user_id, started_at"))
public class PlanHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_name", nullable = false, length = 60)
    private String planName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "visits_per_month", nullable = false)
    private int visitsPerMonth;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected PlanHistory() {
    }

    public PlanHistory(Long userId, Plan plan, Instant startedAt) {
        this.userId = userId;
        this.planName = plan.getName();
        this.price = plan.getPrice();
        this.visitsPerMonth = plan.getVisitsPerMonth();
        this.startedAt = startedAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getPlanName() { return planName; }
    public BigDecimal getPrice() { return price; }
    public int getVisitsPerMonth() { return visitsPerMonth; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
}
