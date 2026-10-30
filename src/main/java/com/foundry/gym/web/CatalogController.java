package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.domain.Gym;
import com.foundry.gym.dto.Dtos.ClassView;
import com.foundry.gym.dto.Dtos.PlanView;
import com.foundry.gym.service.CatalogService;
import com.foundry.gym.service.GymService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only lists for any signed-in person (members choose from them, the admin edits them). Optional ?gymId= picks the gym. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;
    private final GymService gyms;

    public CatalogController(CatalogService catalog, GymService gyms) {
        this.catalog = catalog;
        this.gyms = gyms;
    }

    @GetMapping("/plans")
    public List<PlanView> plans(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long gymId) {
        Gym g = gyms.forPerson(me.role(), me.id(), gymId);
        return catalog.plans(g, me.role() == com.foundry.gym.domain.Role.MEMBER ? me.id() : null);
    }

    @GetMapping("/classes")
    public List<ClassView> classes(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long gymId) {
        return catalog.classes(gyms.forPerson(me.role(), me.id(), gymId));
    }
}
