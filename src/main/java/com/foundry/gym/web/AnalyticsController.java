package com.foundry.gym.web;

import java.time.LocalDate;

import com.foundry.gym.service.AnalyticsService;
import com.foundry.gym.service.AnalyticsService.Report;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin's Analytics page (admin only, see SecurityConfig: /api/admin/**). Read-only. */
@RestController
@RequestMapping("/api/admin/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** from and to are yyyy-MM-dd. Without "to" the period ends today; without "from" it is the last 12 weeks. */
    @GetMapping
    public Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long gymId) {
        return analytics.report(from, to, gymId);
    }
}
