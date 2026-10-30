package com.foundry.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Gym;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.Role;
import com.foundry.gym.dto.Dtos.GymRequest;
import com.foundry.gym.dto.Dtos.GymView;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.security.EmailCrypto;
import com.foundry.gym.service.BookingService;
import com.foundry.gym.service.CatalogService;
import com.foundry.gym.service.GymService;
import com.foundry.gym.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Several gyms: off by default, isolation per gym, the default gym, the life cycle and the safe switch-off. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:multigym;DB_CLOSE_DELAY=-1",
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
class MultiGymTests {

    @Autowired GymService gyms;
    @Autowired CatalogService catalog;
    @Autowired BookingService bookings;
    @Autowired AppUserRepository users;
    @Autowired PlanRepository plans;
    @Autowired GymClassRepository classes;
    @Autowired EmailCrypto crypto;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired com.foundry.gym.config.FeatureFlags flags;

    @AfterEach
    void reset() {
        flags.setMultiGym(false);
    }

    private AppUser member(String name, Plan plan) {
        String email = name.toLowerCase() + "@multigym.test";
        AppUser u = new AppUser(name, email, crypto.index(email), "x", Role.MEMBER);
        u.setPlan(plan);
        return users.save(u);
    }

    private static LocalDate nextOpenDay(LocalDate from) {
        LocalDate d = from.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Test
    void one_gym_is_the_default_and_nothing_changes_while_the_switch_is_off() throws Exception {
        assertFalse(gyms.multi());
        assertTrue(gyms.defaultGym().isDefaultGym());
        assertEquals(Gym.FIRST_ID, gyms.defaultGym().getId());
        assertTrue(gyms.choices().isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> gyms.create(new GymRequest("Nope", null, null))).status().value());
        // whatever gym is asked for, the single gym answers
        assertEquals(Gym.FIRST_ID, gyms.forAdmin(999L).getId());
        assertEquals(Gym.FIRST_ID, gyms.forMember(999L, null).getId());
    }

    @Test
    void two_gyms_keep_their_classes_and_plans_apart() {
        flags.setMultiGym(true);
        GymView north = gyms.create(new GymRequest("North", "1 Main St", "Europe/Athens"));
        Gym northGym = gyms.find(north.id());
        Gym main = gyms.defaultGym();
        assertEquals(409, assertThrows(ApiException.class, () -> gyms.create(new GymRequest("north", null, null))).status().value());

        Plan everywhere = plans.save(new Plan("MG-Everywhere", new BigDecimal("10.00"), 4));
        Plan northOnly = plans.save(new Plan("MG-NorthOnly", new BigDecimal("10.00"), 4));
        northOnly.setGymId(northGym.getId());
        plans.save(northOnly);

        GymClass atMain = classes.save(new GymClass("MG-Main", "X", LocalTime.of(18, 0), 60, 5, Set.of(DayOfWeek.values())));
        GymClass atNorth = new GymClass("MG-North", "X", LocalTime.of(18, 0), 60, 5, Set.of(DayOfWeek.values()));
        atNorth.setGymId(northGym.getId());
        atNorth = classes.save(atNorth);

        assertTrue(catalog.classes(main).stream().anyMatch(c -> c.name().equals("MG-Main")));
        assertFalse(catalog.classes(main).stream().anyMatch(c -> c.name().equals("MG-North")));
        assertTrue(catalog.classes(northGym).stream().anyMatch(c -> c.name().equals("MG-North")));
        assertTrue(catalog.plans(main, null).stream().noneMatch(p -> p.name().equals("MG-NorthOnly")));
        assertTrue(catalog.plans(northGym, null).stream().anyMatch(p -> p.name().equals("MG-NorthOnly")));
        assertTrue(catalog.plans(main, null).stream().anyMatch(p -> p.name().equals("MG-Everywhere")));
        // an admin editing one gym cannot touch another gym's class
        Long northClass = atNorth.getId();
        assertEquals(404, assertThrows(ApiException.class, () -> catalog.deleteClass(northClass, main)).status().value());

        // a plan made for North cannot be used at the main gym
        AppUser m = member("Nora", northOnly);
        LocalDate day = nextOpenDay(LocalDate.now(clock));
        assertEquals(409, assertThrows(ApiException.class, () -> bookings.book(m.getId(), atMain.getId(), day)).status().value());
        assertEquals(northGym.getName(), bookings.book(m.getId(), northClass, day).gymName());
        assertTrue(everywhere.getGymId() == null);
    }

