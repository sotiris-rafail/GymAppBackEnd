package com.foundry.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.MemberPreference;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.Role;
import com.foundry.gym.mail.EmailGateway;
import com.foundry.gym.mail.NotificationPlanner;
import com.foundry.gym.mail.OutboxSender;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.MemberPreferenceRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.security.EmailCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** The schedulers behind the Preferences toggles, the home page "send as marketing" tick and the Marketing page. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:emailjobs;DB_CLOSE_DELAY=-1",
    "gym.seed-demo-data=false",
    "config.client.enabled=false",
    "gym.admin.email=boss@test.gym",
    "gym.admin.password=secret-admin",
    "gym.security.password-salt=test-password-salt-0123456789",
    "gym.security.email-key=test-email-key-0123456789abcdef",
    "gym.mail.enabled=true",
    "gym.mail.from=Test Gym <no-reply@test.gym>",
    "spring.mail.host=localhost",
    // the timers must not fire during the tests: the tests call the jobs themselves
    "gym.mail.scan-seconds=86400", "gym.mail.send-seconds=86400", "gym.mail.initial-delay-seconds=86400"
})
@AutoConfigureMockMvc
@Import(EmailJobsTests.TestBeans.class)
class EmailJobsTests {

    /** 22 October 2026, 10:00: after the 21st and after the reminder hour. */
    static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    static class Recorder implements EmailGateway {
        final List<String[]> sent = new CopyOnWriteArrayList<>(); // to, subject, body
        volatile boolean failNext;

        @Override
        public void send(String to, String subject, String body) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("mail server down");
            }
            sent.add(new String[] {to, subject, body});
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean @Primary Recorder recorder() { return new Recorder(); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Recorder mail;
    @Autowired NotificationPlanner planner;
    @Autowired OutboxSender sender;
    @Autowired AppUserRepository users;
    @Autowired PlanRepository plans;
    @Autowired GymClassRepository classes;
    @Autowired BookingRepository bookings;
    @Autowired MemberPreferenceRepository prefs;
    @Autowired EmailCrypto crypto;
    @Autowired com.foundry.gym.mail.MailSettings settings;
    @Autowired com.foundry.gym.mail.NotificationScheduler scheduler;
    @Autowired com.foundry.gym.mail.SmtpEmailGateway smtp;

    @BeforeEach
    void clean() {
        mail.sent.clear();
        mail.failNext = false;
        jdbc.update("delete from email_outbox");
        jdbc.update("delete from marketing_campaign");
        jdbc.update("delete from promo");
        jdbc.update("update member_preference set marketing = false, new_plans = false, plan_ending = false, visits_reminder = false");
        jdbc.update("update plan set created_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private MockHttpSession admin() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"boss@test.gym\",\"password\":\"secret-admin\",\"role\":\"ADMIN\"}")).andExpect(status().isOk());
        return s;
    }

    private AppUser member(String namePart, Plan plan, boolean marketing, boolean newPlans, boolean ending, boolean reminder) {
        String email = namePart + "-" + UUID.randomUUID() + "@test.gym";
        AppUser u = new AppUser(namePart, email, crypto.index(email), "x", Role.MEMBER);
        u.setPlan(plan);
        u = users.save(u);
        MemberPreference p = new MemberPreference(u.getId());
        p.setMarketing(marketing);
        p.setNewPlans(newPlans);
        p.setPlanEnding(ending);
        p.setVisitsReminder(reminder);
        prefs.save(p);
        return u;
    }

    private Plan plan(String name, int visits, boolean unlimited) {
        Plan p = new Plan(name, new BigDecimal("30.00"), unlimited ? 0 : visits);
        p.setUnlimited(unlimited);
        p.setCreatedAt(NOW.minusSeconds(3600));
        return plans.save(p);
    }

    private void book(AppUser u, GymClass c, int day) {
        bookings.save(new Booking(u, c, LocalDate.of(2026, 10, day)));
    }

    private GymClass gymClass() {
        return classes.save(new GymClass("Test class " + UUID.randomUUID().toString().substring(0, 6), "Coach", LocalTime.of(18, 0),
            60, 20, Set.of(DayOfWeek.MONDAY)));
    }

    private List<String> subjectsTo(AppUser u) {
        List<String> out = new ArrayList<>();
        for (String[] m : mail.sent) {
            if (m[0].equals(u.getEmail())) {
                out.add(m[1]);
            }
        }
        return out;
    }

    // ---- the home page tick ----------------------------------------------------------------------------------------

