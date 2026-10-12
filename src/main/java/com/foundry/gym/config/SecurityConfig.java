package com.foundry.gym.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Lets the separately hosted UI call the API from the browser and send the session cookie. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(GymProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsOrigins() == null ? List.of() : props.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Accept"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /** Logins go through AuthController, so there is no user store for Spring to generate a default user from. */
    @Bean
    public UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository repo) throws Exception {
        http
            // Session cookie is SameSite=Lax and the API only accepts JSON, which keeps this POC safe from
            // cross-site form posts. Turn CSRF protection on before exposing it to the internet.
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .securityContext(sc -> sc.securityContextRepository(repo))
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.changeSessionId()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/me/**", "/api/bookings/**", "/api/sessions/**").hasRole("MEMBER")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "Please log in to continue."))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "You do not have access to that.")))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
