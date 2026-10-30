package com.foundry.gym.repo;

import java.time.LocalDate;
import java.util.List;

import com.foundry.gym.domain.ClosedPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosedPeriodRepository extends JpaRepository<ClosedPeriod, Long> {

    /** Periods of one gym that have not ended before the given date. */
    List<ClosedPeriod> findByGymIdAndEndDateGreaterThanEqualOrderByStartDateAsc(Long gymId, LocalDate date);

    /** Periods of one gym that include the given date. */
    List<ClosedPeriod> findByGymIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(Long gymId, LocalDate start, LocalDate end);

    List<ClosedPeriod> findByGymIdAndKindAndStartDate(Long gymId, String kind, LocalDate startDate);
}
