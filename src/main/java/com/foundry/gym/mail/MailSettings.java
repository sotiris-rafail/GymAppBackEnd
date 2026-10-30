package com.foundry.gym.mail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.foundry.gym.config.ConfigServerEnvironmentPostProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Every setting of the emails to members ({@code gym.mail.*} and the mail server {@code spring.mail.*}), kept live.
 *
 * <p>The values are read from the config server when the backend starts and then again every
 * {@code config.client.refresh-seconds} (default 300 = 5 minutes), like {@code plans.unlimited-enabled}. So switching the
 * emails off, or changing the mail server, user, password or sender address on the config server takes effect within 5 minutes
 * without restarting the backend. Everything reads the current values from this one shared instance (a Spring singleton) and
 * never asks the config server itself.
 *
 * <ul>
 *   <li>At start-up, {@code enabled=true} with the mail server ({@code spring.mail.host}) or the sender ({@code gym.mail.from})
 *       missing stops the backend from starting.</li>
 *   <li>Later, a change that would leave the emails switched on but incomplete is refused (logged, the previous working values
 *       stay in force) rather than breaking the sending.</li>
 *   <li>A key set by an environment variable, a JVM property or the command line beats the config server and stays fixed.</li>
 *   <li>If the config server cannot be reached the last values stay in force.</li>
 *   <li>Only the two timer intervals ({@code scan-seconds}, {@code send-seconds}, {@code initial-delay-seconds}) are fixed at
 *       start-up.</li>
 * </ul>
 */
@Component
public class MailSettings {

    private static final Logger log = LoggerFactory.getLogger(MailSettings.class);
    private static final String JAVAMAIL_PREFIX = "spring.mail.properties.";

    /** One consistent set of values. Read it once per piece of work: {@code Snapshot s = settings.current();} */
    public record Snapshot(boolean enabled, String from, String appUrl, String gymName, String currency, int reminderHour,
                           int newsWindowDays, int maxAttempts, int batchSize,
                           String host, int port, String username, String password, Map<String, String> javaMail) {

        /** Never prints the password. */
        @Override
        public String toString() {
            return "MailSettings[enabled=" + enabled + ", host=" + host + ", port=" + port + ", username=" + username + "]";
        }
    }

    private final Environment env;
    private final boolean live;
    private final long refreshSeconds;
    private volatile Snapshot snapshot;
    private volatile boolean reachable = true;
    private volatile String lastRejection = "";
    private ScheduledExecutorService timer;

    public MailSettings(Environment env) {
        this.env = env;
        this.refreshSeconds = Math.max(2, Long.parseLong(env.getProperty("config.client.refresh-seconds", "300")));
        this.live = env instanceof ConfigurableEnvironment
            && Boolean.parseBoolean(env.getProperty("config.client.enabled", "true"));
        Snapshot first = build(null);
        String problem = problem(first);
        if (problem != null) {
            throw new IllegalStateException("gym.mail.enabled is true but " + problem + ". Set it on the config server "
                + "(app 'backend') or turn gym.mail.enabled off.");
        }
        this.snapshot = first;
    }

    public Snapshot current() {
        return snapshot;
    }

    public boolean enabled() {
        return snapshot.enabled();
    }

    @PostConstruct
    void start() {
        if (!live) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mail-settings");
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

    /** Asks the config server again. Run by a timer; public so a test can drive it. */
    public void refresh() {
        if (!live) {
            return;
        }
        try {
            apply(ConfigServerEnvironmentPostProcessor.fetchFor(env));
            if (!reachable) {
                log.info("The config server is reachable again.");
                reachable = true;
            }
        } catch (Exception e) {
            if (reachable) { // say it once, not every few minutes
                log.warn("Could not refresh the email settings from the config server ({}); keeping the last values.", e.getMessage());
                reachable = false;
            }
        }
    }

    /** Takes over freshly fetched settings (public so a test can feed them in). An incomplete "enabled" set is refused. */
    public void apply(Map<String, Object> fetched) {
        Snapshot next = build(fetched);
        String problem = problem(next);
        if (problem != null) {
            if (!problem.equals(lastRejection)) { // once per distinct problem
                log.error("The email settings on the config server were NOT applied: gym.mail.enabled is true but {}. "
                    + "Keeping the previous settings ({}).", problem, snapshot);
                lastRejection = problem;
            }
            return;
        }
        lastRejection = "";
        Snapshot before = snapshot;
        if (!next.equals(before)) {
            snapshot = next;
            log.info("Email settings changed from the config server: now {}", next);
        }
    }

    /** What is wrong with a set of values, or null when it can be used. */
    private static String problem(Snapshot s) {
        if (!s.enabled()) {
            return null;
        }
        if (s.host().isBlank()) {
            return "spring.mail.host (the mail server) is not set";
        }
        if (s.from().isBlank()) {
            return "gym.mail.from (the sender address) is not set";
        }
        return null;
    }

    private Snapshot build(Map<String, Object> fetched) {
        Map<String, String> javaMail = new LinkedHashMap<>();
        if (env instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> ps : ce.getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String n : eps.getPropertyNames()) {
                        if (n.startsWith(JAVAMAIL_PREFIX)) {
                            javaMail.putIfAbsent(n.substring(JAVAMAIL_PREFIX.length()), env.getProperty(n, ""));
                        }
                    }
                }
            }
        }
        if (fetched != null) {
            fetched.forEach((k, v) -> {
                if (k.startsWith(JAVAMAIL_PREFIX) && !pinned(k)) {
                    javaMail.put(k.substring(JAVAMAIL_PREFIX.length()), String.valueOf(v));
                }
            });
        }
        return new Snapshot(
            bool(value("gym.mail.enabled", fetched), false),
            text(value("gym.mail.from", fetched)),
            text(value("gym.mail.app-url", fetched)),
            orElse(text(value("gym.mail.gym-name", fetched)), "Foundry"),
            orElse(text(value("gym.mail.currency", fetched)), "€"),
            clamp(integer(value("gym.mail.reminder-hour", fetched), 9), 0, 23),
            clamp(integer(value("gym.mail.news-window-days", fetched), 7), 1, 365),
            clamp(integer(value("gym.mail.max-attempts", fetched), 5), 1, 50),
            clamp(integer(value("gym.mail.batch-size", fetched), 50), 1, 500),
            text(value("spring.mail.host", fetched)),
            clamp(integer(value("spring.mail.port", fetched), 587), 1, 65535),
            text(value("spring.mail.username", fetched)),
            value("spring.mail.password", fetched) == null ? "" : value("spring.mail.password", fetched),
            Map.copyOf(javaMail));
    }

    /** The freshly fetched value, unless the key is fixed by an environment variable, JVM property or command line. */
    private String value(String key, Map<String, Object> fetched) {
        if (fetched != null && fetched.containsKey(key) && !pinned(key)) {
            Object v = fetched.get(key);
            return v == null ? null : v.toString();
        }
        return env.getProperty(key);
    }

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

    private static boolean bool(String v, boolean otherwise) {
        return v == null || v.isBlank() ? otherwise : Boolean.parseBoolean(v.trim());
    }

    private static int integer(String v, int otherwise) {
        try {
            return v == null || v.isBlank() ? otherwise : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String text(String v) {
        return v == null ? "" : v.trim();
    }

    private static String orElse(String v, String otherwise) {
        return v.isEmpty() ? otherwise : v;
    }
}
