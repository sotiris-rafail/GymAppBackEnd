package com.foundry.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:gymtest;DB_CLOSE_DELAY=-1",
    "gym.seed-demo-data=false",
    "config.client.enabled=false",
    "gym.admin.email=boss@test.gym",
    "gym.admin.password=secret-admin"
})
@AutoConfigureMockMvc
class GymApiTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private MockHttpSession login(String email, String password, String role) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MockHttpSession registerMember() throws Exception {
        String email = UUID.randomUUID() + "@test.gym";
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Test Member\",\"email\":\"" + email + "\",\"password\":\"pass1234\"}"))
            .andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private long create(MockHttpSession admin, String path, String body) throws Exception {
        String res = mvc.perform(post(path).session(admin).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    /** Plans are assigned by the admin only. */
    private void choosePlan(MockHttpSession member, long planId) throws Exception {
        long id = json.readTree(mvc.perform(get("/api/auth/me").session(member))
            .andReturn().getResponse().getContentAsString()).get("id").asLong();
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":" + planId + "}")).andExpect(status().isOk());
    }

    private String book(MockHttpSession member, long classId, LocalDate date, int expected) throws Exception {
        return mvc.perform(post("/api/bookings").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"classId\":" + classId + ",\"date\":\"" + date + "\"}"))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private static final String EVERY_DAY = "[1,2,3,4,5,6,7]";

    /** Sundays are always closed, so tests that book use the next day that is not a Sunday. */
    private static LocalDate nextOpen(LocalDate d) {
        return d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? d.plusDays(1) : d;
    }

    @Test
    void logins_are_separate_and_protected() throws Exception {
        mvc.perform(get("/api/plans")).andExpect(status().isUnauthorized());

        // Admin credentials do not work on the member login and the other way round.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"boss@test.gym\",\"password\":\"secret-admin\",\"role\":\"MEMBER\"}"))
            .andExpect(status().isUnauthorized());

        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        mvc.perform(get("/api/admin/members").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/me/bookings").session(admin)).andExpect(status().isForbidden());

        MockHttpSession member = registerMember();
        mvc.perform(get("/api/admin/members").session(member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/plans").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Hack\",\"price\":1,\"visitsPerMonth\":1}")).andExpect(status().isForbidden());
    }

    @Test
    void the_separate_ui_may_call_the_api_with_cookies() throws Exception {
        // Browser preflight from the UI's address is accepted and allows credentials.
        mvc.perform(options("/api/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        // Some other site is not allowed.
        mvc.perform(options("/api/auth/login")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
    }

    @Test
    void admin_input_is_validated() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        mvc.perform(post("/api/admin/classes").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Zero\",\"startTime\":\"10:00\",\"durationMinutes\":45,\"capacity\":0,\"days\":[1]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Max people must be at least 1."));
    }

    @Test
    void home_page_items_are_public_to_read_and_admin_only_to_change() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long shown = create(admin, "/api/admin/promos",
            "{\"kind\":\"PROMO\",\"title\":\"Summer offer\",\"body\":\"First month half price\",\"image\":\"\",\"linkUrl\":\"https://example.com/offer\",\"sortOrder\":0,\"active\":true}");
        create(admin, "/api/admin/promos",
            "{\"kind\":\"INSTAGRAM\",\"title\":\"Hidden post\",\"sortOrder\":1,\"active\":false}");

        // Visitors who are not logged in see active items only.
        mvc.perform(get("/api/promos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id==" + shown + ")].title").value("Summer offer"))
            .andExpect(jsonPath("$[?(@.title=='Hidden post')]").isEmpty());

        // Changing them needs the admin; the admin list also has hidden items.
        mvc.perform(post("/api/admin/promos").contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/promos").session(registerMember())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/promos").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title=='Hidden post')]").isNotEmpty());

        // Links must be web addresses.
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Bad\",\"linkUrl\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/admin/promos/" + shown).session(admin)).andExpect(status().isNoContent());
    }

    @Test
    void a_signed_in_person_may_change_only_their_password_and_must_know_the_current_one() throws Exception {
        MockHttpSession member = registerMember(); // registerMember() uses the password pass1234
        String email = json.readTree(mvc.perform(get("/api/auth/me").session(member)).andReturn().getResponse().getContentAsString())
            .get("email").asText();

        mvc.perform(put("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"x\",\"newPassword\":\"abcdef\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/password").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong-one\",\"newPassword\":\"abcdef\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Your current password is not correct."));
        mvc.perform(put("/api/auth/password").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"pass1234\",\"newPassword\":\"abc\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Password must be 6 to 72 characters."));
        mvc.perform(put("/api/auth/password").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"pass1234\",\"newPassword\":\"pass1234\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(put("/api/auth/password").session(member).contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"pass1234\",\"newPassword\":\"brand-new-pass\"}"))
            .andExpect(status().isNoContent());

        // the new password logs in, the old one no longer does
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"brand-new-pass\",\"role\":\"MEMBER\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"pass1234\",\"role\":\"MEMBER\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void class_capacity_is_enforced() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"Big\",\"price\":10,\"visitsPerMonth\":50}");
        long cls = create(admin, "/api/admin/classes",
            "{\"name\":\"Tiny\",\"coach\":\"X\",\"startTime\":\"23:30\",\"durationMinutes\":30,\"capacity\":1,\"days\":" + EVERY_DAY + "}");
        LocalDate day = nextOpen(LocalDate.now().plusDays(1));

        MockHttpSession a = registerMember();
        MockHttpSession b = registerMember();
        choosePlan(a, plan);
        choosePlan(b, plan);

        book(a, cls, day, 201);
        String msg = book(b, cls, day, 409);
        org.junit.jupiter.api.Assertions.assertTrue(msg.contains("full"), msg);

        // The session shows as full to the second member and as theirs to the first.
        JsonNode sessions = json.readTree(mvc.perform(get("/api/sessions").session(b)
            .param("from", day.toString()).param("to", day.toString()))
            .andReturn().getResponse().getContentAsString());
        JsonNode s = null;
        for (JsonNode n : sessions) {
            if (n.get("classId").asLong() == cls) s = n;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(s);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("booked").asInt());
        org.junit.jupiter.api.Assertions.assertFalse(s.get("mine").asBoolean());

        // Raising the limit makes room for the second member.
        mvc.perform(put("/api/admin/classes/" + cls).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Tiny\",\"coach\":\"X\",\"startTime\":\"23:30\",\"durationMinutes\":30,\"capacity\":2,\"days\":" + EVERY_DAY + "}"))
            .andExpect(status().isOk());
        book(b, cls, day, 201);
    }

    @Test
    void monthly_visit_limit_is_enforced_and_cancelling_gives_the_visit_back() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"One\",\"price\":5,\"visitsPerMonth\":1}");
        long cls = create(admin, "/api/admin/classes",
            "{\"name\":\"Daily\",\"coach\":\"\",\"startTime\":\"23:45\",\"durationMinutes\":30,\"capacity\":10,\"days\":" + EVERY_DAY + "}");
        LocalDate day = nextOpen(LocalDate.now().plusDays(1));
        // The day after, when it falls in the same month, so the one-visit limit applies to both.
        LocalDate next = nextOpen(day.plusDays(1));

        MockHttpSession m = registerMember();
        book(m, cls, day, 409); // no plan yet
        choosePlan(m, plan);
        String first = book(m, cls, day, 201);
        long bookingId = json.readTree(first).get("id").asLong();

        if (next.getMonth() == day.getMonth()) {
            String msg = book(m, cls, next, 409);
            org.junit.jupiter.api.Assertions.assertTrue(msg.contains("No visits left"), msg);
        }
        book(m, cls, day, 409); // already booked

        mvc.perform(delete("/api/bookings/" + bookingId).session(m)).andExpect(status().isNoContent());
        book(m, cls, day, 201); // visit is back
    }

    @Test
    void only_the_admin_assigns_plans_and_a_member_holds_one() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long a = create(admin, "/api/admin/plans", "{\"name\":\"A\",\"price\":5,\"visitsPerMonth\":5}");
        long b = create(admin, "/api/admin/plans", "{\"name\":\"B\",\"price\":9,\"visitsPerMonth\":9}");
        MockHttpSession m = registerMember();
        long id = json.readTree(mvc.perform(get("/api/auth/me").session(m)).andReturn().getResponse().getContentAsString())
            .get("id").asLong();

        // The member has no way to change the plan: the old endpoints are gone and the admin one is closed to members.
        mvc.perform(put("/api/me/plan").session(m).contentType(MediaType.APPLICATION_JSON).content("{\"planId\":" + a + "}"))
            .andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/me/plan").session(m)).andExpect(status().is4xxClientError());
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(m).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":" + a + "}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(m)).andExpect(jsonPath("$.planId").doesNotExist());

        // The admin assigns, replaces and removes. Always one plan at most.
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":" + a + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.planId").value(a));
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":" + b + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.planName").value("B"));
        mvc.perform(get("/api/auth/me").session(m)).andExpect(jsonPath("$.planId").value(b));
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":null}")).andExpect(status().isOk()).andExpect(jsonPath("$.planId").doesNotExist());

        // Unknown plan or member, and the admin account is not a member.
        mvc.perform(put("/api/admin/members/" + id + "/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":999999}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/members/999999/plan").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"planId\":" + a + "}")).andExpect(status().isNotFound());
    }

    @Test
    void plan_history_and_monthly_visits_are_listed_for_the_admin() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long a = create(admin, "/api/admin/plans", "{\"name\":\"HistA\",\"price\":5,\"visitsPerMonth\":3}");
        long b = create(admin, "/api/admin/plans", "{\"name\":\"HistB\",\"price\":9,\"visitsPerMonth\":9}");
        long cls = create(admin, "/api/admin/classes",
            "{\"name\":\"H\",\"coach\":\"\",\"startTime\":\"23:55\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession m = registerMember();
        long id = json.readTree(mvc.perform(get("/api/auth/me").session(m)).andReturn().getResponse().getContentAsString())
            .get("id").asLong();

        // Nothing yet: no plans, and only the current month with 0 visits.
        mvc.perform(get("/api/admin/members/" + id + "/history").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.plans.length()").value(0))
            .andExpect(jsonPath("$.months[0].month").value(java.time.YearMonth.now().toString()))
            .andExpect(jsonPath("$.months[0].visits").value(0));

        choosePlan(m, a);
        book(m, cls, nextOpen(LocalDate.now().plusDays(1)), 201);
        choosePlan(m, a); // same plan again: no new period
        choosePlan(m, b);

        JsonNode h = json.readTree(mvc.perform(get("/api/admin/members/" + id + "/history").session(admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(2, h.get("plans").size());
        org.junit.jupiter.api.Assertions.assertEquals("HistB", h.get("plans").get(0).get("planName").asText()); // newest first
        org.junit.jupiter.api.Assertions.assertTrue(h.get("plans").get(0).get("to").isNull());
        org.junit.jupiter.api.Assertions.assertEquals("HistA", h.get("plans").get(1).get("planName").asText());
        org.junit.jupiter.api.Assertions.assertFalse(h.get("plans").get(1).get("to").isNull());
        JsonNode month = null;
        for (JsonNode n : h.get("months")) {
            if (n.get("month").asText().equals(nextOpen(LocalDate.now().plusDays(1)).toString().substring(0, 7))) month = n;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(month);
        org.junit.jupiter.api.Assertions.assertEquals(1, month.get("visits").asInt());

        // Deleting the plan the member is on closes the open period and keeps the name in the history.
        mvc.perform(delete("/api/admin/plans/" + b).session(admin)).andExpect(status().isNoContent());
        h = json.readTree(mvc.perform(get("/api/admin/members/" + id + "/history").session(admin))
            .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("HistB", h.get("plans").get(0).get("planName").asText());
        org.junit.jupiter.api.Assertions.assertFalse(h.get("plans").get(0).get("to").isNull());

        // Members cannot read it, and an unknown member is a 404.
        mvc.perform(get("/api/admin/members/" + id + "/history").session(m)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/members/999999/history").session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void admin_sees_who_is_booked_into_each_class_this_month() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"Att\",\"price\":5,\"visitsPerMonth\":20}");
        long cls = create(admin, "/api/admin/classes",
            "{\"name\":\"AttendClass\",\"coach\":\"\",\"startTime\":\"23:58\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession a = registerMember();
        MockHttpSession b = registerMember();
        choosePlan(a, plan);
        choosePlan(b, plan);
        LocalDate day = nextOpen(LocalDate.now().plusDays(1));
        book(a, cls, day, 201);
        book(b, cls, day, 201);

        String month = day.toString().substring(0, 7);
        JsonNode list = json.readTree(mvc.perform(get("/api/admin/attendance").param("month", month).session(admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode n : list) {
            if (n.get("classId").asLong() == cls) mine = n;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(mine);
        org.junit.jupiter.api.Assertions.assertEquals(2, mine.get("totalBookings").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(day.toString(), mine.get("sessions").get(0).get("date").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2, mine.get("sessions").get(0).get("attendees").size());

        mvc.perform(get("/api/admin/attendance").param("month", "nope").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/attendance").session(a)).andExpect(status().isForbidden());
    }

    @Test
    void members_cannot_cancel_other_peoples_bookings() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"P\",\"price\":5,\"visitsPerMonth\":5}");
        long cls = create(admin, "/api/admin/classes",
            "{\"name\":\"Late\",\"coach\":\"\",\"startTime\":\"23:50\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession a = registerMember();
        MockHttpSession b = registerMember();
        choosePlan(a, plan);
        long id = json.readTree(book(a, cls, nextOpen(LocalDate.now().plusDays(1)), 201)).get("id").asLong();
        mvc.perform(delete("/api/bookings/" + id).session(b)).andExpect(status().isNotFound());
    }

    @Test
    void sundays_and_closed_days_cannot_be_booked_and_only_the_admin_manages_closed_days() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"Closed\",\"price\":5,\"visitsPerMonth\":20}");
        long cls = create(admin, "/api/admin/classes", "{\"name\":\"Daily\",\"coach\":\"X\",\"startTime\":\"23:30\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession m = registerMember();
        choosePlan(m, plan);

        LocalDate sunday = LocalDate.now().plusDays(1);
        while (sunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) sunday = sunday.plusDays(1);
        if (sunday.isAfter(LocalDate.now().plusDays(28))) return; // never happens, keeps the horizon rule out of this test
        String msg = book(m, cls, sunday, 409);
        org.junit.jupiter.api.Assertions.assertTrue(msg.contains("Sunday"), msg);
        org.junit.jupiter.api.Assertions.assertEquals(0, json.readTree(mvc.perform(get("/api/sessions").session(m)
            .param("from", sunday.toString()).param("to", sunday.toString())).andReturn().getResponse().getContentAsString()).size());
        mvc.perform(post("/api/admin/closed-days").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\":\"" + sunday + "\"}")).andExpect(status().isBadRequest());

        LocalDate day = nextOpen(LocalDate.now().plusDays(2));
        if (day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) day = day.plusDays(1);
        mvc.perform(post("/api/admin/closed-days").session(m).contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\":\"" + day + "\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/closed-days").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\":\"" + day + "\",\"reason\":\"Holiday\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/closed-days").session(m)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.date=='" + day + "')].reason").value("Holiday"));
        book(m, cls, day, 409);
        org.junit.jupiter.api.Assertions.assertEquals(0, json.readTree(mvc.perform(get("/api/sessions").session(m)
            .param("from", day.toString()).param("to", day.toString())).andReturn().getResponse().getContentAsString()).size());

        mvc.perform(delete("/api/admin/closed-days/" + day).session(admin)).andExpect(status().isNoContent());
        book(m, cls, day, 201);
    }

    @Test
    void announcement_cards_keep_their_html_text_and_are_public() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long id = create(admin, "/api/admin/promos",
            "{\"kind\":\"CARD\",\"title\":\"Summer news\",\"body\":\"Short\",\"content\":\"<p>Open <b>late</b></p>\"}");
        mvc.perform(get("/api/promos")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id==" + id + ")].kind").value("CARD"))
            .andExpect(jsonPath("$[?(@.id==" + id + ")].content").value("<p>Open <b>late</b></p>"));
        mvc.perform(put("/api/admin/promos/" + id).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"CARD\",\"title\":\"Summer news\",\"content\":\"<p>Changed</p>\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("<p>Changed</p>"));
    }

    @Test
    void email_preferences_default_to_off_and_are_saved_per_member() throws Exception {
        MockHttpSession m = registerMember();
        mvc.perform(get("/api/me/preferences").session(m)).andExpect(status().isOk())
            .andExpect(jsonPath("$.marketing").value(false)).andExpect(jsonPath("$.visitsReminder").value(false));
        mvc.perform(put("/api/me/preferences").session(m).contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketing\":true,\"newPlans\":false,\"planEnding\":true,\"visitsReminder\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planEnding").value(true));
        mvc.perform(get("/api/me/preferences").session(m)).andExpect(jsonPath("$.marketing").value(true))
            .andExpect(jsonPath("$.newPlans").value(false));
        mvc.perform(put("/api/me/preferences").session(m).contentType(MediaType.APPLICATION_JSON).content("{\"marketing\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/preferences").session(registerMember())).andExpect(jsonPath("$.marketing").value(false));
        mvc.perform(get("/api/me/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void home_items_with_a_hide_on_date_disappear_from_that_day_and_keep_their_size() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        LocalDate today = LocalDate.now();
        long later = create(admin, "/api/admin/promos", "{\"kind\":\"PROMO\",\"title\":\"Until next week\",\"hideOn\":\"" + today.plusDays(7) + "\",\"colSpan\":2,\"rowSpan\":3}");
        long gone = create(admin, "/api/admin/promos", "{\"kind\":\"PROMO\",\"title\":\"Ended today\",\"hideOn\":\"" + today + "\"}");
        mvc.perform(get("/api/promos")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id==" + later + ")].colSpan").value(2))
            .andExpect(jsonPath("$[?(@.id==" + later + ")].rowSpan").value(3))
            .andExpect(jsonPath("$[?(@.id==" + later + ")].hideOn").value(today.plusDays(7).toString()))
            .andExpect(jsonPath("$[?(@.id==" + gone + ")]").isEmpty());
        mvc.perform(get("/api/admin/promos").session(admin)).andExpect(jsonPath("$[?(@.id==" + gone + ")]").isNotEmpty());
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Too wide\",\"colSpan\":5}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/promos/" + gone).session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Ended today\",\"hideOn\":\"" + today.plusDays(1) + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/promos")).andExpect(jsonPath("$[?(@.id==" + gone + ")]").isNotEmpty());
    }

    @Test
    void home_items_can_have_a_background_colour_and_picture() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long id = create(admin, "/api/admin/promos", "{\"kind\":\"PROMO\",\"title\":\"Coloured\",\"bgColor\":\"#AA3355\",\"bgImage\":\"https://example.com/b.jpg\",\"rowSpan\":6}");
        mvc.perform(get("/api/promos")).andExpect(jsonPath("$[?(@.id==" + id + ")].bgColor").value("#aa3355"))
            .andExpect(jsonPath("$[?(@.id==" + id + ")].bgImage").value("https://example.com/b.jpg"))
            .andExpect(jsonPath("$[?(@.id==" + id + ")].rowSpan").value(6));
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Bad\",\"bgColor\":\"red\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/promos").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"PROMO\",\"title\":\"Bad\",\"bgImage\":\"javascript:alert(1)\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void admin_can_close_the_current_week_and_month_for_bookings() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"Period\",\"price\":5,\"visitsPerMonth\":50}");
        long cls = create(admin, "/api/admin/classes", "{\"name\":\"Daily\",\"coach\":\"X\",\"startTime\":\"23:30\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession m = registerMember();
        choosePlan(m, plan);
        LocalDate day = nextOpen(LocalDate.now().plusDays(1));
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        boolean inWeek = !day.isBefore(monday) && !day.isAfter(monday.plusDays(6));

        mvc.perform(put("/api/admin/closed-periods/MONTH").session(m).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/closed-periods/YEAR").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/closed-periods/WEEK").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("WEEK")).andExpect(jsonPath("$[0].from").value(monday.toString()));
        mvc.perform(put("/api/admin/closed-periods/WEEK").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)); // closing twice changes nothing
        if (inWeek) {
            book(m, cls, day, 409);
            mvc.perform(get("/api/closed-days").session(m)).andExpect(jsonPath("$[?(@.date=='" + day + "')].period").value("WEEK"));
        }
        mvc.perform(put("/api/admin/closed-periods/WEEK").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        book(m, cls, day, 201);

        LocalDate other = nextOpen(day.plusDays(1));
        mvc.perform(put("/api/admin/closed-periods/MONTH").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":true}")).andExpect(status().isOk());
        if (java.time.YearMonth.from(other).equals(java.time.YearMonth.now())) book(m, cls, other, 409);
        mvc.perform(put("/api/admin/closed-periods/MONTH").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"closed\":false}")).andExpect(status().isOk());
        book(m, cls, other, 201);
    }

    @Test
    void booking_window_limits_members_to_the_current_week_or_month() throws Exception {
        MockHttpSession admin = login("boss@test.gym", "secret-admin", "ADMIN");
        long plan = create(admin, "/api/admin/plans", "{\"name\":\"Window\",\"price\":5,\"visitsPerMonth\":50}");
        long cls = create(admin, "/api/admin/classes", "{\"name\":\"Daily\",\"coach\":\"X\",\"startTime\":\"23:30\",\"durationMinutes\":30,\"capacity\":5,\"days\":" + EVERY_DAY + "}");
        MockHttpSession m = registerMember();
        choosePlan(m, plan);
        mvc.perform(get("/api/booking-window").session(m)).andExpect(jsonPath("$.mode").value("ANY"));
        mvc.perform(put("/api/admin/booking-window").session(m).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"WEEK\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/booking-window").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"YEAR\"}"))
            .andExpect(status().isBadRequest());

        LocalDate today = LocalDate.now();
        LocalDate nextMonday = today.with(java.time.DayOfWeek.MONDAY).plusDays(7);
        mvc.perform(put("/api/admin/booking-window").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"WEEK\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("WEEK"));
        book(m, cls, nextMonday, 409);
        org.junit.jupiter.api.Assertions.assertEquals(0, json.readTree(mvc.perform(get("/api/sessions").session(m)
            .param("from", nextMonday.toString()).param("to", nextMonday.toString())).andReturn().getResponse().getContentAsString()).size());

        LocalDate firstOfNextMonth = today.withDayOfMonth(1).plusMonths(1);
        mvc.perform(put("/api/admin/booking-window").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MONTH\"}")).andExpect(status().isOk());
        LocalDate other = nextOpen(firstOfNextMonth);
        if (!other.isAfter(today.plusDays(28))) book(m, cls, other, 409);
        LocalDate inMonth = nextOpen(today.plusDays(1));
        if (java.time.YearMonth.from(inMonth).equals(java.time.YearMonth.from(today))) book(m, cls, inMonth, 201);

        mvc.perform(put("/api/admin/booking-window").session(admin).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ANY\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/booking-window").session(m)).andExpect(jsonPath("$.mode").value("ANY"));
    }
}
