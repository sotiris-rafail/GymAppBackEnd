package com.foundry.gym.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import com.foundry.gym.config.GymProperties;
import com.foundry.gym.domain.AppSetting;
import com.foundry.gym.repo.AppSettingRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How far ahead members may book. Two rules work together, and a date is bookable only when it passes both:
 * <ul>
 * <li>the window the admin chooses: ANY (no extra limit), WEEK (only the current week, Monday to Sunday) or MONTH
 * (only the current calendar month);</li>
 * <li>the booking horizon {@code gym.booking-horizon-days}: never more than that many days after today.</li>
 * </ul>
 * So the last day a member can book is the earlier of today + horizon and the end of the window.
 */
@Service
public class BookingWindowService {

    static final String KEY = "booking.window";

    private final AppSettingRepository settings;
    private final Clock clock;

    private final int horizonDays;

    public BookingWindowService(AppSettingRepository settings, Clock clock, GymProperties props) {
        this.settings = settings;
        this.clock = clock;
        this.horizonDays = Math.max(1, props.bookingHorizonDays());
    }

    /** The booking horizon: how many days after today members may book at most. */
    public int horizonDays() {
        return horizonDays;
    }

    /** The last day members can book right now: the earlier of today + horizon and the end of the chosen window. */
    @Transactional(readOnly = true)
    public LocalDate lastBookable() {
        return lastBookable(mode());
    }

    public LocalDate lastBookable(String mode) {
        LocalDate today = LocalDate.now(clock);
        LocalDate byHorizon = today.plusDays(horizonDays);
        LocalDate byWindow = switch (mode) {
            case "WEEK" -> today.with(DayOfWeek.MONDAY).plusDays(6);
            case "MONTH" -> YearMonth.from(today).atEndOfMonth();
            default -> null;
        };
        return byWindow == null || byHorizon.isBefore(byWindow) ? byHorizon : byWindow;
    }

    @Transactional(readOnly = true)
    public String mode() {
        return settings.findById(KEY).map(AppSetting::getSettingValue).orElse("ANY");
    }

    @Transactional
    public String setMode(String mode) {
        if (!mode.equals("ANY") && !mode.equals("WEEK") && !mode.equals("MONTH")) {
            throw ApiException.badRequest("Mode must be ANY, WEEK or MONTH.");
        }
        AppSetting s = settings.findById(KEY).orElseGet(() -> new AppSetting(KEY, mode));
        s.setSettingValue(mode);
        settings.save(s);
        return mode;
    }

    /** True when members may book this date: it is inside the chosen window and within the booking horizon. */
    @Transactional(readOnly = true)
    public boolean allows(LocalDate date) {
        return allows(mode(), date);
    }

    public boolean allows(String mode, LocalDate date) {
        return inWindow(mode, date) && withinHorizon(date);
    }

    /** Not more than the booking horizon after today (dates in the past are not this rule's business). */
    public boolean withinHorizon(LocalDate date) {
        return !date.isAfter(LocalDate.now(clock).plusDays(horizonDays));
    }

    /** Inside the week or month the admin chose (always true for ANY). */
    public boolean inWindow(String mode, LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        switch (mode) {
            case "WEEK": {
                LocalDate monday = today.with(DayOfWeek.MONDAY);
                return !date.isBefore(monday) && !date.isAfter(monday.plusDays(6));
            }
            case "MONTH":
                return YearMonth.from(date).equals(YearMonth.from(today));
            default:
                return true;
        }
    }
}
