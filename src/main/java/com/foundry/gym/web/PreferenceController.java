package com.foundry.gym.web;

import com.foundry.gym.dto.Dtos.PreferencesBody;
import com.foundry.gym.service.PreferenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in member's email preferences (member only, see SecurityConfig: /api/me/**). */
@RestController
@RequestMapping("/api/me/preferences")
public class PreferenceController {

    private final PreferenceService prefs;

    public PreferenceController(PreferenceService prefs) {
        this.prefs = prefs;
    }

    @GetMapping
    public PreferencesBody get(@AuthenticationPrincipal AuthUser me) {
        return prefs.get(me.id());
    }

    @PutMapping
    public PreferencesBody save(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody PreferencesBody body) {
        return prefs.save(me.id(), body);
    }
}
