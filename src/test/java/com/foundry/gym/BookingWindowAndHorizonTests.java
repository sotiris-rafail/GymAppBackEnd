package com.foundry.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.foundry.gym.config.GymProperties;
import com.foundry.gym.domain.AppSetting;
import com.foundry.gym.repo.AppSettingRepository;
import com.foundry.gym.service.BookingWindowService;
import org.junit.jupiter.api.Test;

/**
 * The admin's window (any day, current week, current month) and gym.booking-horizon-days work together: a day is
 * bookable only when it passes both, so the last bookable day is the earlier of today + horizon and the end of the window.
 * "Today" is Wednesday 14 October 2026 (the week is Mon 12 to Sun 18 October).
 */
class BookingWindowAndHorizonTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

    private static BookingWindowService service(String mode, int horizonDays) {
        AppSettingRepository repo = mock(AppSettingRepository.class);
        when(repo.findById("booking.window")).thenReturn(Optional.of(new AppSetting("booking.window", mode)));
        return new BookingWindowService(repo, CLOCK, new GymProperties(null, false, List.of(), null, horizonDays));
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void the_last_bookable_day_is_the_earlier_of_the_horizon_and_the_end_of_the_window() {
        assertEquals(d("2027-01-12"), service("ANY", 90).lastBookable());   // no window: only the horizon
        assertEquals(d("2026-10-19"), service("ANY", 5).lastBookable());
        assertEquals(d("2026-10-18"), service("WEEK", 5).lastBookable());   // the week ends first
        assertEquals(d("2026-10-16"), service("WEEK", 2).lastBookable());   // the horizon ends first
        assertEquals(d("2026-10-31"), service("MONTH", 40).lastBookable()); // the month ends first
        assertEquals(d("2026-10-19"), service("MONTH", 5).lastBookable());  // the horizon ends first
    }

    @Test
    void a_day_must_be_inside_the_window_and_within_the_horizon() {
        BookingWindowService week = service("WEEK", 2); // window Mon 12 to Sun 18, horizon up to Fri 16
        assertTrue(week.allows(d("2026-10-14")));
        assertTrue(week.allows(d("2026-10-16")));
        assertFalse(week.allows(d("2026-10-17")), "inside the week but beyond the horizon");
        assertFalse(week.allows(d("2026-10-19")), "next week");
        assertFalse(week.allows(d("2026-10-11")), "last week");

        BookingWindowService any = service("ANY", 5);
        assertTrue(any.allows(d("2026-10-19")));
        assertFalse(any.allows(d("2026-10-20")), "beyond the horizon even without a window");
    }

    @Test
    void the_horizon_is_at_least_one_day() {
        assertEquals(1, service("ANY", 0).horizonDays());
        assertEquals(d("2026-10-15"), service("ANY", 0).lastBookable());
    }
}
