package com.foundry.gym.repo;

import java.util.List;

import com.foundry.gym.domain.Promo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromoRepository extends JpaRepository<Promo, Long> {

    /** Items the admin ticked for a marketing email that has not been queued yet. */
    List<Promo> findByMarketingTrueAndMarketingQueuedAtIsNullAndActiveTrue();

    List<Promo> findByActiveTrueOrderBySortOrderAscIdAsc();

    List<Promo> findAllByOrderBySortOrderAscIdAsc();
}