    @Test
    void a_home_page_item_ticked_as_marketing_is_emailed_once_to_members_who_want_marketing() throws Exception {
        AppUser wants = member("wants", null, true, false, false, false);
        AppUser declines = member("declines", null, false, true, true, true);
        MockHttpSession admin = admin();

        String created = mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Summer offer\",\"body\":\"Half price in July\",\"linkUrl\":\"https://gym.test/offer\","
                + "\"active\":true,\"marketing\":true}")).andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.marketing").value(true)).andExpect(jsonPath("$.marketingSentAt").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();

        assertThat(planner.queueMarketingItems()).isEqualTo(1);
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(subjectsTo(wants)).containsExactly("Summer offer");
        assertThat(subjectsTo(declines)).isEmpty();
        String text = mail.sent.get(0)[2];
        assertThat(text).contains("Half price in July").contains("https://gym.test/offer").contains("Marketing emails");

        // once only: a second run queues nothing, and the item shows when it went out and to how many
        assertThat(planner.queueMarketingItems()).isZero();
        mvc.perform(get("/api/admin/promos").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].marketingSentAt").exists()).andExpect(jsonPath("$[0].marketingRecipients").value(1));
        // and the tick can no longer be changed
        mvc.perform(put("/api/admin/promos/" + id).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Summer offer\",\"body\":\"Half price in July\",\"marketing\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.marketing").value(true));
    }

    @Test
    void an_item_with_nothing_to_say_cannot_be_ticked_and_an_inactive_one_waits() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PHOTO\",\"title\":\"Just a photo\",\"marketing\":true}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("To email an item it needs some text or a link."));

        AppUser wants = member("wants", null, true, false, false, false);
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Hidden\",\"body\":\"Not live yet\",\"active\":false,\"marketing\":true}"))
            .andExpect(status().is2xxSuccessful());
        assertThat(planner.queueMarketingItems()).isZero(); // inactive: not on the home page, so nothing to announce yet
        assertThat(subjectsTo(wants)).isEmpty();
    }

    // ---- the Marketing page ----------------------------------------------------------------------------------------

    @Test
    void the_marketing_page_writes_an_email_and_sends_it_to_everyone_who_has_marketing_on() throws Exception {
        AppUser a = member("a", null, true, false, false, false);
        AppUser b = member("b", null, true, false, false, false);
        AppUser c = member("c", null, false, true, true, true);
        MockHttpSession admin = admin();

        mvc.perform(get("/api/admin/marketing/status").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.mailEnabled").value(true)).andExpect(jsonPath("$.optedIn").value(2));
        mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"\",\"body\":\"x\"}")).andExpect(status().isBadRequest());

        String made = mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Open day\",\"body\":\"Come and try a class on Saturday.\",\"linkUrl\":\"https://gym.test/open\"}"))
            .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(made).get("id").asLong();
        mvc.perform(put("/api/admin/marketing/" + id).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Open day on Saturday\",\"body\":\"Come and try a class on Saturday.\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Open day on Saturday"));

        mvc.perform(post("/api/admin/marketing/" + id + "/send").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT")).andExpect(jsonPath("$.recipients").value(2))
            .andExpect(jsonPath("$.waiting").value(2));
        assertThat(sender.sendDue()).isEqualTo(2);
        assertThat(subjectsTo(a)).containsExactly("Open day on Saturday");
        assertThat(subjectsTo(b)).containsExactly("Open day on Saturday");
        assertThat(subjectsTo(c)).isEmpty();
        mvc.perform(get("/api/admin/marketing").session(admin)).andExpect(jsonPath("$[0].delivered").value(2))
            .andExpect(jsonPath("$[0].waiting").value(0));

        // a sent email is a record: it cannot be sent again, edited or deleted
        mvc.perform(post("/api/admin/marketing/" + id + "/send").session(admin)).andExpect(status().isConflict());
        mvc.perform(put("/api/admin/marketing/" + id).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"x\",\"body\":\"y\"}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/marketing/" + id).session(admin)).andExpect(status().isConflict());
    }

    @Test
    void sending_with_nobody_opted_in_is_refused_a_draft_can_be_deleted_and_members_cannot_use_the_page() throws Exception {
        MockHttpSession admin = admin();
        String made = mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Hello\",\"body\":\"Anyone there?\"}")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(made).get("id").asLong();
        mvc.perform(post("/api/admin/marketing/" + id + "/send").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/marketing/" + id).session(admin)).andExpect(status().isNoContent());

        // a member (registered through the API, so signed in) cannot use the page, and a visitor is asked to log in
        MockHttpSession memberSession = new MockHttpSession();
        mvc.perform(post("/api/auth/register").session(memberSession).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Plain Member\",\"email\":\"" + UUID.randomUUID() + "@test.gym\",\"password\":\"pass1234\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/marketing").session(memberSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/marketing")).andExpect(status().isUnauthorized());
    }

    @Test
    void a_member_who_switches_marketing_off_before_it_is_sent_does_not_get_it() throws Exception {
        AppUser a = member("a", null, true, false, false, false);
        MockHttpSession admin = admin();
        String made = mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Offer\",\"body\":\"Text\"}")).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/admin/marketing/" + json.readTree(made).get("id").asLong() + "/send").session(admin)).andExpect(status().isOk());

        MemberPreference p = prefs.findById(a.getId()).orElseThrow();
        p.setMarketing(false);
        prefs.save(p);

        assertThat(sender.sendDue()).isZero();
        assertThat(mail.sent).isEmpty();
        assertThat(jdbc.queryForObject("select status from email_outbox where user_id = ?", String.class, a.getId())).isEqualTo("SKIPPED");
    }

    // ---- new plans -------------------------------------------------------------------------------------------------

    @Test
    void members_who_asked_hear_about_a_new_plan_once_and_not_about_old_ones() throws Exception {
        plan("Old plan", 8, false);
        jdbc.update("update plan set created_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'"); // long ago
        AppUser wants = member("wants", null, false, true, false, false);
        AppUser declines = member("declines", null, true, false, true, true);
        plan("Fresh plan", 12, false);
        plan("Fresh unlimited", 0, true);

        assertThat(planner.queueNewPlans()).isEqualTo(2);
        assertThat(sender.sendDue()).isEqualTo(2);
        assertThat(subjectsTo(wants)).containsExactlyInAnyOrder("New plan at Foundry: Fresh plan", "New plan at Foundry: Fresh unlimited");
        assertThat(subjectsTo(declines)).isEmpty();
        assertThat(mail.sent.stream().map(m -> m[2]).toList()).anyMatch(t -> t.contains("12 visits per month"))
            .anyMatch(t -> t.contains("unlimited visits"));
        assertThat(planner.queueNewPlans()).isZero();
    }

    // ---- the two visit emails ----------------------------------------------------------------------------------------

    @Test
    void plan_almost_used_up_goes_out_when_exactly_two_visits_remain_once_a_month() throws Exception {
        Plan four = plan("Four", 4, false);
        GymClass c = gymClass();
        AppUser two = member("two", four, false, false, true, false);   // 2 of 4 used -> 2 remain
        AppUser three = member("three", four, false, false, true, false); // 3 of 4 used -> 1 remains
        AppUser none = member("none", four, false, false, true, false);   // 0 used -> 4 remain
        AppUser off = member("off", four, false, true, false, true);      // 2 remain but the toggle is off
        AppUser unlimited = member("unl", plan("Unl", 0, true), false, false, true, true);
        book(two, c, 1); book(two, c, 2);
        book(three, c, 1); book(three, c, 2); book(three, c, 3);
        book(off, c, 1); book(off, c, 2);
        // the previous month does not count
        bookings.save(new Booking(none, c, LocalDate.of(2026, 9, 28)));

        assertThat(planner.queuePlanEnding()).isEqualTo(1);
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(subjectsTo(two)).containsExactly("Only 2 visits left this month");
        assertThat(mail.sent.get(0)[2]).contains("only 2 of your 4 visits");
        assertThat(subjectsTo(three)).isEmpty();
        assertThat(subjectsTo(none)).isEmpty();
        assertThat(subjectsTo(off)).isEmpty();
        assertThat(subjectsTo(unlimited)).isEmpty();

        // booking one more takes them to 1 remaining: nothing new, and the earlier one is never repeated
        book(two, c, 3);
        assertThat(planner.queuePlanEnding()).isZero();
    }

    @Test
    void the_visits_reminder_goes_out_after_the_21st_to_members_with_visits_left_once_a_month() throws Exception {
        Plan four = plan("Four", 4, false);
        GymClass c = gymClass();
        AppUser left = member("left", four, false, false, false, true);   // 1 of 4 used -> 3 left
        AppUser usedUp = member("usedup", four, false, false, false, true);
        AppUser off = member("off", four, true, true, true, false);
        AppUser unlimited = member("unl", plan("Unl2", 0, true), false, false, false, true);
        book(left, c, 1);
        for (int d = 1; d <= 4; d++) {
            book(usedUp, c, d);
        }

        assertThat(planner.queueVisitsReminder()).isEqualTo(1);
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(subjectsTo(left)).containsExactly("You still have 3 visits left this month");
        assertThat(subjectsTo(usedUp)).isEmpty();
        assertThat(subjectsTo(off)).isEmpty();
        assertThat(subjectsTo(unlimited)).isEmpty();
        assertThat(planner.queueVisitsReminder()).isZero(); // already sent this month
    }

    // ---- live settings from the config server --------------------------------------------------------------------------

    @Test
    void switching_the_emails_off_on_the_config_server_stops_the_jobs_and_sending_without_a_restart() throws Exception {
        AppUser wants = member("wants", null, true, false, false, false);
        MockHttpSession admin = admin();
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Live offer\",\"body\":\"Text\",\"active\":true,\"marketing\":true}")).andExpect(status().is2xxSuccessful());
        String draft = mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Hello\",\"body\":\"Text\"}")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(draft).get("id").asLong();
        try {
            settings.apply(java.util.Map.of("gym.mail.enabled", "false"));
            mvc.perform(get("/api/admin/marketing/status").session(admin)).andExpect(jsonPath("$.mailEnabled").value(false));
            mvc.perform(post("/api/admin/marketing/" + id + "/send").session(admin)).andExpect(status().isConflict());
            scheduler.scan();
            scheduler.send();
            assertThat(jdbc.queryForObject("select count(*) from email_outbox", Integer.class)).isZero(); // nothing queued while off
            assertThat(mail.sent).isEmpty();
        } finally {
            settings.apply(java.util.Map.of("gym.mail.enabled", "true"));
        }
        scheduler.scan(); // switched on again: the waiting item is picked up
        scheduler.send();
        assertThat(subjectsTo(wants)).containsExactly("Live offer");
    }

    @Test
    void a_new_mail_server_user_password_and_sender_are_used_at_once_and_an_incomplete_change_is_refused() throws Exception {
        var before = settings.current();
        try {
            var mailSender = (org.springframework.mail.javamail.JavaMailSenderImpl) smtp.senderFor(settings.current());
            assertThat(mailSender.getHost()).isEqualTo("localhost");

            settings.apply(java.util.Map.of("spring.mail.host", "smtp.example.com", "spring.mail.port", "2525",
                "spring.mail.username", "mailer", "spring.mail.password", "s3cret", "gym.mail.from", "Gym <hello@example.com>",
                "spring.mail.properties.mail.smtp.starttls.enable", "true"));
            var after = settings.current();
            assertThat(after.host()).isEqualTo("smtp.example.com");
            assertThat(after.from()).isEqualTo("Gym <hello@example.com>");
            var rebuilt = (org.springframework.mail.javamail.JavaMailSenderImpl) smtp.senderFor(after);
            assertThat(rebuilt.getHost()).isEqualTo("smtp.example.com");
            assertThat(rebuilt.getPort()).isEqualTo(2525);
            assertThat(rebuilt.getUsername()).isEqualTo("mailer");
            assertThat(rebuilt.getPassword()).isEqualTo("s3cret");
            assertThat(rebuilt.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(after.toString()).doesNotContain("s3cret"); // the password is never printed
            assertThat(smtp.senderFor(after)).isSameAs(rebuilt); // unchanged settings: the same sender, not a new one each time

            // still enabled but the mail server removed: refused, the working settings stay
            settings.apply(java.util.Map.of("spring.mail.host", ""));
            assertThat(settings.current().host()).isEqualTo("smtp.example.com");
            // switched off, the server may be empty
            settings.apply(java.util.Map.of("spring.mail.host", "", "gym.mail.enabled", "false"));
            assertThat(settings.enabled()).isFalse();
        } finally {
            settings.apply(java.util.Map.of("gym.mail.enabled", "true", "spring.mail.host", before.host(),
                "spring.mail.port", String.valueOf(before.port()), "spring.mail.username", before.username(),
                "spring.mail.password", before.password(), "gym.mail.from", before.from()));
        }
        assertThat(settings.enabled()).isTrue();
    }

    // ---- sending: failures and retries ---------------------------------------------------------------------------------

    @Test
    void a_failed_send_is_kept_and_tried_again_later() throws Exception {
        AppUser a = member("a", null, true, false, false, false);
        MockHttpSession admin = admin();
        String made = mvc.perform(post("/api/admin/marketing").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"Retry me\",\"body\":\"Text\"}")).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/admin/marketing/" + json.readTree(made).get("id").asLong() + "/send").session(admin)).andExpect(status().isOk());

        mail.failNext = true;
        assertThat(sender.sendDue()).isZero();
        assertThat(jdbc.queryForObject("select status from email_outbox where user_id = ?", String.class, a.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from email_outbox where user_id = ?", Integer.class, a.getId())).isEqualTo(1);
        assertThat(sender.sendDue()).isZero(); // still paused: nothing is tried again straight away

        jdbc.update("update email_outbox set next_attempt_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'");
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(subjectsTo(a)).containsExactly("Retry me");
    }
}
