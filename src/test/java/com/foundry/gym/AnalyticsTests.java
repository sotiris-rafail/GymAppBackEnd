package com.foundry.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.MemberPreference;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.PlanHistory;
import com.foundry.gym.domain.Role;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.MemberPreferenceRepository;
import com.foundry.gym.repo.PlanHistoryRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.security.EmailCrypto;
import com.foundry.gym.service.AnalyticsService;
import com.foundry.gym.service.AnalyticsService.Report;
import com.foundry.gym.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:analytics;DB_CLOSE_DELAY=-1",
    "gym.seed-demo-data=false",
    "config.client.enabled=false",
    "gym.admin.email=boss@test.gym",
    "gym.admin.password=secret-admin",
    "gym.security.password-salt=test-password-salt-0123456789",
    "gym.security.email-key=test-email-key-0123456789abcdef",
    "gym.mail.enabled=false",
    "gym.mail.scan-seconds=86400", "gym.mail.send-seconds=86400", "gym.mail.initial-delay-seconds=86400"
})
@AutoConfigureMockMvc
class AnalyticsTests {

    @Autowired AnalyticsService analytics;
    @Autowired AppUserRepository users;
    @Autowired PlanRepository plans;
    @Autowired GymClassRepository classes;
    @Autowired BookingRepository bookings;
    @Autowired PlanHistoryRepository history;
    @Autowired MemberPreferenceRepository prefs;
    @Autowired EmailCrypto crypto;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired com.foundry.gym.config.FeatureFlags flags;

    private AppUser member(String name, Plan plan) {
        String email = name.toLowerCase() + "@analytics.test";
        AppUser u = new AppUser(name, email, crypto.index(email), "x", Role.MEMBER);
        u.setPlan(plan);
        return users.save(u);
    }

    @Test
    void the_numbers_come_from_the_stored_data() {
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        Plan plan = plans.save(new Plan("AnaPlan", new BigDecimal("20.00"), 4));
        Set<DayOfWeek> everyDay = Set.of(DayOfWeek.values());
        GymClass c1 = classes.save(new GymClass("AnaOne", "X", LocalTime.of(18, 0), 60, 4, everyDay));
        GymClass c2 = classes.save(new GymClass("AnaTwo", "X", LocalTime.of(18, 0), 60, 4, everyDay));
        GymClass c3 = classes.save(new GymClass("AnaThree", "X", LocalTime.of(18, 0), 60, 4, everyDay));

        AppUser a = member("Alice", plan);   // on the plan for 40 days, has booked
        AppUser b = member("Bob", plan);     // joined 5 days ago, has not booked
        AppUser c = member("Cleo", null);    // no plan
        AppUser d = member("Dora", plan);    // on the plan for 60 days, never booked
        history.save(new PlanHistory(a.getId(), plan, now.minus(40, ChronoUnit.DAYS)));
        history.save(new PlanHistory(b.getId(), plan, now.minus(5, ChronoUnit.DAYS)));
        history.save(new PlanHistory(d.getId(), plan, now.minus(60, ChronoUnit.DAYS)));
        for (GymClass k : new GymClass[] {c1, c2, c3}) {
            bookings.save(new Booking(a, k, today));
        }
        MemberPreference p = new MemberPreference(a.getId());
        p.setMarketing(true);
        prefs.save(p);

        Report r = analytics.report(today.minusDays(27), today, null);

        assertEquals(3, r.bookings().value());
        assertEquals(0, r.bookings().previous());
        assertNull(r.bookings().change(), "no bookings before: no percentage");
        assertEquals("week", r.bucket());
        assertEquals(3, r.series().stream().mapToInt(AnalyticsService.Point::bookings).sum());
        assertEquals(3, r.topClasses().stream().mapToInt(AnalyticsService.ClassRow::bookings).sum());
        assertEquals(3, r.heatmap()[today.getDayOfWeek().getValue() - 1][18]);

        // Members with a plan, now and 28 days ago (from the plan history).
        assertEquals(3, r.withPlan().value());
        assertEquals(2, r.withPlan().previous());
        assertEquals(1, r.withPlan().change());
        assertEquals(4, r.totalMembers());
        assertEquals(1, r.noPlan());
        assertEquals("AnaPlan", r.planRows().get(0).name());
        assertEquals(3, r.planRows().get(0).members());
        assertEquals(0, new BigDecimal("60.00").compareTo(r.monthlyIncome()));

        // Alice used 3 of her 4 visits, the others none: 3 of 12 included visits.
        assertEquals(3, r.usageBuckets().get(3).members());
        assertEquals(2, r.usageBuckets().get(0).members());
        assertEquals(2, r.unusedMembers());
        assertEquals(25, r.usage().value());

        // Bob is too new to chase, Alice booked today; Dora has had her plan for 60 days and never booked.
        assertEquals(1, r.followUpTotal());
        assertEquals("Dora", r.followUp().get(0).name());
        assertNull(r.followUp().get(0).daysAgo());

        // One of four members wants marketing emails.
        assertEquals(25, r.optIns().get(0).pct());
        assertEquals(0, r.optIns().get(1).pct());
    }

