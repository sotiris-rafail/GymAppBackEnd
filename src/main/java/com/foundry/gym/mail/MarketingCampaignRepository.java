package com.foundry.gym.mail;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, Long> {

    /** Newest first. */
    List<MarketingCampaign> findAllByOrderByIdDesc();
}
