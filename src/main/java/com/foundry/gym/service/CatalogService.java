package com.foundry.gym.service;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.dto.Dtos.ClassRequest;
import com.foundry.gym.dto.Dtos.ClassView;
import com.foundry.gym.dto.Dtos.PlanRequest;
import com.foundry.gym.dto.Dtos.PlanView;
import com.foundry.gym.dto.Views;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.PlanHistoryRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans and classes: what the admin configures and what members choose from. */
@Service
public class CatalogService {

    private final PlanRepository plans;
    private final GymClassRepository classes;
    private final AppUserRepository users;
    private final BookingRepository bookings;
    private final PlanHistoryRepository history;
    private final java.time.Clock clock;

    public CatalogService(PlanRepository plans, GymClassRepository classes, AppUserRepository users,
                          BookingRepository bookings,
                          PlanHistoryRepository history, java.time.Clock clock) {
        this.history = history;
        this.clock = clock;
        this.plans = plans;
        this.classes = classes;
        this.users = users;
        this.bookings = bookings;
    }

    // ----- plans -----

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        return plans.findAll(Sort.by("id")).stream().map(Views::plan).toList();
    }

    @Transactional
    public PlanView createPlan(PlanRequest r) {
        Plan p = new Plan(r.name().trim(), r.price().setScale(2, java.math.RoundingMode.HALF_UP), r.visitsPerMonth());
        return Views.plan(plans.save(p));
    }

    @Transactional
    public PlanView updatePlan(Long id, PlanRequest r) {
        Plan p = plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan"));
        p.setName(r.name().trim());
        p.setPrice(r.price().setScale(2, java.math.RoundingMode.HALF_UP));
        p.setVisitsPerMonth(r.visitsPerMonth());
        return Views.plan(p);
    }

    /** Members on a deleted plan are left without a plan and need to pick another. */
    @Transactional
    public void deletePlan(Long id) {
        if (!plans.existsById(id)) {
            throw ApiException.notFound("Plan");
        }
        history.closeOpenForPlan(id, java.time.Instant.now(clock));
        users.clearPlan(id);
        plans.deleteById(id);
    }

    // ----- classes -----

    @Transactional(readOnly = true)
    public List<ClassView> classes() {
        return classes.findAll(Sort.by("id")).stream().map(Views::clazz).toList();
    }

    @Transactional
    public ClassView createClass(ClassRequest r) {
        GymClass c = new GymClass(r.name().trim(), clean(r.coach()), r.startTime().withSecond(0).withNano(0),
            r.durationMinutes(), r.capacity(), days(r.days()));
        return Views.clazz(classes.save(c));
    }

    @Transactional
    public ClassView updateClass(Long id, ClassRequest r) {
        GymClass c = classes.findById(id).orElseThrow(() -> ApiException.notFound("Class"));
        c.setName(r.name().trim());
        c.setCoach(clean(r.coach()));
        c.setStartTime(r.startTime().withSecond(0).withNano(0));
        c.setDurationMinutes(r.durationMinutes());
        c.setCapacity(r.capacity());
        c.setDays(days(r.days()));
        return Views.clazz(c);
    }

    /** Deleting a class also removes the bookings made for it. */
    @Transactional
    public void deleteClass(Long id) {
        if (!classes.existsById(id)) {
            throw ApiException.notFound("Class");
        }
        bookings.deleteAllForClass(id);
        classes.deleteById(id);
    }

    private static Set<DayOfWeek> days(Set<Integer> iso) {
        return iso.stream().map(DayOfWeek::of).collect(Collectors.toSet());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
