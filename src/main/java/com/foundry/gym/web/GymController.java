package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.GymChoice;
import com.foundry.gym.dto.Dtos.GymRequest;
import com.foundry.gym.dto.Dtos.GymStatusBody;
import com.foundry.gym.dto.Dtos.GymView;
import com.foundry.gym.dto.Dtos.SwitchOffCheck;
import com.foundry.gym.service.GymService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gyms. Members and the admin read the choices at /api/gyms (only the gyms that can be chosen, and none while several
 * gyms are switched off); the admin manages them under /api/admin/gyms. Changes need the switch on; the list and the
 * switch-off check always work, so the owner can tidy up after switching off.
 */
@RestController
@RequestMapping("/api")
public class GymController {

    private final GymService gyms;

    public GymController(GymService gyms) {
        this.gyms = gyms;
    }

    @GetMapping("/gyms")
    public List<GymChoice> choices() {
        return gyms.choices();
    }

    @GetMapping("/admin/gyms")
    public List<GymView> list() {
        return gyms.list();
    }

    @PostMapping("/admin/gyms")
    @ResponseStatus(HttpStatus.CREATED)
    public GymView create(@Valid @RequestBody GymRequest body) {
        return gyms.create(body);
    }

    @PutMapping("/admin/gyms/{id}")
    public GymView update(@PathVariable Long id, @Valid @RequestBody GymRequest body) {
        return gyms.update(id, body);
    }

    @PutMapping("/admin/gyms/{id}/default")
    public GymView makeDefault(@PathVariable Long id) {
        return gyms.makeDefault(id);
    }

    @PutMapping("/admin/gyms/{id}/status")
    public GymView status(@PathVariable Long id, @RequestBody GymStatusBody body) {
        return gyms.setStatus(id, body.status());
    }

    @GetMapping("/admin/gyms/switch-off-check")
    public SwitchOffCheck switchOffCheck() {
        return gyms.switchOffCheck();
    }

    @PostMapping("/admin/gyms/{id}/move-members-to-default")
    public int moveMembers(@PathVariable Long id) {
        return gyms.moveMembersToDefault(id);
    }

    @PostMapping("/admin/gyms/{id}/share-plans")
    public int sharePlans(@PathVariable Long id) {
        return gyms.sharePlans(id);
    }
}
