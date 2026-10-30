package com.foundry.gym.mail;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timers. One job per toggle in Preferences, plus the sender. The timers always run, but every job first checks the live
 * {@link MailSettings}: while {@code gym.mail.enabled} is false nothing is queued or sent (what is already queued waits). Each job is isolated: if one fails (it is logged) the
 * others still run, and the failed one simply runs again at the next tick.
 */
@Configuration
@EnableScheduling
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationPlanner planner;
    private final OutboxSender sender;
    private final MailSettings settings;

    public NotificationScheduler(NotificationPlanner planner, OutboxSender sender, MailSettings settings) {
        this.planner = planner;
        this.sender = sender;
        this.settings = settings;
    }

    /** Queues emails for the four toggles. */
    @Scheduled(initialDelayString = "${gym.mail.initial-delay-seconds:30}", fixedDelayString = "${gym.mail.scan-seconds:900}",
        timeUnit = TimeUnit.SECONDS)
    public void scan() {
        run("marketing items", planner::queueMarketingItems);
        run("new plans", planner::queueNewPlans);
        run("plan almost used up", planner::queuePlanEnding);
        run("visits left reminder", planner::queueVisitsReminder);
    }

    /** Sends what is queued. */
    @Scheduled(initialDelayString = "${gym.mail.initial-delay-seconds:30}", fixedDelayString = "${gym.mail.send-seconds:60}",
        timeUnit = TimeUnit.SECONDS)
    public void send() {
        run("sending", sender::sendDue);
    }

    /** Once a day. */
    @Scheduled(initialDelay = 3600, fixedDelay = 86400, timeUnit = TimeUnit.SECONDS)
    public void purge() {
        run("cleaning up", sender::purge);
    }

    private void run(String job, java.util.function.IntSupplier work) {
        if (!settings.enabled()) {
            return; // switched off on the config server: nothing is queued or sent until it is switched on again
        }
        try {
            int n = work.getAsInt();
            if (n > 0) {
                log.info("Email job '{}': {}", job, n);
            }
        } catch (RuntimeException e) {
            log.error("Email job '{}' failed and will run again at the next tick", job, e);
        }
    }
}
