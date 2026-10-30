package com.foundry.gym.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.foundry.gym.config.FeatureFlags;
import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.MemberPreference;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.PlanHistory;
import com.foundry.gym.domain.Role;
import com.foundry.gym.dto.Dtos.CampaignView;
import com.foundry.gym.mail.MarketingService;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.MemberPreferenceRepository;
import com.foundry.gym.repo.PlanHistoryRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only numbers for the admin's Analytics page. Everything is worked out from data that is already stored
 * (bookings, classes, plans, plan history, preferences, campaigns); nothing is written.
 *
 * The page has a chosen period (from..to, the end never later than today) and the numbers are compared with the
 * period of the same length just before it. A few blocks describe the situation right now and ignore the period.
 */
@Service
public class AnalyticsService {

    static final int MAX_DAYS = 366;

    private final BookingRepository bookings;
    private final GymClassRepository classes;
    private final AppUserRepository users;
    private final PlanRepository plans;
    private final PlanHistoryRepository history;
    private final MemberPreferenceRepository prefs;
    private final ClosedDayService closed;
    private final MarketingService marketing;
    private final Clock clock;
    private final FeatureFlags flags;
    private final GymService gyms;

    public AnalyticsService(BookingRepository bookings, GymClassRepository classes, AppUserRepository users,
                            PlanRepository plans, PlanHistoryRepository history, MemberPreferenceRepository prefs,
                            ClosedDayService closed, MarketingService marketing, Clock clock, FeatureFlags flags,
                            GymService gyms) {
        this.gyms = gyms;
        this.bookings = bookings;
        this.classes = classes;
        this.users = users;
        this.plans = plans;
        this.history = history;
        this.prefs = prefs;
        this.closed = closed;
        this.marketing = marketing;
        this.clock = clock;
        this.flags = flags;
    }

    // ---- what the page receives ---------------------------------------------------------------------------------

    /** A headline number with the same number for the previous period. changePct is null when there was nothing before. */
    public record Metric(long value, long previous, Integer change, List<Integer> spark) {
    }

    public record Point(String date, int bookings) {
    }

    public record ClassRow(String name, int bookings, Integer fillPct) {
    }

    public record PlanRow(String name, int members, BigDecimal monthly) {
    }

    public record Bucket(String label, int members) {
    }

    public record FollowUp(String name, String plan, Integer daysAgo) {
    }

    public record OptIn(String key, int members, int pct) {
    }

    public record Campaign(String subject, String sentAt, int recipients, long delivered, long failed, long skipped) {
    }

    /** One line of the "gyms compared" table (only while several gyms are in use). */
    public record GymRow(Long id, String name, String status, int members, int bookings, Integer fillPct) {
    }

    /** fillPct and usagePct are percentages (null when there is nothing to divide by); their change is in points. */
    public record Report(int followUpDays, int lowFillPct, String from, String to, String previousFrom, String previousTo, String bucket,
                         Metric bookings, Metric fill, Metric withPlan, Metric usage, int totalMembers,
                         List<Point> series, List<ClassRow> topClasses, int[][] heatmap,
                         List<PlanRow> planRows, int noPlan, BigDecimal monthlyIncome,
                         List<Bucket> usageBuckets, int unusedMembers,
                         List<FollowUp> followUp, int followUpTotal,
                         List<OptIn> optIns, List<Campaign> campaigns,
                         Long gymId, boolean multiGym, List<GymRow> gymRows) {
    }

