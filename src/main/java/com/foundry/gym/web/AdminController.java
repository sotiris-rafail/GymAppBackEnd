package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.ClassAttendance;
import com.foundry.gym.dto.Dtos.ClassRequest;
import com.foundry.gym.dto.Dtos.ClassView;
import com.foundry.gym.dto.Dtos.MemberHistory;
import com.foundry.gym.dto.Dtos.MemberView;
import com.foundry.gym.dto.Dtos.PlanAssign;
import com.foundry.gym.dto.Dtos.PlanRequest;
import com.foundry.gym.dto.Dtos.PlanView;
import com.foundry.gym.service.AccountService;
import com.foundry.gym.service.BookingService;
import com.foundry.gym.service.CatalogService;
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

/** Everything under /api/admin needs the ADMIN role (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CatalogService catalog;
    private final AccountService accounts;
    private final BookingService bookings;
    private final java.time.Clock clock;

    public AdminController(CatalogService catalog, AccountService accounts, BookingService bookings, java.time.Clock clock) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.bookings = bookings;
        this.clock = clock;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(@Valid @RequestBody PlanRequest body) {
        return catalog.createPlan(body);
    }

    @PutMapping("/plans/{id}")
    public PlanView updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest body) {
        return catalog.updatePlan(id, body);
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable Long id) {
        catalog.deletePlan(id);
    }

    @PostMapping("/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassView createClass(@Valid @RequestBody ClassRequest body) {
        return catalog.createClass(body);
    }

    @PutMapping("/classes/{id}")
    public ClassView updateClass(@PathVariable Long id, @Valid @RequestBody ClassRequest body) {
        return catalog.updateClass(id, body);
    }

    @DeleteMapping("/classes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClass(@PathVariable Long id) {
        catalog.deleteClass(id);
    }

    @GetMapping("/members")
    public List<MemberView> members() {
        return accounts.members();
    }

    /** Assigns, replaces or (planId null) removes a member's plan. */
    @PutMapping("/members/{id}/plan")
    public MemberView assignPlan(@PathVariable Long id, @RequestBody PlanAssign body) {
        return accounts.assignPlan(id, body.planId());
    }

    /** Plans the member has held and visits used per month. */
    @GetMapping("/members/{id}/history")
    public MemberHistory history(@PathVariable Long id) {
        return accounts.history(id);
    }

    /** Who is booked into each class in a month (yyyy-MM, default: the current month). */
    @GetMapping("/attendance")
    public List<ClassAttendance> attendance(@org.springframework.web.bind.annotation.RequestParam(required = false) String month) {
        java.time.YearMonth m;
        try {
            m = month == null || month.isBlank() ? java.time.YearMonth.now(clock) : java.time.YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Month must look like 2026-10.");
        }
        return bookings.attendance(m);
    }
}
