package com.foundry.gym.mail;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.MemberPreference;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.MemberPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Sends the emails waiting in the outbox. Just before sending it checks the member's choice again, so someone who switched
 * an email off after it was queued does not get it. A failure is not lost: the email is tried again after a pause
 * (1, 2, 4... minutes) up to {@code gym.mail.max-attempts} times, then marked FAILED.
 */
@Service
public class OutboxSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);

    private final EmailOutboxRepository outbox;
    private final AppUserRepository users;
    private final MemberPreferenceRepository preferences;
    private final EmailGateway gateway;
    private final MailSettings settings;
    private final Clock clock;

    public OutboxSender(EmailOutboxRepository outbox, AppUserRepository users, MemberPreferenceRepository preferences,
                        EmailGateway gateway, MailSettings settings, Clock clock) {
        this.outbox = outbox;
        this.users = users;
        this.preferences = preferences;
        this.gateway = gateway;
        this.settings = settings;
        this.clock = clock;
    }

    /** Sends up to one batch of due emails. Returns how many were sent. */
    public int sendDue() {
        MailSettings.Snapshot props = settings.current();
        Instant now = Instant.now(clock);
        List<EmailOutbox> due = outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            EmailOutbox.Status.PENDING, now, PageRequest.of(0, props.batchSize()));
        int sent = 0;
        for (EmailOutbox e : due) {
            AppUser u = users.findById(e.getUserId()).orElse(null);
            if (u == null || !stillWanted(e)) {
                e.markSkipped();
            } else {
                try {
                    gateway.send(u.getEmail(), e.getSubject(), e.getBody());
                    e.markSent(now);
                    sent++;
                } catch (RuntimeException ex) {
                    e.markFailed(now, ex.getClass().getSimpleName() + ": " + ex.getMessage(), props.maxAttempts());
                    log.warn("Could not send email {} (try {}): {}", e.getId(), e.getAttempts(), ex.getMessage());
                }
            }
            outbox.save(e);
        }
        return sent;
    }

    /** Removes finished rows after 90 days; their keys are no longer needed to prevent repeats. */
    public int purge() {
        return outbox.deleteFinishedBefore(EnumSet.of(EmailOutbox.Status.SENT, EmailOutbox.Status.SKIPPED, EmailOutbox.Status.FAILED),
            Instant.now(clock).minusSeconds(90L * 86400));
    }

    private boolean stillWanted(EmailOutbox e) {
        MemberPreference p = preferences.findById(e.getUserId()).orElse(null);
        if (p == null) {
            return false;
        }
        return switch (e.getKind()) {
            case MARKETING -> p.isMarketing();
            case NEW_PLAN -> p.isNewPlans();
            case PLAN_ENDING -> p.isPlanEnding();
            case VISITS_REMINDER -> p.isVisitsReminder();
        };
    }
}
