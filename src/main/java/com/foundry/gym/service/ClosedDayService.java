package com.foundry.gym.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.foundry.gym.domain.ClosedDay;
import com.foundry.gym.dto.Dtos.ClosedDayRequest;
import com.foundry.gym.dto.Dtos.ClosedDayView;
import com.foundry.gym.repo.ClosedDayRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Days with no classes: every Sunday, plus the dates the admin closes (holidays, maintenance...). */
@Service
public class ClosedDayService {

    private final ClosedDayRepository repo;

    public ClosedDayService(ClosedDayRepository repo) {
        this.repo = repo;
    }

    public static boolean isSunday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** True for Sundays and for any date the admin closed. */
    @Transactional(readOnly = true)
    public boolean isClosed(LocalDate d) {
        return isSunday(d) || repo.existsByClosedOn(d);
    }

    /** The admin-closed dates inside a range (Sundays are implied, not listed). */
    @Transactional(readOnly = true)
    public Set<LocalDate> closedBetween(LocalDate from, LocalDate to) {
        return repo.findByClosedOnBetween(from, to).stream().map(ClosedDay::getClosedOn).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<ClosedDayView> all() {
        return repo.findAllByOrderByClosedOnAsc().stream().map(ClosedDayService::view).toList();
    }

    @Transactional
    public ClosedDayView add(ClosedDayRequest r) {
        if (isSunday(r.date())) {
            throw ApiException.badRequest("Sundays are always closed.");
        }
        ClosedDay c = repo.findByClosedOn(r.date()).orElseGet(() -> new ClosedDay(r.date(), null));
        c.setReason(r.reason() == null || r.reason().isBlank() ? null : r.reason().trim());
        return view(repo.save(c));
    }

    @Transactional
    public void remove(LocalDate date) {
        ClosedDay c = repo.findByClosedOn(date).orElseThrow(() -> ApiException.notFound("Closed day"));
        repo.delete(c);
    }

    private static ClosedDayView view(ClosedDay c) {
        return new ClosedDayView(c.getClosedOn().toString(), c.getReason() == null ? "" : c.getReason());
    }
}
