package com.foundry.gym.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import com.foundry.gym.domain.AppSetting;
import com.foundry.gym.repo.AppSettingRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How far ahead members may book: ANY (up to the usual booking horizon), WEEK (only the current week,
 * Monday to Sunday) or MONTH (only the current calendar month). Chosen by the admin.
 */
@Service
public class BookingWindowService {

    static final String KEY = "booking.window";

    private final AppSettingRepository settings;
    private final Clock clock;

    public BookingWindowService(AppSettingRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
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

    /** True when members may book this date under the current rule. */
    @Transactional(readOnly = true)
    public boolean allows(LocalDate date) {
        return allows(mode(), date);
    }

    public boolean allows(String mode, LocalDate date) {
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
