package com.foundry.gym.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** One clock for the whole app, so "has this class started" uses the gym's time zone. */
    @Bean
    public Clock clock(GymProperties props) {
        String tz = props.timezone();
        ZoneId zone = (tz == null || tz.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(tz.trim());
        return Clock.system(zone);
    }
}
