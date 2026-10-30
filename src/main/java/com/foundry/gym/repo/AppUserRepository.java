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

    /** Looked up by the blind index (EmailCrypto.index), because the stored address is encrypted. */
    Optional<AppUser> findByEmailHash(String emailHash);

    boolean existsByEmailHash(String emailHash);

    List<AppUser> findByRoleOrderByNameAsc(Role role);

    List<AppUser> findByRoleAndHomeGymId(Role role, Long homeGymId);

    /** Members who turned on one of the email preferences (see the scheduled jobs in com.foundry.gym.mail). */
    @Query("select u from AppUser u where u.role = :role and u.id in (select p.userId from MemberPreference p where p.marketing = true)")
    List<AppUser> findOptedInMarketing(@Param("role") Role role);

    @Query("select u from AppUser u where u.role = :role and u.id in (select p.userId from MemberPreference p where p.newPlans = true)")
    List<AppUser> findOptedInNewPlans(@Param("role") Role role);

    /** Only members who hold a plan (inner join), as the next two emails are about the visits of the plan. */
    @Query("select u from AppUser u join fetch u.plan where u.role = :role and u.id in (select p.userId from MemberPreference p where p.planEnding = true)")
    List<AppUser> findOptedInPlanEnding(@Param("role") Role role);

    @Query("select u from AppUser u join fetch u.plan where u.role = :role and u.id in (select p.userId from MemberPreference p where p.visitsReminder = true)")
    List<AppUser> findOptedInVisitsReminder(@Param("role") Role role);

    /** Locks the member row so one member cannot beat their visit limit with two parallel bookings. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AppUser u set u.plan = null where u.plan.id = :planId")
    int clearPlan(@Param("planId") Long planId);
}