    @Test
    void a_short_period_is_shown_per_day_and_the_end_date_defaults_to_today() {
        LocalDate today = LocalDate.now(clock);
        Report r = analytics.report(today.minusDays(6), null, null);
        assertEquals(today.toString(), r.to());
        assertEquals("day", r.bucket());
        assertEquals(7, r.series().size());
        assertEquals(today.minusDays(13).toString(), r.previousFrom());
        assertEquals(today.minusDays(7).toString(), r.previousTo());
    }

    @Test
    void impossible_periods_are_refused() {
        LocalDate today = LocalDate.now(clock);
        assertEquals(400, assertThrows(ApiException.class, () -> analytics.report(today, today.plusDays(1), null)).status().value());
        assertEquals(400, assertThrows(ApiException.class, () -> analytics.report(today.minusDays(1), today.minusDays(2), null)).status().value());
        assertEquals(400, assertThrows(ApiException.class, () -> analytics.report(today.minusDays(400), today, null)).status().value());
    }

    @Test
    void only_the_admin_can_open_the_page() throws Exception {
        mvc.perform(get("/api/admin/analytics")).andExpect(status().isUnauthorized());
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"boss@test.gym\",\"password\":\"secret-admin\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession admin = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get("/api/admin/analytics").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.bucket").value("week"));
        mvc.perform(get("/api/admin/analytics").session(admin).param("from", "2999-01-01").param("to", "2999-02-01"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void the_config_server_switches_can_turn_the_page_off_and_change_the_thresholds() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"boss@test.gym\",\"password\":\"secret-admin\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession admin = (MockHttpSession) login.getRequest().getSession(false);
        try {
            mvc.perform(get("/api/features").session(admin)).andExpect(jsonPath("$.analytics").value(true));
            mvc.perform(get("/api/admin/analytics").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUpDays").value(30))
                .andExpect(jsonPath("$.lowFillPct").value(65));

            flags.setFollowUpDays(10);
            flags.setLowFillPercent(40);
            mvc.perform(get("/api/admin/analytics").session(admin))
                .andExpect(jsonPath("$.followUpDays").value(10))
                .andExpect(jsonPath("$.lowFillPct").value(40));

            flags.setAnalytics(false);
            mvc.perform(get("/api/features").session(admin)).andExpect(jsonPath("$.analytics").value(false));
            mvc.perform(get("/api/admin/analytics").session(admin)).andExpect(status().isNotFound());
            assertEquals(404, assertThrows(ApiException.class, () -> analytics.report(null, null, null)).status().value());
        } finally {
            flags.setAnalytics(true);
            flags.setFollowUpDays(30);
            flags.setLowFillPercent(65);
        }
    }
}
