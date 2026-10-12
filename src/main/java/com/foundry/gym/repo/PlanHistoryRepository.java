package com.foundry.gym.repo;

import java.time.Instant;
import java.util.List;

import com.foundry.gym.domain.PlanHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanHistoryRepository extends JpaRepository<PlanHistory, Long> {

    List<PlanHistory> findByUserIdOrderByStartedAtDescIdDesc(Long userId);

    List<PlanHistory> findByUserIdAndEndedAtIsNull(Long userId);

    /** Used before a plan is deleted: ends the open period of every member who is on it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PlanHistory h set h.endedAt = :now where h.endedAt is null "
        + "and h.userId in (select u.id from AppUser u where u.plan.id = :planId)")
    int closeOpenForPlan(@Param("planId") Long planId, @Param("now") Instant now);
}
