package com.foundry.gym.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Which emails a member agreed to receive. Everything is off until the member turns it on. */
@Entity
@Table(name = "member_preference")
public class MemberPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** Marketing material. */
    @Column(nullable = false)
    private boolean marketing;

    /** A new plan is available. */
    @Column(name = "new_plans", nullable = false)
    private boolean newPlans;

    /** The plan is nearly used up: 2 visits remain this month. */
    @Column(name = "plan_ending", nullable = false)
    private boolean planEnding;

    /** After the 21st of the month, a reminder if visits are still left. */
    @Column(name = "visits_reminder", nullable = false)
    private boolean visitsReminder;

    protected MemberPreference() {
    }

    public MemberPreference(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public boolean isMarketing() { return marketing; }
    public void setMarketing(boolean v) { this.marketing = v; }
    public boolean isNewPlans() { return newPlans; }
    public void setNewPlans(boolean v) { this.newPlans = v; }
    public boolean isPlanEnding() { return planEnding; }
    public void setPlanEnding(boolean v) { this.planEnding = v; }
    public boolean isVisitsReminder() { return visitsReminder; }
    public void setVisitsReminder(boolean v) { this.visitsReminder = v; }
}
