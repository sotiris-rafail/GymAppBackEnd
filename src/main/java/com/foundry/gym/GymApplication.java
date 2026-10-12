package com.foundry.gym;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GymApplication {
    public static void main(String[] args) {
        SpringApplication.run(GymApplication.class, args);
    }
}
