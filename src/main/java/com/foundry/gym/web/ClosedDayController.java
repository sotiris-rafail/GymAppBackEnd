package com.foundry.gym.web;

import java.time.LocalDate;
import java.util.List;

import com.foundry.gym.dto.Dtos.ClosedDayRequest;
import com.foundry.gym.dto.Dtos.ClosedDayView;
import com.foundry.gym.dto.Dtos.ClosedPeriodRequest;
import com.foundry.gym.dto.Dtos.ClosedPeriodView;
import com.foundry.gym.service.ClosedDayService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Closed days: readable by any logged-in user, managed by the admin. Sundays are always closed and never listed. */
@RestController
@RequestMapping("/api")
public class ClosedDayController {

    private final ClosedDayService days;

    public ClosedDayController(ClosedDayService days) {
        this.days = days;
    }

    @GetMapping("/closed-days")
    public List<ClosedDayView> list() {
        return days.all();
    }

    @GetMapping("/admin/closed-periods")
    public List<ClosedPeriodView> periods() {
        return days.openPeriods();
    }

    /** kind is WEEK or MONTH; closed=true closes the current week/month for bookings, false opens it again. */
    @PutMapping("/admin/closed-periods/{kind}")
    public List<ClosedPeriodView> setPeriod(@PathVariable String kind, @Valid @RequestBody ClosedPeriodRequest body) {
        return days.setPeriod(kind.toUpperCase(java.util.Locale.ROOT), body.closed());
    }

    @PostMapping("/admin/closed-days")
    @ResponseStatus(HttpStatus.CREATED)
    public ClosedDayView add(@Valid @RequestBody ClosedDayRequest body) {
        return days.add(body);
    }

    @DeleteMapping("/admin/closed-days/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        days.remove(date);
    }
}
