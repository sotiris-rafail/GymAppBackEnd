package com.foundry.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.foundry.gym.config.ConfigServerEnvironmentPostProcessor;
import com.foundry.gym.config.FeatureFlags;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

/** The backend's start-up client, against a tiny fake config server. */
class ConfigServerClientTests {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> tokenRequest = new AtomicReference<>();
    private volatile int tokenStatus = 200;
    private volatile int status = 200;
    private volatile String body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"gym.cors-origins\":\"http://x:1\",\"gym.booking-horizon-days\":\"30\",\"gym.timezone\":\"\"}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/token", ex -> {
            tokenRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"accessToken\":\"tok-123\",\"tokenType\":\"Bearer\",\"expiresIn\":900}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(tokenStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/config/", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        try {
            server.stop(0); // may already be stopped by the test that simulates a server that is down
        } catch (RuntimeException ignored) {
            // nothing to clean up
        }
    }

    private StandardEnvironment env(String... pairs) {
        StandardEnvironment env = new StandardEnvironment();
        MockPropertySource p = new MockPropertySource("test");
        p.setProperty("config.client.url", "http://127.0.0.1:" + server.getAddress().getPort());
        p.setProperty("config.client.client-id", "backend");
        p.setProperty("config.client.client-secret", "pw");
        p.setProperty("config.client.required", "false"); // the real default is true; most tests want the lenient mode
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        env.getPropertySources().addFirst(p);
        return env;
    }

    private void run(StandardEnvironment env) {
        new ConfigServerEnvironmentPostProcessor(supplier -> supplier.get()).postProcessEnvironment(env, new SpringApplication());
    }

    @Test
    void a_token_is_requested_first_and_sent_as_a_bearer_header() {
        StandardEnvironment env = env();
        env.setActiveProfiles("prod", "eu");
        run(env);
        assertEquals("30", env.getProperty("gym.booking-horizon-days"));
        assertEquals("http://x:1", env.getProperty("gym.cors-origins"));
        assertEquals("/config/backend?profiles=prod%2Ceu", lastPath.get());
        assertEquals("{\"clientId\":\"backend\",\"clientSecret\":\"pw\"}", tokenRequest.get());
        assertEquals("Bearer tok-123", lastAuth.get());
    }

