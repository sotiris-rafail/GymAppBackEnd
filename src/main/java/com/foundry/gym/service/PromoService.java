package com.foundry.gym.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.foundry.gym.domain.Promo;
import com.foundry.gym.dto.Dtos.PromoRequest;
import com.foundry.gym.dto.Dtos.PromoView;
import com.foundry.gym.repo.PromoRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The items shown on the public home page, managed by the admin. */
@Service
public class PromoService {

    private final PromoRepository promos;

    private final Clock clock;

    private final com.foundry.gym.config.FeatureFlags flags;

    private final GymService gyms;

    public PromoService(PromoRepository promos, Clock clock, com.foundry.gym.config.FeatureFlags flags, GymService gyms) {
        this.gyms = gyms;
        this.flags = flags;
        this.promos = promos;
        this.clock = clock;
    }

    /** The admin's side of the home page is closed while it is switched off in the config server. */
    private void requireOn() {
        if (!flags.home()) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "The home page is switched off.");
        }
    }

    /** What visitors see: active items only, in the admin's order. Nothing while the home page is switched off. */
    @Transactional(readOnly = true)
    public List<PromoView> visible(com.foundry.gym.domain.Gym gym) {
        if (!flags.home()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        return promos.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
            .filter(p -> p.getHideOn() == null || p.getHideOn().isAfter(today)) // hidden from its hide-on date on
            .filter(p -> !gyms.multi() || p.getGymId() == null || p.getGymId().equals(gym.getId()))
            .map(PromoService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<PromoView> all(com.foundry.gym.domain.Gym gym) {
        requireOn();
        return promos.findAllByOrderBySortOrderAscIdAsc().stream()
            .filter(p -> !gyms.multi() || p.getGymId() == null || p.getGymId().equals(gym.getId()))
            .map(PromoService::view).toList();
    }

    /** A tile exists to be clicked, so it must have a link. */
    private static void requireTileLink(PromoRequest r) {
        if (Boolean.TRUE.equals(r.marketing()) && clean(r.body()).isEmpty() && clean(r.linkUrl()).isEmpty()) {
            throw ApiException.badRequest("To email an item it needs some text or a link.");
        }
        if ("TILE".equals(r.kind()) && clean(r.linkUrl()).isEmpty()) {
            throw ApiException.badRequest("A tile needs a link.");
        }
    }

    @Transactional
    public PromoView create(PromoRequest r) {
        requireOn();
        requireTileLink(r);
        Promo p = new Promo(r.kind(), r.title().trim(), clean(r.body()), clean(r.image()), clean(r.linkUrl()),
            r.sortOrder() == null ? 0 : r.sortOrder(), r.active() == null || r.active());
        p.setBordered(r.bordered() == null || r.bordered());
        p.setTitleVisible(r.titleVisible() == null || r.titleVisible());
        p.setContent(clean(r.content()));
        p.setHideOn(r.hideOn());
        p.setColSpan(r.colSpan() == null ? 0 : r.colSpan());
        p.setRowSpan(r.rowSpan() == null ? 0 : r.rowSpan());
        p.setBgColor(clean(r.bgColor()).toLowerCase());
        p.setBgImage(clean(r.bgImage()));
        p.setMarketing(Boolean.TRUE.equals(r.marketing()));
        p.setGymId(gymOf(r, null));
        return view(promos.save(p));
    }

    @Transactional
    public PromoView update(Long id, PromoRequest r) {
        requireOn();
        requireTileLink(r);
        Promo p = promos.findById(id).orElseThrow(() -> ApiException.notFound("Item"));
        p.setKind(r.kind());
        p.setTitle(r.title().trim());
        p.setBody(clean(r.body()));
        p.setImage(clean(r.image()));
        p.setLinkUrl(clean(r.linkUrl()));
        p.setContent(clean(r.content()));
        p.setHideOn(r.hideOn());
        p.setColSpan(r.colSpan() == null ? 0 : r.colSpan());
        p.setRowSpan(r.rowSpan() == null ? 0 : r.rowSpan());
        p.setBgColor(clean(r.bgColor()).toLowerCase());
        p.setBgImage(clean(r.bgImage()));
        if (r.sortOrder() != null) {
            p.setSortOrder(r.sortOrder());
        }
        if (r.marketing() != null && p.getMarketingQueuedAt() == null) { // once emailed, the tick stays as it was
            p.setMarketing(r.marketing());
        }
        p.setGymId(gymOf(r, p.getGymId()));
        if (r.active() != null) {
            p.setActive(r.active());
        }
        if (r.bordered() != null) {
            p.setBordered(r.bordered());
        }
        if (r.titleVisible() != null) {
            p.setTitleVisible(r.titleVisible());
        }
        return view(p);
    }

    @Transactional
    public void delete(Long id) {
        requireOn();
        if (!promos.existsById(id)) {
            throw ApiException.notFound("Item");
        }
        promos.deleteById(id);
    }

    /** An item's gym: only while several gyms are in use; empty = shown at every gym. */
    private Long gymOf(PromoRequest r, Long current) {
        if (!gyms.multi()) {
            return current;
        }
        if (r.gymId() != null) {
            gyms.find(r.gymId());
        }
        return r.gymId();
    }

    private static PromoView view(Promo p) {
        return new PromoView(p.getId(), p.getKind(), p.getTitle(), p.getBody(), p.getImage(), p.getLinkUrl(),
            p.getSortOrder(), p.isActive(), p.isBordered(), p.isTitleVisible(), p.getContent() == null ? "" : p.getContent(),
            p.getHideOn() == null ? null : p.getHideOn().toString(), p.getColSpan(), p.getRowSpan(),
            p.getBgColor() == null ? "" : p.getBgColor(), p.getBgImage() == null ? "" : p.getBgImage(),
            p.isMarketing(), p.getMarketingQueuedAt() == null ? null : p.getMarketingQueuedAt().toString(), p.getMarketingRecipients(),
            p.getGymId());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
