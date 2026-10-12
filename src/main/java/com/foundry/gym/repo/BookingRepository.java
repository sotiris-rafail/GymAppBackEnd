package com.foundry.gym.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.foundry.gym.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    long countByGymClassIdAndSessionDate(Long classId, LocalDate date);

    long countByUserIdAndSessionDateBetween(Long userId, LocalDate from, LocalDate to);

    boolean existsByUserIdAndGymClassIdAndSessionDate(Long userId, Long classId, LocalDate date);

    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    @Query("select b from Booking b join fetch b.gymClass c "
        + "where b.user.id = :uid order by b.sessionDate, c.startTime")
    List<Booking> findForUser(@Param("uid") Long uid);

    @Query("select b from Booking b join fetch b.gymClass "
        + "where b.user.id = :uid and b.sessionDate between :from and :to")
    List<Booking> findForUserBetween(@Param("uid") Long uid, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Every booking in the date range with its class and member loaded, in session order. */
    @Query("select b from Booking b join fetch b.gymClass c join fetch b.user u "
        + "where b.sessionDate between :from and :to order by b.sessionDate, c.startTime, u.name")
    List<Booking> findAllBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Rows of [year, month, bookedCount] for every month in which the member has at least one booking. */
    @Query("select year(b.sessionDate), month(b.sessionDate), count(b) from Booking b "
        + "where b.user.id = :uid group by year(b.sessionDate), month(b.sessionDate)")
    List<Object[]> countPerMonthForUser(@Param("uid") Long uid);

    /** Rows of [classId, date, bookedCount] for every session that has at least one booking. */
    @Query("select b.gymClass.id, b.sessionDate, count(b) from Booking b "
        + "where b.sessionDate between :from and :to group by b.gymClass.id, b.sessionDate")
    List<Object[]> countPerSession(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Booking b where b.gymClass.id = :classId")
    int deleteAllForClass(@Param("classId") Long classId);
}
