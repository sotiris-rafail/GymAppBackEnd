# Running more than one gym: analysis and plan

Status: agreed design, being implemented. Decisions marked **(decided)** follow the recommendations in the design page
and were accepted by the owner.

## 1. The idea in one paragraph

One switch in the configuration server, `gyms.multi-enabled`, turns multi-gym on. With it **off** (the default) Foundry
behaves exactly as it does today, on a single gym called the **default gym**. With it **on**, the admin can add more
gyms, and every class, session, closed day, plan, home page item and marketing email belongs to a gym (or, for plans,
home page items and marketing emails, optionally to all gyms). Turning the switch off again never deletes anything:
the other gyms are hidden, their bookings still stand, and everything falls back to the default gym.

## 2. Requirements

1. The owner can run more than one gym from the same installation.
2. It is controlled from the configuration server **live** (no restart, no reload), for both backend and UI.
3. The default is one gym, as it is today. An upgrade changes nothing visible.
4. If the feature is switched on once and then off, the system must stay consistent. A **default gym** is chosen by
   the admin and is the fallback.

## 3. How a request is resolved to one gym

```
request ──► gyms.multi-enabled ?
              ├─ off ─► default gym (any gym in the request is ignored)
              └─ on  ─► 1. the gym named in the request (?gymId=)
                         2. the member's home gym
                         3. the default gym
                         (the gym must be ACTIVE; CLOSING is readable and keeps its existing bookings)
            ──► read that gym only: classes, sessions, closed days, plans, home page items
```

Requests that name no gym keep working as they do now, so the existing UI and tests pass with the switch off.

## 4. Data model