    @Test
    void an_environment_variable_style_source_still_beats_the_config_server() {
        StandardEnvironment env = env();
        // replace the real systemEnvironment source with one that "has" the variable
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MockPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            .withProperty("gym.booking-horizon-days", "7"));
        run(env);
        assertEquals("7", env.getProperty("gym.booking-horizon-days"));
        assertEquals("http://x:1", env.getProperty("gym.cors-origins"));
    }

    @Test
    void disabled_does_nothing() {
        StandardEnvironment env = env("config.client.enabled", "false");
        run(env);
        assertNull(lastPath.get());
        assertNull(env.getProperty("gym.booking-horizon-days"));
    }

    @Test
    void the_config_server_is_required_unless_switched_off() {
        tokenStatus = 401;
        StandardEnvironment bare = new StandardEnvironment(); // no config.client.required at all
        MockPropertySource p = new MockPropertySource("test");
        p.setProperty("config.client.url", "http://127.0.0.1:" + server.getAddress().getPort());
        bare.getPropertySources().addFirst(p);
        assertThrows(IllegalStateException.class, () -> run(bare));
    }

    @Test
    void an_unreachable_or_refusing_server_is_survivable_unless_required() {
        tokenStatus = 401; // the config server does not know this client
        StandardEnvironment lenient = env();
        run(lenient); // warns, does not throw
        assertNull(lenient.getProperty("gym.booking-horizon-days"));

        assertThrows(IllegalStateException.class, () -> run(env("config.client.required", "true")));
        assertNull(lastPath.get()); // without a token the settings were never even requested

        tokenStatus = 200;
        status = 403; // a token that may not read this application
        StandardEnvironment refused = env();
        run(refused);
        assertNull(refused.getProperty("gym.booking-horizon-days"));
        assertThrows(IllegalStateException.class, () -> run(env("config.client.required", "true")));

        server.stop(0);
        StandardEnvironment down = env();
        run(down);
        assertNull(down.getProperty("gym.booking-horizon-days"));
        assertThrows(IllegalStateException.class, () -> run(env("config.client.required", "true", "config.client.timeout-ms", "500")));
    }

    private static String settings(String flag) {
        return "{\"app\":\"backend\",\"profiles\":[],\"properties\":{" + (flag == null ? "" : "\"plans.unlimited-enabled\":\"" + flag + "\"") + "}}";
    }

    @Test
    void the_unlimited_plans_switch_follows_the_config_server_while_running() {
        body = settings(null);
        StandardEnvironment env = env();
        run(env);
        FeatureFlags flags = new FeatureFlags(env);
        assertTrue(flags.unlimitedPlans(), "a missing value means on");

        body = settings("false");
        flags.refresh();
        assertFalse(flags.unlimitedPlans());

        body = settings("TRUE");
        flags.refresh();
        assertTrue(flags.unlimitedPlans());

        body = settings("Off");
        flags.refresh();
        assertFalse(flags.unlimitedPlans());

        body = settings(null); // the entry was deleted: back to the default (on)
        flags.refresh();
        assertTrue(flags.unlimitedPlans());
    }

    @Test
    void the_switch_keeps_its_last_value_when_the_config_server_goes_down() {
        body = settings("false");
        StandardEnvironment env = env();
        run(env); // the start-up fetch puts plans.unlimited-enabled=false in the environment
        FeatureFlags flags = new FeatureFlags(env);
        assertFalse(flags.unlimitedPlans(), "the start-up value is used");
        server.stop(0);
        flags.refresh();
        assertFalse(flags.unlimitedPlans());
    }

    @Test
    void an_environment_variable_fixes_the_switch_and_the_config_server_is_not_asked() {
        body = settings("true");
        StandardEnvironment env = env();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MockPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            .withProperty("plans.unlimited-enabled", "false"));
        FeatureFlags flags = new FeatureFlags(env);
        assertFalse(flags.unlimitedPlans());
        flags.refresh(); // would turn it on if the config server were asked
        assertFalse(flags.unlimitedPlans());
    }

    @Test
    void the_analytics_settings_follow_the_config_server_while_running() {
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{}}";
        StandardEnvironment env = env();
        run(env);
        FeatureFlags flags = new FeatureFlags(env);
        assertTrue(flags.analytics(), "a missing value means on");
        assertEquals(30, flags.followUpDays());
        assertEquals(65, flags.lowFillPercent());

        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"analytics.enabled\":\"false\",\"analytics.follow-up-days\":\"14\",\"analytics.low-fill-percent\":\"50\"}}";
        flags.refresh();
        assertFalse(flags.analytics());
        assertEquals(14, flags.followUpDays());
        assertEquals(50, flags.lowFillPercent());

        // nonsense or out-of-range numbers fall back to the defaults instead of breaking the page
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"analytics.follow-up-days\":\"soon\",\"analytics.low-fill-percent\":\"500\"}}";
        flags.refresh();
        assertTrue(flags.analytics());
        assertEquals(30, flags.followUpDays());
        assertEquals(65, flags.lowFillPercent());
    }

    @Test
    void the_home_page_switch_follows_the_config_server_while_running() {
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{}}";
        StandardEnvironment env = env();
        run(env);
        FeatureFlags flags = new FeatureFlags(env);
        assertTrue(flags.home(), "a missing value means on");
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"home.enabled\":\"false\"}}";
        flags.refresh();
        assertFalse(flags.home());
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"home.enabled\":\"true\"}}";
        flags.refresh();
        assertTrue(flags.home());
    }

    @Test
    void several_gyms_are_off_by_default_and_follow_the_config_server_while_running() {
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{}}";
        StandardEnvironment env = env();
        run(env);
        FeatureFlags flags = new FeatureFlags(env);
        assertFalse(flags.multiGym(), "a missing value means one gym");
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"gyms.multi-enabled\":\"true\"}}";
        flags.refresh();
        assertTrue(flags.multiGym());
        body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{}}";
        flags.refresh();
        assertFalse(flags.multiGym(), "removing the value goes back to one gym");
    }
}
