package com.foundry.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import com.foundry.gym.config.ConfigServerEnvironmentPostProcessor;
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
    private volatile int status = 200;
    private volatile String body = "{\"app\":\"backend\",\"profiles\":[],\"properties\":{\"gym.cors-origins\":\"http://x:1\",\"gym.booking-horizon-days\":\"30\",\"gym.timezone\":\"\"}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        p.setProperty("config.client.username", "backend");
        p.setProperty("config.client.password", "pw");
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
    void settings_from_the_server_become_properties_and_basic_auth_is_sent() {
        StandardEnvironment env = env();
        env.setActiveProfiles("prod", "eu");
        run(env);
        assertEquals("30", env.getProperty("gym.booking-horizon-days"));
        assertEquals("http://x:1", env.getProperty("gym.cors-origins"));
        assertEquals("/config/backend?profiles=prod%2Ceu", lastPath.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("backend:pw".getBytes(StandardCharsets.UTF_8)), lastAuth.get());
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
    void an_unreachable_or_refusing_server_is_survivable_unless_required() {
        status = 401;
        StandardEnvironment lenient = env();
        run(lenient); // warns, does not throw
        assertNull(lenient.getProperty("gym.booking-horizon-days"));

        assertThrows(IllegalStateException.class, () -> run(env("config.client.required", "true")));

        server.stop(0);
        StandardEnvironment down = env();
        run(down);
        assertNull(down.getProperty("gym.booking-horizon-days"));
        assertThrows(IllegalStateException.class, () -> run(env("config.client.required", "true", "config.client.timeout-ms", "500")));
    }
}
