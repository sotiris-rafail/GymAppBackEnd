package com.foundry.gym.repo;

import java.util.List;
import java.util.Optional;

import com.foundry.gym.domain.Gym;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GymRepository extends JpaRepository<Gym, Long> {

    Optional<Gym> findByDefaultGymTrue();

    List<Gym> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByNameIgnoreCase(String name);
}
