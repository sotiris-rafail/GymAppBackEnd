package com.foundry.gym.repo;

import java.util.Optional;

import com.foundry.gym.domain.GymClass;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GymClassRepository extends JpaRepository<GymClass, Long> {

    java.util.List<GymClass> findByGymId(Long gymId, org.springframework.data.domain.Sort sort);

    java.util.List<GymClass> findByGymId(Long gymId);

    int countByGymId(Long gymId);

    /** Locks the class row so two people cannot take the last spot at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GymClass c where c.id = :id")
    Optional<GymClass> findByIdForUpdate(@Param("id") Long id);
}
