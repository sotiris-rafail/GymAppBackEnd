package com.foundry.gym.web;

import com.foundry.gym.config.FeatureFlags;
import com.foundry.gym.dto.Dtos.FeaturesBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which optional features are switched on in the config server. Readable by any signed-in user. */
@RestController
@RequestMapping("/api")
public class FeatureController {

    private final FeatureFlags flags;
    private final com.foundry.gym.mail.MailSettings mail;

    public FeatureController(FeatureFlags flags, com.foundry.gym.mail.MailSettings mail) {
        this.flags = flags;
        this.mail = mail;
    }

    @GetMapping("/features")
    public FeaturesBody features() {
        return new FeaturesBody(flags.unlimitedPlans(), mail.enabled(), flags.analytics(), flags.home(), flags.multiGym(), flags.marketing());
    }
}
