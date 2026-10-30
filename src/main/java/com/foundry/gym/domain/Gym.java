package com.foundry.gym.domain;

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
 * A place with classes. Exactly one gym is the default: it is the only gym while multi-gym is switched off in the
 * configuration server, and the fallback for everything that names no gym.
 */
@Entity
@Table(name = "gym")
public class Gym {

    /** The gym every installation starts with (created by the migration). Only used as a safe fallback in entities. */
    public static final long FIRST_ID = 1L;

    public enum Status {
        /** Visible and bookable. */
        ACTIVE,
        /** Visible, takes no new bookings; the ones that exist still stand. */
        CLOSING,
        /** Hidden, read-only history. */
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 160)
    private String address;

    /** Empty = the application's own time zone (gym.timezone). */
    @Column(name = "time_zone", length = 40)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "is_default", nullable = false)
    private boolean defaultGym;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Gym() {
    }

    public Gym(String name, String address, String timeZone, int sortOrder) {
        this.name = name;
        this.address = address;
        this.timeZone = timeZone;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isDefaultGym() { return defaultGym; }
    public void setDefaultGym(boolean defaultGym) { this.defaultGym = defaultGym; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
