package com.foundry.gym.mail;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Role;
import com.foundry.gym.dto.Dtos.CampaignRequest;
import com.foundry.gym.dto.Dtos.CampaignView;
import com.foundry.gym.dto.Dtos.MarketingStatus;
import com.foundry.gym.repo.AppUserRepository;
import com.foundry.gym.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Marketing page: the admin writes an email and sends it to every member who turned marketing emails on.
 * "Send" does not talk to the mail server: it queues one email per member (see {@link EmailOutbox}); the sending job
 * delivers them within about a minute and re-checks each member's choice at that moment.
 */
@Service
public class MarketingService {

    private final MarketingCampaignRepository campaigns;
    private final EmailOutboxRepository outbox;
    private final AppUserRepository users;
    private final MailSettings settings;
    private final Clock clock;
    private final com.foundry.gym.service.GymService gyms;

    public MarketingService(MarketingCampaignRepository campaigns, EmailOutboxRepository outbox, AppUserRepository users,
                            MailSettings settings, Clock clock, com.foundry.gym.service.GymService gyms) {
        this.gyms = gyms;
        this.campaigns = campaigns;
        this.outbox = outbox;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MarketingStatus status(Long gymId) {
        return new MarketingStatus(settings.enabled(), gyms.audience(users.findOptedInMarketing(Role.MEMBER), gymId).size());
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        return campaigns.findAllByOrderByIdDesc().stream().map(this::view).toList();
    }

    @Transactional
    public CampaignView create(CampaignRequest r) {
        MarketingCampaign c = new MarketingCampaign(r.subject().trim(), r.body().trim(), clean(r.linkUrl()), Instant.now(clock));
        c.setGymId(gymOf(r, null));
        return view(campaigns.save(c));
    }

    @Transactional
    public CampaignView update(Long id, CampaignRequest r) {
        MarketingCampaign c = draft(id);
        c.edit(r.subject().trim(), r.body().trim(), clean(r.linkUrl()));
        c.setGymId(gymOf(r, c.getGymId()));
        return view(c);
    }

    @Transactional
    public void delete(Long id) {
        campaigns.delete(draft(id));
    }

    /** Queues the email for every member with marketing emails on. Only a draft can be sent, and only once. */
    @Transactional
    public CampaignView send(Long id) {
        if (!settings.enabled()) {
            throw ApiException.conflict("Email sending is switched off (gym.mail.enabled is false), so nothing can be sent.");
        }
        MarketingCampaign c = draft(id);
        Instant now = Instant.now(clock);
        int queued = 0;
        for (AppUser u : gyms.audience(users.findOptedInMarketing(Role.MEMBER), c.getGymId())) {
            String key = "campaign:" + c.getId();
            if (!outbox.existsByUserIdAndDedupeKey(u.getId(), key)) {
                outbox.save(new EmailOutbox(u.getId(), EmailKind.MARKETING, key, c.getSubject(),
                    EmailTexts.marketing(settings.current(), u.getName(), c.getBody(), c.getLinkUrl()), now));
                queued++;
            }
        }
        if (queued == 0) {
            throw ApiException.badRequest("No member has marketing emails switched on, so there is nobody to send this to.");
        }
        c.markSent(now, queued);
        return view(c);
    }

    /** The gym a campaign is aimed at: only while several gyms are in use; empty = every member. */
    private Long gymOf(CampaignRequest r, Long current) {
        if (!gyms.multi()) {
            return current;
        }
        if (r.gymId() != null) {
            gyms.find(r.gymId());
        }
        return r.gymId();
    }

    private MarketingCampaign draft(Long id) {
        MarketingCampaign c = campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Marketing email"));
        if (c.getStatus() != MarketingCampaign.Status.DRAFT) {
            throw ApiException.conflict("That email has already been sent and can no longer be changed.");
        }
        return c;
    }

    private CampaignView view(MarketingCampaign c) {
        String key = "campaign:" + c.getId();
        boolean sent = c.getStatus() == MarketingCampaign.Status.SENT;
        return new CampaignView(c.getId(), c.getSubject(), c.getBody(), c.getLinkUrl() == null ? "" : c.getLinkUrl(),
            c.getStatus().name(), c.getCreatedAt().toString(), c.getSentAt() == null ? null : c.getSentAt().toString(),
            c.getRecipients(),
            sent ? outbox.countByDedupeKeyAndStatus(key, EmailOutbox.Status.SENT) : 0,
            sent ? outbox.countByDedupeKeyAndStatus(key, EmailOutbox.Status.PENDING) : 0,
            sent ? outbox.countByDedupeKeyAndStatus(key, EmailOutbox.Status.FAILED) : 0,
            sent ? outbox.countByDedupeKeyAndStatus(key, EmailOutbox.Status.SKIPPED) : 0, c.getGymId());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
