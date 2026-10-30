package com.foundry.gym.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A date on which the gym is closed: no class can be booked. Sundays are always closed and are not stored. */
@Entity
@Table(name = "closed_day")
public class ClosedDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gym_id", nullable = false)
    private Long gymId = Gym.FIRST_ID;

    @Column(name = "closed_on", nullable = false)
    private LocalDate closedOn;

    @Column(length = 80)
    private String reason;

    protected ClosedDay() {
    }

    public ClosedDay(Long gymId, LocalDate day, String reason) {
        this.gymId = gymId;
        this.closedOn = day;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Long getGymId() { return gymId; }
    public LocalDate getClosedOn() { return closedOn; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
