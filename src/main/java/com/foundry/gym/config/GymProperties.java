package com.foundry.gym.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gym")
public record GymProperties(String timezone, boolean seedDemoData, List<String> corsOrigins, Admin admin,
                          @DefaultValue("90") int bookingHorizonDays) {
    public record Admin(String email, String password) {
    }
}
