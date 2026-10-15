package com.foundry.gym.repo;

import java.util.List;

import com.foundry.gym.domain.Promo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromoRepository extends JpaRepository<Promo, Long> {

    List<Promo> findByActiveTrueOrderBySortOrderAscIdAsc();

    List<Promo> findAllByOrderBySortOrderAscIdAsc();
}
