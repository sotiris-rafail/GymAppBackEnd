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
    private final com.foundry.gym.mail.MailSettings mail;

    public PreferenceService(MemberPreferenceRepository repo, com.foundry.gym.mail.MailSettings mail) {
        this.repo = repo;
        this.mail = mail;
    }

    @Transactional(readOnly = true)
    public PreferencesBody get(Long userId) {
        return repo.findById(userId).map(PreferenceService::view).orElse(new PreferencesBody(false, false, false, false));
    }

    @Transactional
    public PreferencesBody save(Long userId, PreferencesBody b) {
        MemberPreference p = repo.findById(userId).orElseGet(() -> new MemberPreference(userId));
        // While the emails are switched off (gym.mail.enabled on the config server) nothing can be turned on, as nothing
        // would be sent; turning an email off is always allowed.
        if (!mail.enabled() && ((b.marketing() && !p.isMarketing()) || (b.newPlans() && !p.isNewPlans())
            || (b.planEnding() && !p.isPlanEnding()) || (b.visitsReminder() && !p.isVisitsReminder()))) {
            throw com.foundry.gym.web.ApiException.conflict("Emails are switched off at the moment, so they cannot be turned on. You can still turn them off.");
        }
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
