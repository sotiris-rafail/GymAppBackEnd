package com.foundry.gym.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.foundry.gym.config.FeatureFlags;
import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Gym;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.domain.Promo;
import com.foundry.gym.domain.Role;
import com.foundry.gym.dto.Dtos.GymChoice;
import com.foundry.gym.dto.Dtos.GymRequest;
import com.foundry.gym.dto.Dtos.GymView;
import com.foundry.gym.dto.Dtos.OtherGym;
import com.foundry.gym.dto.Dtos.SwitchOffCheck;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.repo.BookingRepository;
import com.foundry.gym.repo.GymClassRepository;
import com.foundry.gym.repo.GymRepository;
import com.foundry.gym.repo.PlanRepository;
import com.foundry.gym.repo.PromoRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gyms, and the one rule everything else relies on: which gym does a request work on?
 *
 * <p>While {@code gyms.multi-enabled} is off in the configuration server the answer is always the default gym. While it
 * is on: the gym named in the request, else the member's home gym, else the default gym. Switching it off never deletes
 * anything; bookings at other gyms keep standing.
 */
@Service
public class GymService {

    private final GymRepository gyms;
    private final GymClassRepository classes;
    private final BookingRepository bookings;
    private final AppUserRepository users;
    private final PlanRepository plans;
    private final PromoRepository promos;
    private final FeatureFlags flags;
    private final Clock clock;

    public GymService(GymRepository gyms, GymClassRepository classes, BookingRepository bookings, AppUserRepository users,
                      PlanRepository plans, PromoRepository promos, FeatureFlags flags, Clock clock) {
        this.gyms = gyms;
        this.classes = classes;
        this.bookings = bookings;
        this.users = users;
        this.plans = plans;
        this.promos = promos;
        this.flags = flags;
        this.clock = clock;
    }

    /** Several gyms are in use (the switch in the configuration server). */
    public boolean multi() {
        return flags.multiGym();
    }

