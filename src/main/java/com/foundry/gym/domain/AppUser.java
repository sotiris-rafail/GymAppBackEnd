package com.foundry.gym.domain;

import jakarta.persistence.Column;
import com.foundry.gym.security.EmailConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A login: either a gym member (with an optional plan) or the admin. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    /** Stored encrypted (see EmailConverter); the code always sees the plain address. */
    @Convert(converter = EmailConverter.class)
    @Column(nullable = false, length = 600)
    private String email;

    /** Searchable fingerprint of the address: the ciphertext differs every time, so lookups use this (unique). */
    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    /** The gym a member sees first; empty = the default gym. */
    @Column(name = "home_gym_id")
    private Long homeGymId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    protected AppUser() {
    }

    public AppUser(String name, String email, String emailHash, String passwordHash, Role role) {
        this.name = name;
        this.email = email;
        this.emailHash = emailHash;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getEmailHash() { return emailHash; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public Long getHomeGymId() { return homeGymId; }
    public void setHomeGymId(Long homeGymId) { this.homeGymId = homeGymId; }
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
}