    /** Bookings, members and fill rate of every gym for the same period, so the owner can compare them. */
    private List<GymRow> gymRows(List<Booking> inPeriod, LocalDate from, LocalDate to, Long defaultId) {
        List<AppUser> all = users.findByRoleOrderByNameAsc(Role.MEMBER);
        List<GymRow> rows = new ArrayList<>();
        for (com.foundry.gym.domain.Gym g : gyms.all()) {
            Set<LocalDate> shut = closed.closedBetween(g.getId(), from, to);
            long seats = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (ClosedDayService.isSunday(d) || shut.contains(d)) {
                    continue;
                }
                for (GymClass c : classes.findByGymId(g.getId())) {
                    if (c.getDays().contains(d.getDayOfWeek())) {
                        seats += c.getCapacity();
                    }
                }
            }
            int booked = (int) inPeriod.stream().filter(b -> g.getId().equals(b.getGymClass().getGymId())).count();
            int mem = (int) all.stream().filter(u -> g.getId().equals(GymService.effectiveGymId(u, defaultId))).count();
            rows.add(new GymRow(g.getId(), g.getName(), g.getStatus().name(), mem, booked,
                seats == 0 ? null : (int) Math.round(booked * 100.0 / seats)));
        }
        return rows;
    }

    // ---- the report ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Report report(LocalDate fromIn, LocalDate toIn, Long gymIdIn) {
        if (!flags.analytics()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Analytics is switched off.");
        }
        int followDays = flags.followUpDays();
        LocalDate today = LocalDate.now(clock);
        LocalDate to = toIn == null ? today : toIn;
        LocalDate from = fromIn == null ? to.minusWeeks(12).plusDays(1) : fromIn;
        if (to.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The end date cannot be in the future.");
        }
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The start date must be on or before the end date.");
        }
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        if (len > MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pick a period of at most one year.");
        }
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(len - 1);

        com.foundry.gym.domain.Gym gym = gyms.forAdmin(gymIdIn);
        boolean multi = gyms.multi();
        Long defaultId = gyms.defaultGym().getId();
        List<AppUser> members = users.findByRoleOrderByNameAsc(Role.MEMBER).stream()
            .filter(u -> !multi || gym.getId().equals(GymService.effectiveGymId(u, defaultId))).toList();
        List<PlanHistory> allHistory = history.findAll();
        Map<Long, List<PlanHistory>> historyByUser = new HashMap<>();
        for (PlanHistory h : allHistory) {
            historyByUser.computeIfAbsent(h.getUserId(), k -> new ArrayList<>()).add(h);
        }
        List<GymClass> allClasses = classes.findByGymId(gym.getId());
        List<Booking> allNow = bookings.findAllBetween(from, to);
        List<Booking> now = allNow.stream().filter(b -> gym.getId().equals(b.getGymClass().getGymId())).toList();
        List<Booking> before = bookings.findAllBetween(prevFrom, prevTo).stream()
            .filter(b -> gym.getId().equals(b.getGymClass().getGymId())).toList();

        // Seats on offer per day (open days only), for the fill rate.
        Set<LocalDate> shut = closed.closedBetween(gym.getId(), prevFrom, to);
        Map<LocalDate, Integer> seats = new HashMap<>();
        Map<Long, Integer> sessionsPerClass = new HashMap<>();
        for (LocalDate d = prevFrom; !d.isAfter(to); d = d.plusDays(1)) {
            if (ClosedDayService.isSunday(d) || shut.contains(d)) {
                continue;
            }
            for (GymClass c : allClasses) {
                if (c.getDays().contains(d.getDayOfWeek())) {
                    seats.merge(d, c.getCapacity(), Integer::sum);
                    if (!d.isBefore(from)) {
                        sessionsPerClass.merge(c.getId(), 1, Integer::sum);
                    }
                }
            }
        }

        // Buckets for the line chart: a point per day for short periods, otherwise per week (Monday first).
        boolean daily = len <= 35;
        List<LocalDate> starts = new ArrayList<>();
        for (LocalDate d = bucketStart(from, daily); !d.isAfter(to); d = d.plusDays(daily ? 1 : 7)) {
            starts.add(d);
        }
        int[] perBucket = new int[starts.size()];
        long[] seatsPerBucket = new long[starts.size()];
        for (Booking b : now) {
            perBucket[index(starts, b.getSessionDate(), daily)]++;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            seatsPerBucket[index(starts, d, daily)] += seats.getOrDefault(d, 0);
        }
        List<Point> series = new ArrayList<>();
        List<Integer> bookingSpark = new ArrayList<>();
        List<Integer> fillSpark = new ArrayList<>();
        List<Integer> planSpark = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            series.add(new Point(starts.get(i).toString(), perBucket[i]));
            bookingSpark.add(perBucket[i]);
            if (seatsPerBucket[i] > 0) {
                fillSpark.add((int) Math.round(100.0 * perBucket[i] / seatsPerBucket[i]));
            }
            LocalDate end = starts.get(i).plusDays(daily ? 0 : 6);
            planSpark.add(withPlanOn(members, historyByUser, end.isAfter(to) ? to : end));
        }

        // Headline numbers.
        long seatsNow = seatsIn(seats, from, to);
        long seatsBefore = seatsIn(seats, prevFrom, prevTo);
        Integer fillNow = pct(now.size(), seatsNow);
        Integer fillBefore = pct(before.size(), seatsBefore);
        int planNow = withPlanOn(members, historyByUser, to);
        int planBefore = withPlanOn(members, historyByUser, prevTo);

        // Most booked classes in the period.
        Map<Long, Integer> perClass = new HashMap<>();
        for (Booking b : now) {
            perClass.merge(b.getGymClass().getId(), 1, Integer::sum);
        }
        List<ClassRow> top = new ArrayList<>();
        for (GymClass c : allClasses) {
            int n = perClass.getOrDefault(c.getId(), 0);
            long cap = (long) sessionsPerClass.getOrDefault(c.getId(), 0) * c.getCapacity();
            top.add(new ClassRow(c.getName(), n, pct(n, cap)));
        }
        top.sort(Comparator.comparingInt(ClassRow::bookings).reversed().thenComparing(ClassRow::name));

        // Weekday x hour of the class start.
        int[][] heat = new int[7][24];
        for (Booking b : now) {
            heat[b.getSessionDate().getDayOfWeek().getValue() - 1][b.getGymClass().getStartTime().getHour()]++;
        }

        // Members by plan (right now).
        Map<Long, Integer> byPlan = new HashMap<>();
        int noPlan = 0;
        for (AppUser m : members) {
            if (m.getPlan() == null) {
                noPlan++;
            } else {
                byPlan.merge(m.getPlan().getId(), 1, Integer::sum);
            }
        }
        List<PlanRow> planRows = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        for (Plan p : plans.findAll()) {
            int n = byPlan.getOrDefault(p.getId(), 0);
            BigDecimal monthly = p.getPrice().multiply(BigDecimal.valueOf(n));
            income = income.add(monthly);
            planRows.add(new PlanRow(p.getName(), n, monthly));
        }
        planRows.sort(Comparator.comparingInt(PlanRow::members).reversed().thenComparing(PlanRow::name));

        // Plan usage this calendar month (right now), for members on a plan with a visit limit.
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate prevMonthStart = monthStart.minusMonths(1);
        LocalDate prevMonthEnd = monthStart.minusDays(1);
        Map<Long, Integer> usedThis = countPerUser(bookings.findAllBetween(monthStart, monthEnd));
        Map<Long, Integer> usedPrev = countPerUser(bookings.findAllBetween(prevMonthStart, prevMonthEnd));
        long included = 0;
        long usedNowSum = 0;
        long usedPrevSum = 0;
        int[] counts = new int[6];
        for (AppUser m : members) {
            Plan p = m.getPlan();
            if (p == null || p.isUnlimited() || p.getVisitsPerMonth() <= 0) {
                continue;
            }
            int limit = p.getVisitsPerMonth();
            int used = Math.min(usedThis.getOrDefault(m.getId(), 0), limit);
            included += limit;
            usedNowSum += used;
            usedPrevSum += Math.min(usedPrev.getOrDefault(m.getId(), 0), limit);
            counts[usageBucket(used, limit)]++;
        }
        String[] labels = {"0 visits", "1–25%", "26–50%", "51–75%", "76–99%", "All used"};
        List<Bucket> usageBuckets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            usageBuckets.add(new Bucket(labels[i], counts[i]));
        }
        Integer usageNow = pct(usedNowSum, included);
        Integer usagePrev = pct(usedPrevSum, included);

        // Members on a plan who have not booked for a while (their plan must be older than that, too).
        Map<Long, LocalDate> last = new HashMap<>();
        for (Object[] row : bookings.lastBookingPerUser(today)) {
            last.put((Long) row[0], (LocalDate) row[1]);
        }
        Map<Long, Instant> planSince = new HashMap<>();
        for (PlanHistory h : allHistory) {
            if (h.getEndedAt() == null) {
                planSince.merge(h.getUserId(), h.getStartedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        ZoneId zone = clock.getZone();
        List<FollowUp> follow = new ArrayList<>();
        for (AppUser m : members) {
            if (m.getPlan() == null) {
                continue;
            }
            Instant since = planSince.get(m.getId());
            if (since != null && since.atZone(zone).toLocalDate().isAfter(today.minusDays(followDays))) {
                continue;
            }
            LocalDate l = last.get(m.getId());
            if (l == null || l.isBefore(today.minusDays(followDays))) {
                follow.add(new FollowUp(m.getName(), m.getPlan().getName(), l == null ? null : (int) ChronoUnit.DAYS.between(l, today)));
            }
        }
        follow.sort(Comparator.comparing((FollowUp f) -> f.daysAgo() == null ? Integer.MAX_VALUE : f.daysAgo()).reversed()
            .thenComparing(FollowUp::name));

        // Who wants which emails, and what was sent.
        Set<Long> memberIds = new HashSet<>();
        members.forEach(m -> memberIds.add(m.getId()));
        int[] on = new int[4];
        for (MemberPreference p : prefs.findAll()) {
            if (!memberIds.contains(p.getUserId())) {
                continue;
            }
            on[0] += p.isMarketing() ? 1 : 0;
            on[1] += p.isNewPlans() ? 1 : 0;
            on[2] += p.isPlanEnding() ? 1 : 0;
            on[3] += p.isVisitsReminder() ? 1 : 0;
        }
        String[] keys = {"marketing", "newPlans", "planEnding", "visitsReminder"};
        List<OptIn> optIns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            optIns.add(new OptIn(keys[i], on[i], pct(on[i], members.size()) == null ? 0 : pct(on[i], members.size())));
        }
        List<Campaign> recent = marketing.list().stream()
            .filter(c -> "SENT".equals(c.status()) && c.sentAt() != null)
            .sorted(Comparator.comparing(CampaignView::sentAt).reversed())
            .limit(5)
            .map(c -> new Campaign(c.subject(), c.sentAt(), c.recipients(), c.delivered(), c.failed(), c.skipped()))
            .toList();

        return new Report(followDays, flags.lowFillPercent(), from.toString(), to.toString(), prevFrom.toString(), prevTo.toString(), daily ? "day" : "week",
            new Metric(now.size(), before.size(), change(now.size(), before.size()), bookingSpark),
            new Metric(fillNow == null ? 0 : fillNow, fillBefore == null ? 0 : fillBefore,
                fillNow == null || fillBefore == null ? null : fillNow - fillBefore, fillSpark),
            new Metric(planNow, planBefore, planNow - planBefore, planSpark),
            new Metric(usageNow == null ? 0 : usageNow, usagePrev == null ? 0 : usagePrev,
                usageNow == null || usagePrev == null ? null : usageNow - usagePrev, List.of()),
            members.size(), series, top, heat, planRows, noPlan, income, usageBuckets, counts[0],
            follow.stream().limit(5).toList(), follow.size(), optIns, recent,
            gym.getId(), multi, multi ? gymRows(allNow, from, to, defaultId) : List.of());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static LocalDate bucketStart(LocalDate d, boolean daily) {
        return daily ? d : d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    private static int index(List<LocalDate> starts, LocalDate d, boolean daily) {
        return (int) (daily ? ChronoUnit.DAYS.between(starts.get(0), d) : ChronoUnit.DAYS.between(starts.get(0), d) / 7);
    }

    private static long seatsIn(Map<LocalDate, Integer> seats, LocalDate from, LocalDate to) {
        long n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            n += seats.getOrDefault(d, 0);
        }
        return n;
    }

    /** Whole percent, or null when there is nothing to divide by. */
    static Integer pct(long part, long whole) {
        return whole <= 0 ? null : (int) Math.round(100.0 * part / whole);
    }

    /** Percent change against the earlier number; null when the earlier number is 0. */
    static Integer change(long now, long before) {
        return before == 0 ? null : (int) Math.round(100.0 * (now - before) / before);
    }

    private static Map<Long, Integer> countPerUser(List<Booking> list) {
        Map<Long, Integer> out = new HashMap<>();
        for (Booking b : list) {
            out.merge(b.getUser().getId(), 1, Integer::sum);
        }
        return out;
    }

    /** 0 = nothing used, 1..4 = up to 25/50/75/99 percent, 5 = every included visit used. */
    static int usageBucket(int used, int limit) {
        if (used <= 0) {
            return 0;
        }
        if (used >= limit) {
            return 5;
        }
        double share = 100.0 * used / limit;
        return share <= 25 ? 1 : share <= 50 ? 2 : share <= 75 ? 3 : 4;
    }

    /**
     * How many members held a plan at the end of the given day. Taken from the plan history; a member who has a plan
     * but no history at all (set before history was kept) counts as always having had it.
     */
    private int withPlanOn(List<AppUser> members, Map<Long, List<PlanHistory>> byUser, LocalDate day) {
        Instant end = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        int n = 0;
        for (AppUser m : members) {
            List<PlanHistory> rows = byUser.get(m.getId());
            if (rows == null || rows.isEmpty()) {
                n += m.getPlan() != null ? 1 : 0;
                continue;
            }
            for (PlanHistory h : rows) {
                if (h.getStartedAt().isBefore(end) && (h.getEndedAt() == null || h.getEndedAt().isAfter(end))) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }
}