    // ---- resolving the gym of a request ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Gym defaultGym() {
        return gyms.findByDefaultGymTrue()
            .or(() -> gyms.findAllByOrderBySortOrderAscIdAsc().stream().findFirst())
            .orElseThrow(() -> new IllegalStateException("There is no gym. The database migration creates the first one."));
    }

    @Transactional(readOnly = true)
    public Gym find(Long id) {
        return gyms.findById(id).orElseThrow(() -> ApiException.notFound("Gym"));
    }

    /** The gym an admin page works on: the one chosen in the header, else the default gym. */
    @Transactional(readOnly = true)
    public Gym forAdmin(Long requested) {
        if (!multi() || requested == null) {
            return defaultGym();
        }
        Gym g = find(requested);
        if (g.getStatus() == Gym.Status.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "That gym is archived. Restore it on the Gyms page to work on it.");
        }
        return g;
    }

    /** Admin pages and member pages share endpoints, so the role decides how the gym is resolved. */
    @Transactional(readOnly = true)
    public Gym forPerson(Role role, Long userId, Long requested) {
        return role == Role.ADMIN ? forAdmin(requested) : forMember(requested, userId);
    }

    /** The gym a member sees: the one chosen, else their home gym, else the default gym. */
    @Transactional(readOnly = true)
    public Gym forMember(Long requested, Long userId) {
        if (!multi()) {
            return defaultGym();
        }
        if (requested != null) {
            Gym g = find(requested);
            if (g.getStatus() == Gym.Status.ARCHIVED) {
                throw ApiException.notFound("Gym");
            }
            return g;
        }
        Long home = userId == null ? null : users.findById(userId).map(AppUser::getHomeGymId).orElse(null);
        if (home != null) {
            Gym g = gyms.findById(home).orElse(null);
            if (g != null && g.getStatus() != Gym.Status.ARCHIVED) {
                return g;
            }
        }
        return defaultGym();
    }

    /** New bookings only at an active gym; while multi-gym is off only at the default gym. */
    @Transactional(readOnly = true)
    public void requireBookable(Long gymId) {
        Gym g = find(gymId);
        if (!multi()) {
            if (!g.isDefaultGym()) {
                throw ApiException.conflict("That class is not available at the moment.");
            }
            return;
        }
        if (g.getStatus() != Gym.Status.ACTIVE) {
            throw ApiException.conflict(g.getName() + " is not taking new bookings.");
        }
    }

    /** The gym's own time zone, else the application's. */
    @Transactional(readOnly = true)
    public ZoneId zoneOf(Long gymId) {
        Gym g = gyms.findById(gymId).orElse(null);
        if (g != null && g.getTimeZone() != null && !g.getTimeZone().isBlank()) {
            try {
                return ZoneId.of(g.getTimeZone().trim());
            } catch (Exception ignored) {
                // a bad zone was refused when it was saved; fall through to the application's zone
            }
        }
        return clock.getZone();
    }

    /** "Now" at that gym. */
    public Clock clockOf(Long gymId) {
        return clock.withZone(zoneOf(gymId));
    }

    /** Who an email aimed at one gym reaches: everyone while a single gym is in use or when no gym is named. */
    @Transactional(readOnly = true)
    public List<AppUser> audience(List<AppUser> members, Long gymId) {
        if (gymId == null || !multi()) {
            return members;
        }
        Long def = defaultGym().getId();
        return members.stream().filter(u -> gymId.equals(effectiveGymId(u, def))).toList();
    }

    /** The gym a member effectively belongs to: their home gym, else the default gym. */
    public static Long effectiveGymId(AppUser u, Long defaultGymId) {
        return u.getHomeGymId() == null ? defaultGymId : u.getHomeGymId();
    }

    @Transactional(readOnly = true)
    public List<Gym> all() {
        return gyms.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public java.util.Map<Long, String> names() {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        gyms.findAll().forEach(g -> out.put(g.getId(), g.getName()));
        return out;
    }

    // ---- what members can pick ------------------------------------------------------------------------------------

    /** ACTIVE and CLOSING gyms; only the default gym while multi-gym is off. */
    @Transactional(readOnly = true)
    public List<GymChoice> choices() {
        List<Gym> all = multi() ? gyms.findAllByOrderBySortOrderAscIdAsc().stream()
            .filter(g -> g.getStatus() != Gym.Status.ARCHIVED).toList() : List.of(defaultGym());
        return all.stream().map(g -> new GymChoice(g.getId(), g.getName(), g.getAddress() == null ? "" : g.getAddress(),
            g.getStatus().name(), g.isDefaultGym())).toList();
    }

    /** A member picks where they train; it is remembered as their home gym. */
    @Transactional
    public void setHomeGym(Long userId, Long gymId) {
        requireMulti();
        AppUser u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Member"));
        if (gymId != null) {
            Gym g = find(gymId);
            if (g.getStatus() == Gym.Status.ARCHIVED) {
                throw ApiException.notFound("Gym");
            }
        }
        u.setHomeGymId(gymId);
    }

    // ---- the admin's Gyms page ------------------------------------------------------------------------------------

    private void requireMulti() {
        if (!multi()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Several gyms are switched off at the moment.");
        }
    }

    @Transactional(readOnly = true)
    public List<GymView> list() {
        LocalDate today = LocalDate.now(clock);
        Long defaultId = defaultGym().getId();
        List<AppUser> members = users.findByRoleOrderByNameAsc(Role.MEMBER);
        List<GymView> out = new ArrayList<>();
        for (Gym g : gyms.findAllByOrderBySortOrderAscIdAsc()) {
            int n = (int) members.stream().filter(m -> Objects.equals(effectiveGymId(m, defaultId), g.getId())).count();
            out.add(new GymView(g.getId(), g.getName(), g.getAddress() == null ? "" : g.getAddress(),
                g.getTimeZone() == null ? "" : g.getTimeZone(), g.getStatus().name(), g.isDefaultGym(),
                classes.countByGymId(g.getId()), n, bookings.countToCome(g.getId(), today)));
        }
        return out;
    }

    private static String zoneOrNull(String tz) {
        if (tz == null || tz.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(tz.trim()).getId();
        } catch (Exception e) {
            throw ApiException.badRequest("That time zone is not known. Use a name like Europe/Athens.");
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Transactional
    public GymView create(GymRequest r) {
        requireMulti();
        if (gyms.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A gym with that name already exists.");
        }
        int order = gyms.findAll().stream().mapToInt(Gym::getSortOrder).max().orElse(0) + 1;
        Gym g = gyms.save(new Gym(r.name().trim(), clean(r.address()), zoneOrNull(r.timeZone()), order));
        return view(g);
    }

    @Transactional
    public GymView update(Long id, GymRequest r) {
        requireMulti();
        Gym g = find(id);
        String name = r.name().trim();
        if (!g.getName().equalsIgnoreCase(name) && gyms.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A gym with that name already exists.");
        }
        g.setName(name);
        g.setAddress(clean(r.address()));
        g.setTimeZone(zoneOrNull(r.timeZone()));
        return view(g);
    }

    /** Exactly one gym is the default. It must be active. */
    @Transactional
    public GymView makeDefault(Long id) {
        requireMulti();
        Gym g = find(id);
        if (g.getStatus() != Gym.Status.ACTIVE) {
            throw ApiException.conflict("Only an active gym can be the default gym.");
        }
        gyms.findAll().forEach(x -> x.setDefaultGym(x.getId().equals(g.getId())));
        return view(g);
    }

    /** ACTIVE -> CLOSING -> ARCHIVED, and back: CLOSING -> ACTIVE (reopen), ARCHIVED -> ACTIVE (restore). */
    @Transactional
    public GymView setStatus(Long id, String status) {
        requireMulti();
        Gym g = find(id);
        Gym.Status next;
        try {
            next = Gym.Status.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Status must be ACTIVE, CLOSING or ARCHIVED.");
        }
        if (next == g.getStatus()) {
            return view(g);
        }
        if (next != Gym.Status.ACTIVE && g.isDefaultGym()) {
            throw ApiException.conflict("The default gym cannot be closed or archived. Make another gym the default first.");
        }
        if (next == Gym.Status.CLOSING && g.getStatus() != Gym.Status.ACTIVE) {
            throw ApiException.conflict("Restore the gym first.");
        }
        if (next == Gym.Status.ARCHIVED) {
            if (g.getStatus() != Gym.Status.CLOSING) {
                throw ApiException.conflict("Stop new bookings first, then archive the gym when no bookings are left.");
            }
            long left = bookings.countToCome(g.getId(), LocalDate.now(clock));
            if (left > 0) {
                throw ApiException.conflict("That gym still has " + left + " booking" + (left == 1 ? "" : "s") + " to come.");
            }
        }
        g.setStatus(next);
        return view(g);
    }

    /** Members of this gym go back to the default gym (their home gym is cleared). */
    @Transactional
    public int moveMembersToDefault(Long id) {
        Gym g = find(id);
        if (g.isDefaultGym()) {
            throw ApiException.conflict("That is the default gym.");
        }
        List<AppUser> list = users.findByRoleAndHomeGymId(Role.MEMBER, g.getId());
        list.forEach(u -> u.setHomeGymId(null));
        return list.size();
    }

    /** Plans only valid at this gym become valid at all gyms. */
    @Transactional
    public int sharePlans(Long id) {
        find(id);
        List<Plan> list = plans.findAll().stream().filter(p -> Objects.equals(p.getGymId(), id)).toList();
        list.forEach(p -> p.setGymId(null));
        return list.size();
    }

    /** Advice before switching multi-gym off. */
    @Transactional(readOnly = true)
    public SwitchOffCheck switchOffCheck() {
        Gym def = defaultGym();
        LocalDate today = LocalDate.now(clock);
        List<AppUser> members = users.findByRoleOrderByNameAsc(Role.MEMBER);
        List<Plan> allPlans = plans.findAll();
        List<Promo> allPromos = promos.findAll();
        List<OtherGym> others = new ArrayList<>();
        long bookingsToCome = 0, membersElsewhere = 0, plansElsewhere = 0, itemsElsewhere = 0;
        boolean tidy = true;
        for (Gym g : gyms.findAllByOrderBySortOrderAscIdAsc()) {
            if (g.getId().equals(def.getId())) {
                continue;
            }
            long b = bookings.countToCome(g.getId(), today);
            int m = (int) members.stream().filter(x -> Objects.equals(x.getHomeGymId(), g.getId())).count();
            long p = allPlans.stream().filter(x -> Objects.equals(x.getGymId(), g.getId())).count();
            long i = allPromos.stream().filter(x -> Objects.equals(x.getGymId(), g.getId())).count();
            others.add(new OtherGym(g.getId(), g.getName(), g.getStatus().name(), classes.countByGymId(g.getId()), m, b, p, i));
            bookingsToCome += b;
            membersElsewhere += m;
            plansElsewhere += p;
            itemsElsewhere += i;
            if (g.getStatus() != Gym.Status.ARCHIVED || b > 0 || m > 0 || p > 0 || i > 0) {
                tidy = false;
            }
        }
        return new SwitchOffCheck(def.getId(), def.getName(), multi(), tidy, others, bookingsToCome, membersElsewhere,
            plansElsewhere, itemsElsewhere);
    }

    private GymView view(Gym g) {
        LocalDate today = LocalDate.now(clock);
        Long defaultId = defaultGym().getId();
        int n = (int) users.findByRoleOrderByNameAsc(Role.MEMBER).stream()
            .filter(m -> Objects.equals(effectiveGymId(m, defaultId), g.getId())).count();
        return new GymView(g.getId(), g.getName(), g.getAddress() == null ? "" : g.getAddress(),
            g.getTimeZone() == null ? "" : g.getTimeZone(), g.getStatus().name(), g.isDefaultGym(),
            classes.countByGymId(g.getId()), n, bookings.countToCome(g.getId(), today));
    }
}
