package com.foundry.gym.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.Promo;
import com.foundry.gym.domain.Role;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.repo.PromoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four jobs behind the toggles in a member's Preferences. Each one decides <em>who should get which email</em> and
 * adds it to the outbox; {@link OutboxSender} sends. A job can run as often as it likes: the outbox's unique
 * (member, key) pair means nobody gets the same email twice, so a restart or an overlap does no harm.
 *
 * <p>"Visits remaining" is counted as the app counts visits everywhere: the plan's visits per month minus the classes the
 * member has booked in the calendar month (past and upcoming). Unlimited plans have no visits to run out of, so they get
 * neither of the two visit emails.
 */
@Service
public class NotificationPlanner {

    private static final Logger log = LoggerFactory.getLogger(NotificationPlanner.class);

    /** The Preferences text says "only 2 visits remain" and "after the 21st". */
    static final int ENDING_REMAINING = 2;
    static final int REMINDER_FROM_DAY = 21;

    private final AppUserRepository users;
    private final PlanRepository plans;
    private final PromoRepository promos;
    private final BookingRepository bookings;
    private final EmailOutboxRepository outbox;
    private final MailSettings settings;
    private final Clock clock;
    private final com.foundry.gym.service.GymService gyms;

    public NotificationPlanner(AppUserRepository users, PlanRepository plans, PromoRepository promos,
                               BookingRepository bookings, EmailOutboxRepository outbox, MailSettings settings, Clock clock,
                               com.foundry.gym.service.GymService gyms) {
        this.gyms = gyms;
        this.users = users;
        this.plans = plans;
        this.promos = promos;
        this.bookings = bookings;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    /** Marketing: home page items the admin ticked "send as a marketing email" go to members who want marketing emails. */
    @Transactional
    public int queueMarketingItems() {
        MailSettings.Snapshot props = settings.current();
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        int queued = 0;
        for (Promo p : promos.findByMarketingTrueAndMarketingQueuedAtIsNullAndActiveTrue()) {
            if (p.getHideOn() != null && !p.getHideOn().isAfter(today)) {
                continue; // no longer shown on the home page, so not worth announcing; waits in case the date is moved
            }
            int forThis = 0;
            for (AppUser u : gyms.audience(users.findOptedInMarketing(Role.MEMBER), p.getGymId())) {
                String link = p.getLinkUrl();
                String text = p.getTitle() + (p.getBody() == null || p.getBody().isBlank() ? "" : "\n\n" + p.getBody());
                forThis += add(u, EmailKind.MARKETING, "promo:" + p.getId(), p.getTitle(),
                    EmailTexts.marketing(props, u.getName(), text, link), now);
            }
            p.markMarketingQueued(now, forThis);
            promos.save(p);
            queued += forThis;
        }
        return queued;
    }

    /** New plans: members who asked are told about plans created in the last {@code news-window-days} days. */
    @Transactional
    public int queueNewPlans() {
        MailSettings.Snapshot props = settings.current();
        Instant now = Instant.now(clock);
        List<Plan> fresh = plans.findByCreatedAtAfter(now.minusSeconds(props.newsWindowDays() * 86400L));
        if (fresh.isEmpty()) {
            return 0;
        }
        int queued = 0;
        for (AppUser u : users.findOptedInNewPlans(Role.MEMBER)) {
            for (Plan p : fresh) {
                String what = p.isUnlimited() ? "unlimited visits" : p.getVisitsPerMonth() + " visits per month";
                queued += add(u, EmailKind.NEW_PLAN, "plan:" + p.getId(), "New plan at " + props.gymName() + ": " + p.getName(),
                    EmailTexts.greeting(u.getName()) + "We have a new plan: " + p.getName() + " - " + what + ", "
                        + props.currency() + p.getPrice().toPlainString() + " per month."
                        + EmailTexts.footer(props, "New plans"), now);
            }
        }
        return queued;
    }

    /** Plan almost used up: the member has used some visits and exactly 2 remain this month. Once a month. */
    @Transactional
    public int queuePlanEnding() {
        MailSettings.Snapshot props = settings.current();
        Instant now = Instant.now(clock);
        YearMonth month = YearMonth.from(LocalDate.now(clock));
        int queued = 0;
        for (AppUser u : users.findOptedInPlanEnding(Role.MEMBER)) {
            Plan plan = u.getPlan();
            if (plan.isUnlimited()) {
                continue;
            }
            long used = bookings.countByUserIdAndSessionDateBetween(u.getId(), month.atDay(1), month.atEndOfMonth());
            if (used > 0 && plan.getVisitsPerMonth() - used == ENDING_REMAINING) {
                queued += add(u, EmailKind.PLAN_ENDING, "ending:" + month, "Only " + ENDING_REMAINING + " visits left this month",
                    EmailTexts.greeting(u.getName()) + "Your " + plan.getName() + " plan is almost used up: only "
                        + ENDING_REMAINING + " of your " + plan.getVisitsPerMonth() + " visits are left this month."
                        + EmailTexts.footer(props, "Plan almost used up"), now);
            }
        }
        return queued;
    }

    /** Visits left reminder: from the 21st (and from the configured hour) on, once a month, if visits are still left. */
    @Transactional
    public int queueVisitsReminder() {
        MailSettings.Snapshot props = settings.current();
        LocalDateTime local = LocalDateTime.now(clock);
        if (local.getDayOfMonth() < REMINDER_FROM_DAY || local.getHour() < props.reminderHour()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        YearMonth month = YearMonth.from(local.toLocalDate());
        int queued = 0;
        for (AppUser u : users.findOptedInVisitsReminder(Role.MEMBER)) {
            Plan plan = u.getPlan();
            if (plan.isUnlimited()) {
                continue;
            }
            long used = bookings.countByUserIdAndSessionDateBetween(u.getId(), month.atDay(1), month.atEndOfMonth());
            long left = plan.getVisitsPerMonth() - used;
            if (left > 0) {
                queued += add(u, EmailKind.VISITS_REMINDER, "reminder:" + month,
                    "You still have " + left + (left == 1 ? " visit" : " visits") + " left this month",
                    EmailTexts.greeting(u.getName()) + "The month is nearly over and your " + plan.getName() + " plan still has "
                        + left + (left == 1 ? " visit" : " visits") + " left. Book a class before they run out."
                        + EmailTexts.footer(props, "Visits left reminder"), now);
            }
        }
        return queued;
    }

    /** Adds the email unless this member already has one with the same key. Returns 1 if added, else 0. */
    private int add(AppUser u, EmailKind kind, String key, String subject, String body, Instant now) {
        if (outbox.existsByUserIdAndDedupeKey(u.getId(), key)) {
            return 0;
        }
        outbox.save(new EmailOutbox(u.getId(), kind, key, subject, body, now));
        log.debug("Queued {} email {} for member {}", kind, key, u.getId());
        return 1;
    }
}
