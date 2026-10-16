package com.foundry.gym.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.foundry.gym.domain.ClosedDay;
import com.foundry.gym.domain.ClosedPeriod;
import com.foundry.gym.dto.Dtos.ClosedDayRequest;
import com.foundry.gym.dto.Dtos.ClosedPeriodView;
import com.foundry.gym.dto.Dtos.ClosedDayView;
import com.foundry.gym.repo.ClosedDayRepository;
import com.foundry.gym.repo.ClosedPeriodRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Days with no classes: every Sunday, the dates the admin closes (holidays, maintenance...) and whole weeks or months the admin closes. */
@Service
public class ClosedDayService {

    private final ClosedDayRepository repo;
    private final ClosedPeriodRepository periods;
    private final Clock clock;

    public ClosedDayService(ClosedDayRepository repo, ClosedPeriodRepository periods, Clock clock) {
        this.repo = repo;
        this.periods = periods;
        this.clock = clock;
    }

    public static boolean isSunday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** True for Sundays and for any date the admin closed. */
    @Transactional(readOnly = true)
    public boolean isClosed(LocalDate d) {
        return isSunday(d) || repo.existsByClosedOn(d) || !periods.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(d, d).isEmpty();
    }

    /** The admin-closed dates inside a range (Sundays are implied, not listed). */
    @Transactional(readOnly = true)
    public Set<LocalDate> closedBetween(LocalDate from, LocalDate to) {
        Set<LocalDate> out = repo.findByClosedOnBetween(from, to).stream().map(ClosedDay::getClosedOn).collect(Collectors.toSet());
        for (ClosedPeriod p : periods.findByEndDateGreaterThanEqualOrderByStartDateAsc(from)) {
            for (LocalDate d = p.getStartDate().isBefore(from) ? from : p.getStartDate(); !d.isAfter(p.getEndDate()) && !d.isAfter(to); d = d.plusDays(1)) {
                out.add(d);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ClosedDayView> all() {
        List<ClosedDayView> out = new java.util.ArrayList<>(repo.findAllByOrderByClosedOnAsc().stream().map(ClosedDayService::view).toList());
        Set<String> listed = new HashSet<>();
        out.forEach(v -> listed.add(v.date()));
        for (ClosedPeriod p : periods.findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.now(clock))) {
            for (LocalDate d = p.getStartDate(); !d.isAfter(p.getEndDate()); d = d.plusDays(1)) {
                if (!isSunday(d) && listed.add(d.toString())) {
                    out.add(new ClosedDayView(d.toString(), "", p.getKind()));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(ClosedDayView::date));
        return out;
    }

    /** Weeks and months that are closed and have not ended yet. */
    @Transactional(readOnly = true)
    public List<ClosedPeriodView> openPeriods() {
        return periods.findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.now(clock)).stream()
            .map(p -> new ClosedPeriodView(p.getKind(), p.getStartDate().toString(), p.getEndDate().toString())).toList();
    }

    /** Closes (or opens again) bookings for the current week (Monday to Sunday) or the current calendar month. */
    @Transactional
    public List<ClosedPeriodView> setPeriod(String kind, boolean closed) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from, to;
        if ("WEEK".equals(kind)) {
            from = today.with(DayOfWeek.MONDAY);
            to = from.plusDays(6);
        } else if ("MONTH".equals(kind)) {
            YearMonth m = YearMonth.from(today);
            from = m.atDay(1);
            to = m.atEndOfMonth();
        } else {
            throw ApiException.badRequest("Period must be WEEK or MONTH.");
        }
        List<ClosedPeriod> existing = periods.findByKindAndStartDate(kind, from);
        if (closed && existing.isEmpty()) {
            periods.save(new ClosedPeriod(kind, from, to));
        } else if (!closed) {
            periods.deleteAll(existing);
        }
        return openPeriods();
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
        return new ClosedDayView(c.getClosedOn().toString(), c.getReason() == null ? "" : c.getReason(), "");
    }
}
