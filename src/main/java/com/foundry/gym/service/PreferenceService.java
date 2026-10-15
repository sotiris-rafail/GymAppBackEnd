package com.foundry.gym.service;

import com.foundry.gym.domain.MemberPreference;
import com.foundry.gym.dto.Dtos.PreferencesBody;
import com.foundry.gym.repo.MemberPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A member's email preferences. This stores the choices; sending the emails is done by a separate job. */
@Service
public class PreferenceService {

    private final MemberPreferenceRepository repo;

    public PreferenceService(MemberPreferenceRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public PreferencesBody get(Long userId) {
        return repo.findById(userId).map(PreferenceService::view).orElse(new PreferencesBody(false, false, false, false));
    }

    @Transactional
    public PreferencesBody save(Long userId, PreferencesBody b) {
        MemberPreference p = repo.findById(userId).orElseGet(() -> new MemberPreference(userId));
        p.setMarketing(b.marketing());
        p.setNewPlans(b.newPlans());
        p.setPlanEnding(b.planEnding());
        p.setVisitsReminder(b.visitsReminder());
        return view(repo.save(p));
    }

    private static PreferencesBody view(MemberPreference p) {
        return new PreferencesBody(p.isMarketing(), p.isNewPlans(), p.isPlanEnding(), p.isVisitsReminder());
    }
}
