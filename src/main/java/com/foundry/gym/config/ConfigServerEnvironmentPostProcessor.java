package com.foundry.gym.config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Fetches this application's settings from the Foundry config server before the application starts, and makes them
 * available like any other property ({@code gym.cors-origins}, {@code spring.datasource.url}, ...).
 *
 * <p>Priority: command line, JVM system properties and environment variables still win over the config server, and the
 * config server wins over {@code application.yml}. So an environment variable is always a way to override one setting.
 *
 * <p>If the config server cannot be reached the backend starts with its local settings and logs a warning, unless
 * {@code config.client.required=true}, in which case it refuses to start.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Switch it off with {@code config.client.enabled=false}.
 */
public class ConfigServerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "foundryConfigServer";

    private final Log log;

    public ConfigServerEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ConfigServerEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // after application.yml has been read, so config.client.* can come from it
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!Boolean.parseBoolean(env.getProperty("config.client.enabled", "true"))) {
            return;
        }
        String url = trimSlash(env.getProperty("config.client.url", "http://localhost:8888"));
        String app = env.getProperty("config.client.app", "backend");
        String user = env.getProperty("config.client.username", "backend");
        String password = env.getProperty("config.client.password", "");
        boolean required = Boolean.parseBoolean(env.getProperty("config.client.required", "false"));
        long timeoutMs = Long.parseLong(env.getProperty("config.client.timeout-ms", "3000"));
        String profiles = String.join(",", env.getActiveProfiles());

        try {
            Map<String, Object> props = fetch(url, app, profiles, user, password, timeoutMs);
            // After the environment variables: they and the command line still override the config server.
            if (env.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                env.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(SOURCE_NAME, props));
            } else {
                env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
            }
            // Values are never logged: some are secrets.
            log.info("Loaded " + props.size() + " settings for '" + app + "' from the config server at " + url);
        } catch (Exception e) {
            String why = "Could not load settings from the config server at " + url + ": " + e.getMessage();
            if (required) {
                throw new IllegalStateException(why + " (config.client.required=true)", e);
            }
            log.warn(why + ". Starting with the local settings.");
        }
    }

    static Map<String, Object> fetch(String url, String app, String profiles, String user, String password, long timeoutMs)
        throws Exception {
        String target = url + "/config/" + URLEncoder.encode(app, StandardCharsets.UTF_8)
            + (profiles.isBlank() ? "" : "?profiles=" + URLEncoder.encode(profiles, StandardCharsets.UTF_8));
        String basic = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + basic)
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401) {
            throw new IllegalStateException("the config server refused the client username and password");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("the config server answered HTTP " + response.statusCode());
        }
        JsonNode properties = new ObjectMapper().readTree(response.body()).path("properties");
        Map<String, Object> out = new LinkedHashMap<>();
        properties.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static String trimSlash(String s) {
        return s.replaceAll("/+$", "");
    }
}
