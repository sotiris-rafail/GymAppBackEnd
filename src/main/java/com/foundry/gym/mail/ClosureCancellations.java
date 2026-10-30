package com.foundry.gym.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.foundry.gym.domain.Booking;
import com.foundry.gym.repo.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When the admin closes a date, week or month, the bookings already made for those days cannot be honoured. They are
 * cancelled (the booking simply disappears, which gives the visit back, exactly like a member cancelling) and each member
 * is told by email when sending is on. Only classes that have not started are touched: past days are history.
 */
@Service
public class ClosureCancellations {

    /** How many bookings and members a closure would affect. */
    public record Impact(int bookings, int members) {
    }

    private final BookingRepository bookings;
    private final EmailOutboxRepository outbox;
    private final MailSettings settings;
    private final Clock clock;

    public ClosureCancellations(BookingRepository bookings, EmailOutboxRepository outbox, MailSettings settings, Clock clock) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    /** What closing {@code from..to} at this gym would cancel (nothing before today, nothing already started). */
    @Transactional(readOnly = true)
    public Impact impact(Long gymId, LocalDate from, LocalDate to) {
        List<Booking> hit = affected(gymId, from, to);
        return new Impact(hit.size(), (int) hit.stream().map(b -> b.getUser().getId()).distinct().count());
    }

    /** Cancels those bookings and queues one email per booking. Returns how many were cancelled. */
    @Transactional
    public int cancel(Long gymId, LocalDate from, LocalDate to, String reason) {
        List<Booking> hit = affected(gymId, from, to);
        if (hit.isEmpty()) {
            return 0;
        }
        MailSettings.Snapshot props = settings.current();
        Instant now = Instant.now(clock);
        for (Booking b : hit) {
            if (props.enabled()) {
                String key = "closed:" + b.getId();
                if (!outbox.existsByUserIdAndDedupeKey(b.getUser().getId(), key)) {
                    String date = b.getSessionDate().toString();
                    outbox.save(new EmailOutbox(b.getUser().getId(), EmailKind.CLASS_CANCELLED, key,
                        "Your " + b.getGymClass().getName() + " class on " + date + " is cancelled",
                        EmailTexts.classCancelled(props, b.getUser().getName(), b.getGymClass().getName(), date,
                            b.getGymClass().getStartTime().toString(), reason), now));
                }
            }
            bookings.delete(b);
        }
        return hit.size();
    }

    private List<Booking> affected(Long gymId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalTime nowTime = LocalTime.now(clock);
        LocalDate start = from.isBefore(today) ? today : from;
        if (start.isAfter(to)) {
            return List.of();
        }
        return bookings.findForGymBetween(gymId, start, to).stream()
            .filter(b -> !b.getSessionDate().equals(today) || b.getGymClass().getStartTime().isAfter(nowTime))
            .toList();
    }
}
