package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.PromoRequest;
import com.foundry.gym.dto.Dtos.PromoView;
import com.foundry.gym.service.PromoService;
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

/** Home page items: visible to everyone at /api/promos, managed by the admin under /api/admin/promos. */
@RestController
@RequestMapping("/api")
public class PromoController {

    private final PromoService promos;

    public PromoController(PromoService promos) {
        this.promos = promos;
    }

    /** Open to visitors who are not logged in (see SecurityConfig). */
    @GetMapping("/promos")
    public List<PromoView> visible() {
        return promos.visible();
    }

    @GetMapping("/admin/promos")
    public List<PromoView> all() {
        return promos.all();
    }

    @PostMapping("/admin/promos")
    @ResponseStatus(HttpStatus.CREATED)
    public PromoView create(@Valid @RequestBody PromoRequest body) {
        return promos.create(body);
    }

    @PutMapping("/admin/promos/{id}")
    public PromoView update(@PathVariable Long id, @Valid @RequestBody PromoRequest body) {
        return promos.update(id, body);
    }

    @DeleteMapping("/admin/promos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        promos.delete(id);
    }
}
