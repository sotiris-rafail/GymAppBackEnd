package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.ClassView;
import com.foundry.gym.dto.Dtos.PlanView;
import com.foundry.gym.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only lists for any signed-in person (members choose from them, the admin edits them). */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/plans")
    public List<PlanView> plans() {
        return catalog.plans();
    }

    @GetMapping("/classes")
    public List<ClassView> classes() {
        return catalog.classes();
    }
}
