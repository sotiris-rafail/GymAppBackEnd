package com.foundry.gym.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Locale;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.PlanHistory;
import com.foundry.gym.domain.Role;
import com.foundry.gym.dto.Dtos.MemberHistory;
import com.foundry.gym.dto.Dtos.MemberView;
import com.foundry.gym.dto.Dtos.MonthUsage;
import com.foundry.gym.dto.Dtos.PlanPeriod;
import com.foundry.gym.dto.Dtos.UserView;
import com.foundry.gym.dto.Views;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.PlanHistoryRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AppUserRepository users;
    private final PlanRepository plans;
    private final BookingRepository bookings;
    private final PlanHistoryRepository history;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public AccountService(AppUserRepository users, PlanRepository plans, BookingRepository bookings,
                          PlanHistoryRepository history, PasswordEncoder encoder, Clock clock) {
        this.history = history;
        this.users = users;
        this.plans = plans;
        this.bookings = bookings;
        this.encoder = encoder;
        this.clock = clock;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Members and the admin log in through separate tabs, so the role has to match as well. */
    @Transactional(readOnly = true)
    public UserView login(String email, String password, Role role) {
        AppUser u = users.findByEmail(normalizeEmail(email))
            .filter(x -> x.getRole() == role && encoder.matches(password, x.getPasswordHash()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "That email and password do not match " + (role == Role.ADMIN ? "an admin" : "a member") + " account."));
        return Views.user(u);
    }

    @Transactional
    public UserView registerMember(String name, String email, String password) {
        String mail = normalizeEmail(email);
        if (users.existsByEmail(mail)) {
            throw ApiException.conflict("An account with that email already exists. Log in instead.");
        }
        AppUser u = users.save(new AppUser(name.trim(), mail, encoder.encode(password), Role.MEMBER));
        return Views.user(u);
    }

    /** Needs the current password, so a borrowed or left-open session cannot take over the account. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        AppUser u = users.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in to continue."));
        if (!encoder.matches(currentPassword, u.getPasswordHash())) {
            // 400, not 401: the person is still signed in, they just typed the wrong password
            throw ApiException.badRequest("Your current password is not correct.");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("Choose a password that is different from the current one.");
        }
        u.setPasswordHash(encoder.encode(newPassword));
    }

    @Transactional(readOnly = true)
    public UserView me(Long userId) {
        return users.findById(userId)
            .map(Views::user)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in to continue."));
    }

    /**
     * Only the admin assigns plans (AdminController). A member holds exactly one plan, or none, so assigning
     * replaces the previous plan and a null planId removes it. Classes already booked stay booked.
     */
    @Transactional
    public MemberView assignPlan(Long memberId, Long planId) {
        AppUser u = users.findByIdForUpdate(memberId)
            .filter(x -> x.getRole() == Role.MEMBER)
            .orElseThrow(() -> ApiException.notFound("Member"));
        Plan next = planId == null ? null : plans.findById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
        Long before = u.getPlan() == null ? null : u.getPlan().getId();
        if (!Objects.equals(before, planId)) { // assigning the plan they already have changes nothing
            Instant now = Instant.now(clock);
            history.findByUserIdAndEndedAtIsNull(memberId).forEach(h -> h.setEndedAt(now));
            if (next != null) {
                history.save(new PlanHistory(memberId, next, now));
            }
        }
        u.setPlan(next);
        return memberView(u, YearMonth.from(LocalDate.now(clock)));
    }

    /** Admin overview: every member with their plan and visits used in the current month. */
    @Transactional(readOnly = true)
    public List<MemberView> members() {
        YearMonth month = YearMonth.from(LocalDate.now(clock));
        return users.findByRoleOrderByNameAsc(Role.MEMBER).stream().map(u -> memberView(u, month)).toList();
    }

    /** Every plan the member has held (newest first) and the visits used in each month they have bookings. */
    @Transactional(readOnly = true)
    public MemberHistory history(Long memberId) {
        AppUser u = users.findById(memberId).filter(x -> x.getRole() == Role.MEMBER)
            .orElseThrow(() -> ApiException.notFound("Member"));
        List<PlanHistory> rows = history.findByUserIdOrderByStartedAtDescIdDesc(memberId);
        List<PlanPeriod> periods = rows.stream()
            .map(h -> new PlanPeriod(h.getPlanName(), h.getPrice(), h.getVisitsPerMonth(), h.getStartedAt(), h.getEndedAt()))
            .toList();

        ZoneId zone = clock.getZone();
        YearMonth current = YearMonth.from(LocalDate.now(clock));
        Map<YearMonth, Long> used = new TreeMap<>(Comparator.reverseOrder());
        for (Object[] r : bookings.countPerMonthForUser(memberId)) {
            used.put(YearMonth.of(((Number) r[0]).intValue(), ((Number) r[1]).intValue()), ((Number) r[2]).longValue());
        }
        used.putIfAbsent(current, 0L); // the current month is always listed

        List<MonthUsage> months = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (Map.Entry<YearMonth, Long> e : used.entrySet()) {
            // The plan that applied at the end of that month (or right now, for the current month).
            Instant at = e.getKey().equals(current) ? now
                : e.getKey().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusSeconds(1);
            PlanHistory p = rows.stream()
                .filter(h -> !h.getStartedAt().isAfter(at) && (h.getEndedAt() == null || h.getEndedAt().isAfter(at)))
                .findFirst().orElse(null);
            months.add(new MonthUsage(e.getKey().toString(), e.getValue(),
                p == null ? null : p.getPlanName(), p == null ? null : p.getVisitsPerMonth()));
        }
        return new MemberHistory(u.getId(), u.getName(), u.getEmail(), periods, months);
    }

    private MemberView memberView(AppUser u, YearMonth month) {
        Plan p = u.getPlan();
        int used = (int) bookings.countByUserIdAndSessionDateBetween(u.getId(), month.atDay(1), month.atEndOfMonth());
        return new MemberView(u.getId(), u.getName(), u.getEmail(),
            p == null ? null : p.getId(), p == null ? null : p.getName(),
            p == null ? null : p.getVisitsPerMonth(), used);
    }
}
