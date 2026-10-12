package com.foundry.gym.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** One member's seat in one session (a class on a specific date). */
@Entity
@Table(
    name = "booking",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "gym_class_id", "session_date"}),
    indexes = {
        @Index(name = "idx_booking_session", columnList = "gym_class_id, session_date"),
        @Index(name = "idx_booking_user_date", columnList = "user_id, session_date")
    })
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gym_class_id")
    private GymClass gymClass;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    protected Booking() {
    }

    public Booking(AppUser user, GymClass gymClass, LocalDate sessionDate) {
        this.user = user;
        this.gymClass = gymClass;
        this.sessionDate = sessionDate;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public GymClass getGymClass() { return gymClass; }
    public LocalDate getSessionDate() { return sessionDate; }
}
