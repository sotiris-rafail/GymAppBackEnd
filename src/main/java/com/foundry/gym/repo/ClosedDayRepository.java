package com.foundry.gym.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.foundry.gym.domain.ClosedDay;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosedDayRepository extends JpaRepository<ClosedDay, Long> {

    Optional<ClosedDay> findByClosedOn(LocalDate day);

    boolean existsByClosedOn(LocalDate day);

    List<ClosedDay> findAllByOrderByClosedOnAsc();

    List<ClosedDay> findByClosedOnBetween(LocalDate from, LocalDate to);
}
