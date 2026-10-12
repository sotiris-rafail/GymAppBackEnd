package com.foundry.gym.repo;

import java.util.List;
import java.util.Optional;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    List<AppUser> findByRoleOrderByNameAsc(Role role);

    /** Locks the member row so one member cannot beat their visit limit with two parallel bookings. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AppUser u set u.plan = null where u.plan.id = :planId")
    int clearPlan(@Param("planId") Long planId);
}
