package com.foundry.gym.repo;

import java.time.LocalDate;
import java.util.List;

import com.foundry.gym.domain.ClosedPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosedPeriodRepository extends JpaRepository<ClosedPeriod, Long> {

    /** Periods that have not ended before the given date. */
    List<ClosedPeriod> findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate date);

    /** Periods that include the given date. */
    List<ClosedPeriod> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate start, LocalDate end);

    List<ClosedPeriod> findByKindAndStartDate(String kind, LocalDate startDate);
}
