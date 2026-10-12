package com.foundry.gym.repo;

import com.foundry.gym.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {
}
