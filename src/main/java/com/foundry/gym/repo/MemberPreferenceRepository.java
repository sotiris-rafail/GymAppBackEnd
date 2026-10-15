package com.foundry.gym.repo;

import com.foundry.gym.domain.MemberPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberPreferenceRepository extends JpaRepository<MemberPreference, Long> {
}
