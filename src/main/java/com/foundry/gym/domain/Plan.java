package com.foundry.gym.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A monthly subscription: what it costs and how many class visits it includes per calendar month. */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "visits_per_month", nullable = false)
    private int visitsPerMonth;

    /** No visit limit. visitsPerMonth is kept at 0 for such a plan and must not be read as "no visits". */
    @Column(nullable = false)
    private boolean unlimited;

    protected Plan() {
    }

    public Plan(String name, BigDecimal price, int visitsPerMonth) {
        this.name = name;
        this.price = price;
        this.visitsPerMonth = visitsPerMonth;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public boolean isUnlimited() { return unlimited; }
    public void setUnlimited(boolean unlimited) { this.unlimited = unlimited; }
    public int getVisitsPerMonth() { return visitsPerMonth; }
    public void setVisitsPerMonth(int visitsPerMonth) { this.visitsPerMonth = visitsPerMonth; }
}
