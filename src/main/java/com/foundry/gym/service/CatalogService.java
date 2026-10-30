package com.foundry.gym.service;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.foundry.gym.domain.Gym;
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
    private final com.foundry.gym.config.FeatureFlags flags;
    private final GymService gyms;

    public CatalogService(PlanRepository plans, GymClassRepository classes, AppUserRepository users,
                          BookingRepository bookings,
                          PlanHistoryRepository history, java.time.Clock clock,
                          com.foundry.gym.config.FeatureFlags flags, GymService gyms) {
        this.gyms = gyms;
        this.history = history;
        this.clock = clock;
        this.flags = flags;
        this.plans = plans;
        this.classes = classes;
        this.users = users;
        this.bookings = bookings;
    }

    // ----- plans -----

    /**
     * The plans of one gym: those made for it and those valid at every gym. A member also gets the plan they hold, so it
     * is never missing from their page. While several gyms are switched off every plan is listed: members keep the plan
     * they have, whichever gym it was made for.
     */
    @Transactional(readOnly = true)
    public List<PlanView> plans(Gym gym, Long memberId) {
        List<Plan> all = plans.findAll(Sort.by("id"));
        if (!gyms.multi()) {
            return all.stream().map(Views::plan).toList();
        }
        Long own = memberId == null ? null : users.findById(memberId).map(u -> u.getPlan() == null ? null : u.getPlan().getId()).orElse(null);
        return all.stream()
            .filter(p -> p.getGymId() == null || p.getGymId().equals(gym.getId()) || p.getId().equals(own))
            .map(Views::plan).toList();
    }

    /** A plan's gym: only while several gyms are in use; empty = valid at all gyms. */
    private Long planGym(PlanRequest r, Long current) {
        if (!gyms.multi()) {
            return current;
        }
        if (r.gymId() != null) {
            gyms.find(r.gymId());
        }
        return r.gymId();
    }

    /**
     * While unlimited plans are turned off in the config server, a plan cannot newly become unlimited. A plan that already
     * is unlimited can still be edited, and can be switched to a limited one.
     */
    private void requireUnlimitedAllowed(PlanRequest r, boolean alreadyUnlimited) {
        if (Boolean.TRUE.equals(r.unlimited()) && !alreadyUnlimited && !flags.unlimitedPlans()) {
            throw ApiException.badRequest("Unlimited plans are turned off.");
        }
    }

    /** The visits to store: 0 for an unlimited plan, otherwise the number given (required). */
    private static int visitsOf(PlanRequest r) {
        if (Boolean.TRUE.equals(r.unlimited())) {
            return 0;
        }
        if (r.visitsPerMonth() == null) {
            throw ApiException.badRequest("Visits per month is required.");
        }
        return r.visitsPerMonth();
    }

    @Transactional
    public PlanView createPlan(PlanRequest r) {
        requireUnlimitedAllowed(r, false);
        Plan p = new Plan(r.name().trim(), r.price().setScale(2, java.math.RoundingMode.HALF_UP), visitsOf(r));
        p.setUnlimited(Boolean.TRUE.equals(r.unlimited()));
        p.setGymId(planGym(r, null));
        return Views.plan(plans.save(p));
    }

    @Transactional
    public PlanView updatePlan(Long id, PlanRequest r) {
        Plan p = plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan"));
        requireUnlimitedAllowed(r, p.isUnlimited());
        p.setName(r.name().trim());
        p.setPrice(r.price().setScale(2, java.math.RoundingMode.HALF_UP));
        p.setVisitsPerMonth(visitsOf(r));
        p.setUnlimited(Boolean.TRUE.equals(r.unlimited()));
        p.setGymId(planGym(r, p.getGymId()));
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
    public List<ClassView> classes(Gym gym) {
        return classes.findByGymId(gym.getId(), Sort.by("id")).stream().map(Views::clazz).toList();
    }

    @Transactional
    public ClassView createClass(ClassRequest r, Gym gym) {
        GymClass c = new GymClass(r.name().trim(), clean(r.coach()), r.startTime().withSecond(0).withNano(0),
            r.durationMinutes(), r.capacity(), days(r.days()));
        c.setGymId(gym.getId());
        return Views.clazz(classes.save(c));
    }

    @Transactional
    public ClassView updateClass(Long id, ClassRequest r, Gym gym) {
        GymClass c = classes.findById(id).filter(x -> gym.getId().equals(x.getGymId())).orElseThrow(() -> ApiException.notFound("Class"));
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
    public void deleteClass(Long id, Gym gym) {
        if (classes.findById(id).filter(x -> gym.getId().equals(x.getGymId())).isEmpty()) {
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
