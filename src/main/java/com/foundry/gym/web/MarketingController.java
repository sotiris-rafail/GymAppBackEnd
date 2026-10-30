package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.CampaignRequest;
import com.foundry.gym.dto.Dtos.CampaignView;
import com.foundry.gym.dto.Dtos.MarketingStatus;
import com.foundry.gym.mail.MarketingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The admin's Marketing page (admin only, see SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/marketing")
public class MarketingController {

    private final MarketingService marketing;
    private final com.foundry.gym.config.FeatureFlags flags;

    public MarketingController(MarketingService marketing, com.foundry.gym.config.FeatureFlags flags) {
        this.marketing = marketing;
        this.flags = flags;
    }

    /** Every Marketing endpoint answers 404 while marketing.enabled is false in the config server. */
    @org.springframework.web.bind.annotation.ModelAttribute
    void requireOn() {
        if (!flags.marketing()) {
            throw new com.foundry.gym.web.ApiException(HttpStatus.NOT_FOUND, "Marketing is switched off.");
        }
    }

    @GetMapping("/status")
    public MarketingStatus status(@org.springframework.web.bind.annotation.RequestParam(required = false) Long gymId) {
        return marketing.status(gymId);
    }

    @GetMapping
    public List<CampaignView> list() {
        return marketing.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView create(@Valid @RequestBody CampaignRequest body) {
        return marketing.create(body);
    }

    @PutMapping("/{id}")
    public CampaignView update(@PathVariable Long id, @Valid @RequestBody CampaignRequest body) {
        return marketing.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        marketing.delete(id);
    }

    @PostMapping("/{id}/send")
    public CampaignView send(@PathVariable Long id) {
        return marketing.send(id);
    }
}
