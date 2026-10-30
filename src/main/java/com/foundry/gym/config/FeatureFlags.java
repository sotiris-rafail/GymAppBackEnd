package com.foundry.gym.config;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Switches the admin controls from the config server while the backend is running.
 *
 * <p>Six switches: {@code plans.unlimited-enabled}, {@code home.enabled}, {@code gyms.multi-enabled}, {@code analytics.enabled}, {@code analytics.follow-up-days} and
 * {@code analytics.low-fill-percent} (app {@code backend} in the config server). Each is read when the
 * backend starts and then again every {@code config.client.refresh-seconds} (default 300, i.e. every 5 minutes), so changing
 * it in the config server console takes effect within 5 minutes, without restarting the backend. A missing value means "on".
 * The value is kept in this one shared instance (a Spring singleton): requests only read a field, they never call the
 * config server themselves.
 *
 * <p>Like every other setting, an environment variable ({@code PLANS_UNLIMITED_ENABLED}), a JVM property or a command
 * line argument beats the config server; when one of those is set the value is fixed and the config server is not asked.
 * If the config server cannot be reached the last known value stays in force.
 */
@Component
public class FeatureFlags {

    public static final String UNLIMITED_KEY = "plans.unlimited-enabled";
    public static final String HOME_KEY = "home.enabled";
    public static final String MULTI_GYM_KEY = "gyms.multi-enabled";
    public static final String ANALYTICS_KEY = "analytics.enabled";
    public static final String FOLLOW_UP_KEY = "analytics.follow-up-days";
    public static final String LOW_FILL_KEY = "analytics.low-fill-percent";

    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    private final Environment env;
    private final boolean live;
    private final long refreshSeconds;
    private volatile boolean unlimitedPlans;
    private volatile boolean home;
    private volatile boolean multiGym;
    private volatile boolean analytics;
    private volatile int followUpDays;
    private volatile int lowFillPercent;
    private volatile boolean reachable = true;
    private ScheduledExecutorService timer;

    public FeatureFlags(Environment env) {
        this.env = env;
        this.unlimitedPlans = parse(env.getProperty(UNLIMITED_KEY));
        this.home = parse(env.getProperty(HOME_KEY));
        this.multiGym = parseOn(env.getProperty(MULTI_GYM_KEY));
        this.analytics = parse(env.getProperty(ANALYTICS_KEY));
        this.followUpDays = number(env.getProperty(FOLLOW_UP_KEY), 30, 1, 365);
        this.lowFillPercent = number(env.getProperty(LOW_FILL_KEY), 65, 1, 100);
        this.refreshSeconds = Math.max(2, Long.parseLong(env.getProperty("config.client.refresh-seconds", "300")));
        this.live = Boolean.parseBoolean(env.getProperty("config.client.enabled", "true")) && !(pinned(UNLIMITED_KEY) && pinned(HOME_KEY) && pinned(MULTI_GYM_KEY) && pinned(ANALYTICS_KEY) && pinned(FOLLOW_UP_KEY) && pinned(LOW_FILL_KEY));
    }

    /** Whether plans may be made (or assigned) as unlimited. Plans that are already unlimited keep working either way. */
    public boolean unlimitedPlans() {
        return unlimitedPlans;
    }

    /** Whether the home page items are shown to visitors and managed by the admin. Missing = on. */
    public boolean home() {
        return home;
    }

    public void setHome(boolean on) {
        this.home = on;
    }

    /** Whether several gyms are in use. Off by default: a missing value means one gym (the default gym). */
    public boolean multiGym() {
        return multiGym;
    }

    public void setMultiGym(boolean on) {
        this.multiGym = on;
    }

    /** Whether the Analytics page is available (the page and its endpoint). Missing = on. */
    public boolean analytics() {
        return analytics;
    }

    /** Members with a plan and no booking for this many days are listed as "worth a message". Default 30. */
    public int followUpDays() {
        return followUpDays;
    }

    /** A class that is less full than this (percent) is highlighted as one of the emptiest. Default 65. */
    public int lowFillPercent() {
        return lowFillPercent;
    }

