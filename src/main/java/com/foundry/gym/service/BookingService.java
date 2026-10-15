package com.foundry.gym.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.dto.Dtos.Attendee;
import com.foundry.gym.dto.Dtos.BookingView;
import com.foundry.gym.dto.Dtos.ClassAttendance;
import com.foundry.gym.dto.Dtos.SessionAttendance;
import com.foundry.gym.dto.Dtos.SessionView;
import com.foundry.gym.dto.Views;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.config.GymProperties;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private static final int MAX_DAYS_PER_LOOKUP = 62;

    private final BookingRepository bookings;
    private final GymClassRepository classes;
    private final AppUserRepository users;
    private final Clock clock;
    private final ClosedDayService closedDays;
    private final int maxDaysAhead;

    public BookingService(BookingRepository bookings, GymClassRepository classes, AppUserRepository users, Clock clock,
                          GymProperties props, ClosedDayService closedDays) {
        this.closedDays = closedDays;
        this.maxDaysAhead = Math.max(1, props.bookingHorizonDays());
        this.bookings = bookings;
        this.classes = classes;
        this.users = users;
        this.clock = clock;
    }

    /** Every class occurrence in the date range, with how many seats are taken and whether this member holds one. */
    @Transactional(readOnly = true)
    public List<SessionView> sessions(Long userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("'to' must not be before 'from'.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS_PER_LOOKUP) {
            throw ApiException.badRequest("Ask for at most " + MAX_DAYS_PER_LOOKUP + " days at a time.");
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : bookings.countPerSession(from, to)) {
            counts.put(row[0] + "|" + row[1], ((Number) row[2]).intValue());
        }
        Map<String, Long> mine = new HashMap<>();
        for (Booking b : bookings.findForUserBetween(userId, from, to)) {
            mine.put(b.getGymClass().getId() + "|" + b.getSessionDate(), b.getId());
        }
        List<GymClass> all = classes.findAll().stream()
            .sorted(Comparator.comparing(GymClass::getStartTime).thenComparing(GymClass::getId))
            .toList();

        java.util.Set<LocalDate> closed = closedDays.closedBetween(from, to);
        List<SessionView> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (ClosedDayService.isSunday(d) || closed.contains(d)) {
                continue; // closed: nothing to book
            }
            for (GymClass c : all) {
                if (!c.getDays().contains(d.getDayOfWeek())) {
                    continue;
                }
                String key = c.getId() + "|" + d;
                Long bookingId = mine.get(key);
                out.add(new SessionView(c.getId(), d.toString(), c.getName(), c.getCoach() == null ? "" : c.getCoach(),
                    Views.time(c.getStartTime()), c.getDurationMinutes(), c.getCapacity(),
                    counts.getOrDefault(key, 0), bookingId != null, bookingId));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<BookingView> myBookings(Long userId) {
        return bookings.findForUser(userId).stream().map(Views::booking).toList();
    }

    /**
     * Books one seat. The member row and then the class row are locked (always in that order),
     * so capacity and the monthly visit limit hold even when requests arrive at the same time.
     */
    @Transactional
    public BookingView book(Long userId, Long classId, LocalDate date) {
        AppUser user = users.findByIdForUpdate(userId).orElseThrow(() -> ApiException.notFound("Member"));
        Plan plan = user.getPlan();
        if (plan == null) {
            throw ApiException.conflict("You need a plan before you can book a class. Ask the gym to assign one.");
        }
        GymClass c = classes.findByIdForUpdate(classId).orElseThrow(() -> ApiException.notFound("Class"));

        if (!c.getDays().contains(date.getDayOfWeek())) {
            throw ApiException.badRequest(c.getName() + " does not run on " + date.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s.");
        }
        if (closedDays.isClosed(date)) {
            throw ApiException.conflict(ClosedDayService.isSunday(date) ? "The gym is closed on Sundays." : "The gym is closed on that day.");
        }
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today.plusDays(maxDaysAhead))) {
            throw ApiException.badRequest("You can book up to " + maxDaysAhead + " days ahead.");
        }
        if (LocalDateTime.of(date, c.getStartTime()).isBefore(LocalDateTime.now(clock))) {
            throw ApiException.conflict("That class has already started.");
        }
        if (bookings.existsByUserIdAndGymClassIdAndSessionDate(userId, classId, date)) {
            throw ApiException.conflict("You already booked this class.");
        }
        if (bookings.countByGymClassIdAndSessionDate(classId, date) >= c.getCapacity()) {
            throw ApiException.conflict("That class is full.");
        }
        YearMonth month = YearMonth.from(date);
        long used = bookings.countByUserIdAndSessionDateBetween(userId, month.atDay(1), month.atEndOfMonth());
        if (used >= plan.getVisitsPerMonth()) {
            throw ApiException.conflict("No visits left in " + month.getMonth()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ". Your plan includes " + plan.getVisitsPerMonth() + ".");
        }
        return Views.booking(bookings.save(new Booking(user, c, date)));
    }

    /** Cancelling a class that has not started gives the visit back (the booking simply disappears). */
    @Transactional
    public void cancel(Long userId, Long bookingId) {
        Booking b = bookings.findByIdAndUserId(bookingId, userId).orElseThrow(() -> ApiException.notFound("Booking"));
        GymClass c = b.getGymClass();
        if (LocalDateTime.of(b.getSessionDate(), c.getStartTime()).isBefore(LocalDateTime.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, "Classes that already started cannot be cancelled.");
        }
        bookings.delete(b);
    }

    /** Admin view: for every class, who is booked on which date in the month (all classes are listed, even empty ones). */
    @Transactional(readOnly = true)
    public List<ClassAttendance> attendance(YearMonth month) {
        Map<Long, Map<LocalDate, List<Attendee>>> byClass = new HashMap<>();
        for (Booking b : bookings.findAllBetween(month.atDay(1), month.atEndOfMonth())) {
            byClass.computeIfAbsent(b.getGymClass().getId(), k -> new java.util.TreeMap<>())
                .computeIfAbsent(b.getSessionDate(), k -> new ArrayList<>())
                .add(new Attendee(b.getUser().getId(), b.getUser().getName()));
        }
        return classes.findAll(org.springframework.data.domain.Sort.by("startTime", "id")).stream().map(c -> {
            Map<LocalDate, List<Attendee>> dates = byClass.getOrDefault(c.getId(), Map.of());
            List<SessionAttendance> sessions = dates.entrySet().stream()
                .map(e -> new SessionAttendance(e.getKey(), e.getValue())).toList();
            int total = sessions.stream().mapToInt(x -> x.attendees().size()).sum();
            return new ClassAttendance(c.getId(), c.getName(), c.getCoach(), c.getStartTime(), c.getCapacity(), total, sessions);
        }).toList();
    }
}
