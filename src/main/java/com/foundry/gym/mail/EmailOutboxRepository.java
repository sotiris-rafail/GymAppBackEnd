package com.foundry.gym.mail;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    boolean existsByUserIdAndDedupeKey(Long userId, String dedupeKey);

    List<EmailOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(EmailOutbox.Status status, Instant due, Pageable page);

    long countByStatus(EmailOutbox.Status status);

    long countByDedupeKeyAndStatus(String dedupeKey, EmailOutbox.Status status);

    /** Housekeeping: finished rows older than a cut-off. Their dedupe keys are no longer needed to prevent repeats. */
    @Transactional
    @Modifying
    @Query("delete from EmailOutbox e where e.status in :statuses and e.createdAt < :before")
    int deleteFinishedBefore(@Param("statuses") Collection<EmailOutbox.Status> statuses, @Param("before") Instant before);
}
