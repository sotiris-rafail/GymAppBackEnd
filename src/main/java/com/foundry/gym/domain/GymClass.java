package com.foundry.gym.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/** A class that repeats weekly. Every session of it holds at most {@code capacity} people. */
@Entity
@Table(name = "gym_class")
public class GymClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(length = 40)
    private String coach;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private int capacity;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "gym_class_day", joinColumns = @JoinColumn(name = "gym_class_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 12)
    private Set<DayOfWeek> days = new LinkedHashSet<>();

    protected GymClass() {
    }

    public GymClass(String name, String coach, LocalTime startTime, int durationMinutes, int capacity, Set<DayOfWeek> days) {
        this.name = name;
        this.coach = coach;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.capacity = capacity;
        this.days = new LinkedHashSet<>(days);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCoach() { return coach; }
    public void setCoach(String coach) { this.coach = coach; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public Set<DayOfWeek> getDays() { return days; }
    public void setDays(Set<DayOfWeek> days) {
        this.days.clear();
        this.days.addAll(days);
    }
}