New table `gym`: `id`, `name`, `address`, `time_zone` (empty = the application's zone), `status`
(`ACTIVE` | `CLOSING` | `ARCHIVED`), `is_default`, `sort_order`, `created_at`.

| Table | Change | Meaning |
|---|---|---|
| `gym_class` | `gym_id` NOT NULL | a class belongs to exactly one gym; bookings reach a gym through their class |
| `closed_day`, `closed_period` | `gym_id` NOT NULL | closed days are per gym |
| `plan` | `gym_id` NULL | empty = valid at all gyms |
| `promo` (home page items) | `gym_id` NULL | empty = shown at all gyms |
| `marketing_campaign` | `gym_id` NULL | audience: one gym's members or everyone |
| `app_user` | `home_gym_id` NULL | what a member sees first; empty = the default gym |

The migration (`009-gyms.yaml`) creates the first gym as the default and gives it every existing row.
Unchanged: plan history, email preferences, the email queue, the booking window and horizon (global in version 1).

## 5. The switch

| Setting (app `backend`) | Default | Effect |
|---|---|---|
| `gyms.multi-enabled` | `false` | `true`: the Gyms page, gym selectors and per-gym data are active. `false`: single-gym mode on the default gym |

Read at start and every 5 minutes (`config.client.refresh-seconds`) by `FeatureFlags`; the UI asks `GET /api/features`
(`multiGym`) once a minute. An environment variable `GYMS_MULTI_ENABLED` fixes the value. A missing value means off. If the
configuration server cannot be reached the last known value stays in force.

## 6. Gym lifecycle and the default gym

```
ACTIVE ──stop new bookings──► CLOSING ──archive (no bookings to come)──► ARCHIVED
   ▲◄───────reopen────────────────┘                                          │
   └◄──────────────────────────restore──────────────────────────────────────┘
```

* **ACTIVE**: visible and bookable.
* **CLOSING**: no new bookings; existing bookings still stand and are shown.
* **ARCHIVED**: hidden, read-only history, kept for reports. Version 1 has no hard delete.
* Exactly one gym is the **default gym** **(decided: set on the Gyms page, stored as data)**. It must be ACTIVE and can
  never be closed or archived while it is the default. Changing the default is allowed at any time to another ACTIVE gym.

## 7. Switching multi-gym off

The switch is in the configuration server, so it can be flipped at any moment. Switching off must therefore be safe at
any moment, and there is a prepared route for when it should be tidy.

### A. Prepared switch-off (recommended)

1. Make the gym you keep the default gym.
2. Set every other gym to CLOSING. Members can no longer book there; existing bookings stay.
3. Use the **switch-off check** on the Gyms page: it lists future bookings at other gyms, members whose home gym is
   another gym, and plans/home page items that are only for another gym.
4. Resolve it: wait for the bookings to pass, move members to the default gym, make plans valid everywhere.
5. Archive the other gyms (possible once no bookings are left to come).
6. Set `gyms.multi-enabled` to `false`.

The check is advice only. It never blocks the switch.

### B. Switched off without preparing

Nothing breaks and nothing is deleted. The system behaves as if only the default gym exists:

* Members see only the default gym. A plan they hold keeps working there even if it was made for another gym.
* Bookings at other gyms still stand and count toward the monthly visits. They stay visible in My bookings, marked with
  the gym, and can be cancelled. No new ones can be made. A banner on the member page says so.
* The admin sees the default gym only, with a banner saying how many bookings still stand at other gyms.
* Reminder emails for those bookings still go out.
* Switching on again restores every gym and its data exactly as it was.

## 8. Other scenarios

| Situation | Behaviour |
|---|---|
| Upgrade, switch still off | One default gym holds all existing data. Nothing visible changes |
| First time switched on | The Gyms page appears with one gym; selectors stay hidden until a second active gym exists |
| Plan made for another gym | Works at the gyms it is valid at; always works at the default gym in single-gym mode |
| Monthly visits **(decided)** | One calendar-month allowance per member, counted across all gyms |
| Member booking **(decided)** | A member can book at any gym where their plan is valid; the home gym only decides what they see first |
| Plans **(decided)** | Each plan is for one gym or for all gyms |
| Time zones | Each gym has its own zone for "has this class started"; empty uses the application's zone |
| Emails | Reminder emails are unchanged (they are about the member's plan, not a class); a marketing email goes to one gym's members or to everyone |
| Admin access | One admin manages every gym in version 1 |
| Booking window and horizon **(decided)** | Global in version 1 |
| Configuration server unreachable | Last known switch value stays in force |

## 9. API

* `GET /api/features` adds `multiGym`.
* `GET /api/gyms`: the gyms a member can see (ACTIVE and CLOSING; only the default while the switch is off).
* `PUT /api/me/home-gym` `{gymId}`: a member's own gym choice.
* Admin: `GET/POST /api/admin/gyms`, `PUT /api/admin/gyms/{id}`, `PUT /api/admin/gyms/{id}/default`,
  `PUT /api/admin/gyms/{id}/status` `{status}`, `GET /api/admin/gyms/switch-off-check`, all 404 while the switch is off
  except the check and the list.
* Existing endpoints accept an optional `gymId` query parameter (see section 3).
* `GET /api/admin/analytics?gymId=` filters one gym; without it all gyms added together, plus a per-gym comparison.

## 10. Build stages

1. **Foundation**: `gym` table, `gym_id` columns, default gym, scoping with the switch off (no visible change).
2. **The switch**: `gyms.multi-enabled` in `FeatureFlags`, `multiGym` in `/api/features`, the resolution rules.
3. **Admin**: Gyms page, header selector, Plans/Schedule/Home page/Closed days/Marketing following the selected gym.
4. **Members**: home gym, gym picker, gym names on classes and bookings, per-gym time zones, gym in emails.
5. **Switch-off tools and analytics**: switch-off check, banners, analytics by gym with a comparison table.

Main risk: data that belongs to a gym but was not scoped, leaking one gym's data into another. Mitigation: every query
goes through the resolved gym, and each page has a test that creates two gyms and checks nothing crosses over.