    public void setAnalytics(boolean on) {
        this.analytics = on;
    }

    public void setFollowUpDays(int days) {
        this.followUpDays = days;
    }

    public void setLowFillPercent(int percent) {
        this.lowFillPercent = percent;
    }

    /** For tests and for the refresh below. */
    public void setUnlimitedPlans(boolean on) {
        this.unlimitedPlans = on;
    }

    @PostConstruct
    void start() {
        if (!live) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feature-flags");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** Asks the config server again. Run by a timer; public so a test can drive it. Does nothing when the value is fixed. */
    public void refresh() {
        if (!live) {
            return;
        }
        try {
            Map<String, Object> props = ConfigServerEnvironmentPostProcessor.fetch(
                trimSlash(env.getProperty("config.client.url", "http://localhost:8888")),
                env.getProperty("config.client.app", "backend"),
                String.join(",", ((ConfigurableEnvironment) env).getActiveProfiles()),
                env.getProperty("config.client.client-id", "backend"),
                env.getProperty("config.client.client-secret", ""),
                Long.parseLong(env.getProperty("config.client.timeout-ms", "3000")));
            if (!pinned(UNLIMITED_KEY)) {
                boolean next = parse(text(props, UNLIMITED_KEY));
                if (next != unlimitedPlans) {
                    log.info("Unlimited plans are now {} (from the config server).", next ? "on" : "off");
                }
                unlimitedPlans = next;
            }
            if (!pinned(HOME_KEY)) {
                boolean next = parse(text(props, HOME_KEY));
                if (next != home) {
                    log.info("The home page is now {} (from the config server).", next ? "on" : "off");
                }
                home = next;
            }
            if (!pinned(MULTI_GYM_KEY)) {
                boolean next = parseOn(text(props, MULTI_GYM_KEY));
                if (next != multiGym) {
                    log.info("Multi-gym is now {} (from the config server).", next ? "on" : "off");
                }
                multiGym = next;
            }
            if (!pinned(ANALYTICS_KEY)) {
                boolean next = parse(text(props, ANALYTICS_KEY));
                if (next != analytics) {
                    log.info("Analytics is now {} (from the config server).", next ? "on" : "off");
                }
                analytics = next;
            }
            if (!pinned(FOLLOW_UP_KEY)) {
                followUpDays = number(text(props, FOLLOW_UP_KEY), 30, 1, 365);
            }
            if (!pinned(LOW_FILL_KEY)) {
                lowFillPercent = number(text(props, LOW_FILL_KEY), 65, 1, 100);
            }
            if (!reachable) {
                log.info("The config server is reachable again.");
                reachable = true;
            }
        } catch (Exception e) {
            if (reachable) { // say it once, not every few seconds
                log.warn("Could not refresh settings from the config server ({}); keeping the last values.", e.getMessage());
                reachable = false;
            }
        }
    }

    /** Empty or missing = on; "false", "0", "no" and "off" (any case) = off. */
    static boolean parse(String v) {
        return v == null || !v.trim().matches("(?i)false|0|no|off");
    }

    /** For switches that are off unless set: only "true", "1", "yes" or "on" (any case) turns it on. */
    static boolean parseOn(String v) {
        return v != null && v.trim().matches("(?i)true|1|yes|on");
    }

    private static String text(Map<String, Object> props, String key) {
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

    /** A whole number inside min..max; anything else (empty, text, out of range) falls back to the default. */
    static int number(String v, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(v == null ? "" : v.trim());
            return n < min || n > max ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True when the command line, a JVM property or an environment variable sets the key: it beats the config server. */
    private boolean pinned(String key) {
        if (!(env instanceof ConfigurableEnvironment ce)) {
            return false;
        }
        for (PropertySource<?> ps : ce.getPropertySources()) {
            if (ps.containsProperty(key)) {
                String n = ps.getName();
                return n.equals("commandLineArgs") || n.equals("systemProperties") || n.equals("systemEnvironment");
            }
        }
        return false;
    }

    private static String trimSlash(String s) {
        return s.replaceAll("/+$", "");
    }
}
