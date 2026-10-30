package com.foundry.gym.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.foundry.gym.domain.ClosedDay;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosedDayRepository extends JpaRepository<ClosedDay, Long> {

    Optional<ClosedDay> findByGymIdAndClosedOn(Long gymId, LocalDate day);

    boolean existsByGymIdAndClosedOn(Long gymId, LocalDate day);

    List<ClosedDay> findByGymIdOrderByClosedOnAsc(Long gymId);

    List<ClosedDay> findByGymIdAndClosedOnBetween(Long gymId, LocalDate from, LocalDate to);
}
