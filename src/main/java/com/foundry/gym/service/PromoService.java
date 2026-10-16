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

    public PromoService(PromoRepository promos, Clock clock) {
        this.promos = promos;
        this.clock = clock;
    }

    /** What visitors see: active items only, in the admin's order. */
    @Transactional(readOnly = true)
    public List<PromoView> visible() {
        LocalDate today = LocalDate.now(clock);
        return promos.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
            .filter(p -> p.getHideOn() == null || p.getHideOn().isAfter(today)) // hidden from its hide-on date on
            .map(PromoService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<PromoView> all() {
        return promos.findAllByOrderBySortOrderAscIdAsc().stream().map(PromoService::view).toList();
    }

    /** A tile exists to be clicked, so it must have a link. */
    private static void requireTileLink(PromoRequest r) {
        if ("TILE".equals(r.kind()) && clean(r.linkUrl()).isEmpty()) {
            throw ApiException.badRequest("A tile needs a link.");
        }
    }

    @Transactional
    public PromoView create(PromoRequest r) {
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
        return view(promos.save(p));
    }

    @Transactional
    public PromoView update(Long id, PromoRequest r) {
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
        if (!promos.existsById(id)) {
            throw ApiException.notFound("Item");
        }
        promos.deleteById(id);
    }

    private static PromoView view(Promo p) {
        return new PromoView(p.getId(), p.getKind(), p.getTitle(), p.getBody(), p.getImage(), p.getLinkUrl(),
            p.getSortOrder(), p.isActive(), p.isBordered(), p.isTitleVisible(), p.getContent() == null ? "" : p.getContent(),
            p.getHideOn() == null ? null : p.getHideOn().toString(), p.getColSpan(), p.getRowSpan(),
            p.getBgColor() == null ? "" : p.getBgColor(), p.getBgImage() == null ? "" : p.getBgImage());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
