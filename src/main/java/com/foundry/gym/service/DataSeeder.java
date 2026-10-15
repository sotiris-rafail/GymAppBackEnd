package com.foundry.gym.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.foundry.gym.config.GymProperties;
import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.PlanHistory;
import com.foundry.gym.domain.Role;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.PlanHistoryRepository;
import com.foundry.gym.repo.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** On an empty database: creates the admin account and, unless turned off, some example data. */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final GymProperties props;
    private final AppUserRepository users;
    private final PlanRepository plans;
    private final GymClassRepository classes;
    private final BookingRepository bookings;
    private final PlanHistoryRepository history;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public DataSeeder(GymProperties props, AppUserRepository users, PlanRepository plans, GymClassRepository classes,
                      BookingRepository bookings, PlanHistoryRepository history, PasswordEncoder encoder,
                      Clock clock) {
        this.history = history;
        this.props = props;
        this.users = users;
        this.plans = plans;
        this.classes = classes;
        this.bookings = bookings;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.count() > 0) {
            backfillHistory();
            return;
        }
        String adminEmail = AccountService.normalizeEmail(props.admin().email());
        users.save(new AppUser("Admin", adminEmail, encoder.encode(props.admin().password()), Role.ADMIN));
        log.info("Created admin account {}", adminEmail);

        if (!props.seedDemoData()) {
            return;
        }
        Plan starter = plans.save(new Plan("Starter", new BigDecimal("29.00"), 4));
        Plan regular = plans.save(new Plan("Regular", new BigDecimal("49.00"), 8));
        plans.save(new Plan("Committed", new BigDecimal("79.00"), 16));

        List<GymClass> created = classes.saveAll(List.of(
            new GymClass("Morning Strength", "Dana", LocalTime.of(6, 30), 60, 12,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            new GymClass("Lunch HIIT", "Marco", LocalTime.of(12, 15), 45, 16,
                Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)),
            new GymClass("Boxing Basics", "Imani", LocalTime.of(17, 30), 60, 8,
                Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)),
            new GymClass("Spin", "Lena", LocalTime.of(18, 30), 45, 20,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            new GymClass("Yoga Flow", "Priya", LocalTime.of(19, 30), 60, 14,
                Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY))));

        AppUser alex = new AppUser("Alex Rivera", "alex@demo.gym", encoder.encode("member123"), Role.MEMBER);
        alex.setPlan(regular);
        AppUser sam = new AppUser("Sam Okafor", "sam@demo.gym", encoder.encode("member123"), Role.MEMBER);
        sam.setPlan(starter);
        users.saveAll(List.of(alex, sam));

        // Plan history: Alex moved from Starter to Regular, Sam has been on Starter. Past months get a few visits.
        Instant nowI = Instant.now(clock);
        PlanHistory alexOld = new PlanHistory(alex.getId(), starter, nowI.minusSeconds(95L * 86400));
        alexOld.setEndedAt(nowI.minusSeconds(40L * 86400));
        history.save(alexOld);
        history.save(new PlanHistory(alex.getId(), regular, nowI.minusSeconds(40L * 86400)));
        history.save(new PlanHistory(sam.getId(), starter, nowI.minusSeconds(70L * 86400)));

        // A few real bookings so the calendar is not empty on first open.
        LocalDateTime now = LocalDateTime.now(clock);
        int alexBookings = 0;
        boolean samBooked = false;
        for (int i = 0; i < 21 && alexBookings < 2; i++) {
            LocalDate d = now.toLocalDate().plusDays(i);
            for (GymClass c : created) {
                if (d.getDayOfWeek() == DayOfWeek.SUNDAY || !c.getDays().contains(d.getDayOfWeek()) || LocalDateTime.of(d, c.getStartTime()).isBefore(now)) {
                    continue;
                }
                bookings.save(new Booking(alex, c, d));
                alexBookings++;
                if (!samBooked) {
                    bookings.save(new Booking(sam, c, d));
                    samBooked = true;
                }
                break;
            }
        }
        // A few past visits (earlier months) so the monthly usage list has something to show.
        LocalDate today = now.toLocalDate();
        for (int back = 1; back <= 3; back++) {
            LocalDate month = today.minusMonths(back).withDayOfMonth(1);
            int visits = back == 1 ? 6 : back == 2 ? 3 : 4;
            int made = 0;
            for (int day = 0; day < month.lengthOfMonth() && made < visits; day++) {
                LocalDate d = month.plusDays(day);
                for (GymClass c : created) {
                    if (d.getDayOfWeek() != DayOfWeek.SUNDAY && c.getDays().contains(d.getDayOfWeek()) && !bookings.existsByUserIdAndGymClassIdAndSessionDate(alex.getId(), c.getId(), d)) {
                        bookings.save(new Booking(alex, c, d));
                        made++;
                        break;
                    }
                }
            }
        }
        log.info("Seeded demo plans, classes, members and bookings");
    }

    /** Databases created before plan history existed: members who have a plan get an open period starting now. */
    private void backfillHistory() {
        Instant now = Instant.now(clock);
        for (AppUser u : users.findByRoleOrderByNameAsc(Role.MEMBER)) {
            if (u.getPlan() != null && history.findByUserIdAndEndedAtIsNull(u.getId()).isEmpty()) {
                history.save(new PlanHistory(u.getId(), u.getPlan(), now));
            }
        }
    }
}