    @Test
    void default_gym_rules_and_the_life_cycle() {
        flags.setMultiGym(true);
        GymView east = gyms.create(new GymRequest("East", null, null));
        Gym main = gyms.defaultGym();
        // the default gym cannot be closed
        assertEquals(409, assertThrows(ApiException.class, () -> gyms.setStatus(main.getId(), "CLOSING")).status().value());
        // ACTIVE cannot jump to ARCHIVED
        assertEquals(409, assertThrows(ApiException.class, () -> gyms.setStatus(east.id(), "ARCHIVED")).status().value());
        assertEquals("CLOSING", gyms.setStatus(east.id(), "CLOSING").status());
        // a closing gym cannot become the default and takes no new bookings
        assertEquals(409, assertThrows(ApiException.class, () -> gyms.makeDefault(east.id())).status().value());
        assertEquals(409, assertThrows(ApiException.class, () -> gyms.requireBookable(east.id())).status().value());
        assertEquals("ARCHIVED", gyms.setStatus(east.id(), "ARCHIVED").status());
        assertEquals("ACTIVE", gyms.setStatus(east.id(), "ACTIVE").status());
        // exactly one default
        assertTrue(gyms.makeDefault(east.id()).isDefault());
        assertEquals(1, gyms.list().stream().filter(GymView::isDefault).count());
        assertFalse(gyms.find(main.getId()).isDefaultGym());
        gyms.makeDefault(main.getId());
    }

    @Test
    void switching_off_hides_the_other_gyms_but_keeps_the_data_and_bookings() {
        flags.setMultiGym(true);
        Gym north = gyms.find(gyms.create(new GymRequest("Off-North", null, null)).id());
        GymClass atNorth = new GymClass("MG-Off", "X", LocalTime.of(18, 0), 60, 5, Set.of(DayOfWeek.values()));
        atNorth.setGymId(north.getId());
        atNorth = classes.save(atNorth);
        AppUser m = member("Omar", null);
        m.setHomeGymId(north.getId());
        users.save(m);

        var check = gyms.switchOffCheck();
        assertFalse(check.tidy());
        assertEquals(1, check.membersElsewhere());

        flags.setMultiGym(false);
        // nothing is deleted, the gym is just not shown any more
        assertTrue(classes.findById(atNorth.getId()).isPresent());
        assertEquals(Gym.FIRST_ID, gyms.forMember(null, m.getId()).getId());
        assertTrue(gyms.choices().isEmpty());
        assertTrue(catalog.classes(gyms.defaultGym()).stream().noneMatch(c -> c.name().equals("MG-Off")));
        // the check still works and the tidy-up actions still work
        assertEquals(1, gyms.switchOffCheck().membersElsewhere());
        assertEquals(1, gyms.moveMembersToDefault(north.getId()));
        assertNull(users.findById(m.getId()).orElseThrow().getHomeGymId());
        assertEquals(0, gyms.switchOffCheck().membersElsewhere());
    }

    @Test
    void the_switch_shows_in_features_and_guards_the_admin_endpoints() throws Exception {
        MockHttpSession admin = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"boss@test.gym\",\"password\":\"secret-admin\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/features").session(admin)).andExpect(jsonPath("$.multiGym").value(false));
        mvc.perform(post("/api/admin/gyms").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Closed\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/gyms").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/gyms/switch-off-check").session(admin)).andExpect(status().isOk());

        flags.setMultiGym(true);
        mvc.perform(get("/api/features").session(admin)).andExpect(jsonPath("$.multiGym").value(true));
        mvc.perform(post("/api/admin/gyms").session(admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Api-Gym\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/analytics").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.multiGym").value(true));
    }
}
