package com.foundry.gym.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A whole week (Monday to Sunday) or calendar month on which the admin closed bookings. */
@Entity
@Table(name = "closed_period")
public class ClosedPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** WEEK or MONTH. */
    @Column(nullable = false, length = 5)
    private String kind;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    protected ClosedPeriod() {
    }

    public ClosedPeriod(String kind, LocalDate startDate, LocalDate endDate) {
        this.kind = kind;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
}
