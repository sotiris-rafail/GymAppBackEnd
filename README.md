# Foundry gym calendar: Spring Boot backend

Plans, classes, bookings and logins, all saved in a database. This project is only the API. The browser UI
is a separate project, `foundry-gym-ui`, which calls this API over HTTP.

## Database schema and Liquibase

The schema is owned by Liquibase, not Hibernate. On start-up Liquibase applies any changesets that have not run yet
(`src/main/resources/db/changelog/`), then Hibernate (`ddl-auto: validate`) only checks that the JPA entities match the
tables and refuses to start if they do not.

| Table | Entity | Purpose |
| --- | --- | --- |
| `plan` | `Plan` | Monthly subscription: name, price, visits per month |
| `app_user` | `AppUser` | Members and the admin; `plan_id` is the plan held now (at most one) |
| `gym_class` | `GymClass` | Weekly class: coach, start time, length, max people |
| `gym_class_day` | `GymClass.days` | Weekdays a class runs |
| `booking` | `Booking` | A member's seat in a class on one date; unique per member, class and date |
| `plan_history` | `PlanHistory` | Plans a member held, with name/price/visits copied at the time |

`docs/schema.sql` is a readable reference copy of the same tables (documentation only, never executed).

To change the schema, add a new file such as `changes/002-add-something.yaml`, list it at the end of
`db.changelog-master.yaml`, and update the entity. Never edit a changeset that has already been applied anywhere:
Liquibase checksums them and will refuse to start. Each initial changeset is skipped if its table already exists, so a
database created by the earlier `ddl-auto: update` setup is adopted as it is; it must match these tables.

Not enforced by the database: "one active plan per member" holds because `app_user.plan_id` is a single column, and
`plan_history` keeps at most one open row per member in the service layer (H2 has no partial unique indexes).

## Run it

Needs Java 17 or newer and Maven.

    mvn spring-boot:run

The API listens on http://localhost:8080. Then start the UI from the `foundry-gym-ui` folder
(`npm start`) and open http://localhost:5173.

Start the config server first (see below). If it is not running the backend still starts, with the settings in
`application.yml`, and logs a warning.

Data is stored in `./data/foundry.mv.db` (an H2 file database), so it survives restarts. Delete the
`data` folder to start over.

On the first start with an empty database the app creates the admin account and some example plans,
classes and two members.

| Login  | Email               | Password    |
|--------|---------------------|-------------|
| Admin  | admin@foundry.gym   | admin123    |
| Member | alex@demo.gym       | member123   |
| Member | sam@demo.gym        | member123   |

Members can also create their own account on the login screen.

## Settings (environment variables)

| Variable              | Default            | What it does                                              |
|-----------------------|--------------------|-----------------------------------------------------------|
| `PORT`                | 8080               | HTTP port                                                 |
| `GYM_ADMIN_EMAIL`     | admin@foundry.gym  | Admin login, used when the database is first created      |
| `GYM_ADMIN_PASSWORD`  | admin123           | Admin password, used when the database is first created   |
| `GYM_SEED_DEMO`       | true               | Set to `false` to start with no example data              |
| `GYM_CORS_ORIGINS`    | http://localhost:5173,http://127.0.0.1:5173 | Addresses the UI is served from, comma separated. Browsers may call the API only from these |
| `GYM_TIMEZONE`        | server time zone   | Time zone that decides when a class has started, e.g. `Europe/Berlin` |
| `GYM_DB_PATH`         | ./data/foundry     | Where the database file lives                             |

Set `GYM_ADMIN_PASSWORD` before the first start of anything other than a demo.

## Secret password salt and email encryption

Two secrets come from the config server (app `backend`, both marked secret there; the config server makes random ones on
its first start). They have **no default**: the backend refuses to start if either is missing or shorter than 16
characters. An environment variable overrides the config server (`GYM_PASSWORD_SALT`, `GYM_EMAIL_KEY`); make one with
`openssl rand -base64 32`.

| Config server key            | What it does |
|------------------------------|--------------|
| `gym.security.password-salt` | A secret salt mixed into every password. The password is run through HMAC-SHA256 with it, and the result is hashed with BCrypt (which still adds its own random salt per user). New hashes start with `p1$`. A stolen database cannot be attacked offline without this secret, and passwords longer than 72 bytes are no longer cut off. |
| `gym.security.email-key`     | Encrypts every email address with AES-256-GCM (a random IV each time, stored as `v1:...`). Because the ciphertext differs every time it cannot be searched, so a second column `email_hash` (HMAC-SHA256 of the lower-case address, unique) is used for login and the "already registered" check. |

What happens to what is already there: the changeset `encrypted-email-001` widens `app_user.email` and adds `email_hash`.
At start-up `EmailEncryptionMigration` encrypts the address of every existing account (it runs on every start and does
nothing once all are done). Existing passwords keep working: plain BCrypt hashes still verify, and each one is replaced by
the salted kind the first time that person logs in.

**Do not change either secret once accounts exist.** A different salt makes every `p1$` password fail (there is no
password reset), and a different key makes the stored addresses unreadable (the backend stops with "Could not decrypt an
email address"). Back them up together with the config server's database. Rotating them would need a one-off job that
re-encrypts the addresses; passwords cannot be moved to a new salt without each person logging in again.

Not covered: the config server itself, the database file and its backups are as safe as you keep them; names, plans and
bookings are not encrypted; the address is visible in memory while the app runs and in the admin's member list, as before.

## Backend on your own computer, UI hosted elsewhere

You can keep the backend on `localhost:8080` of your own machine and host only the UI somewhere else (for example an
EC2 machine). The browser then calls your own localhost. Three things are needed:

1. Allow the UI's address and turn off the config server's localhost value, e.g.

       GYM_CORS_ORIGINS=http://<ui-host>:5173 CONFIG_CLIENT_ENABLED=false mvn spring-boot:run

2. The login cookie must be allowed across the two addresses (they count as different sites):

       SERVER_SERVLET_SESSION_COOKIE_SAMESITE=none SERVER_SERVLET_SESSION_COOKIE_SECURE=true

   Browsers accept a `Secure` cookie from `http://localhost`.
3. In the UI's `config.js`: `window.FOUNDRY_API_BASE = 'http://localhost:8080';`

Newer browsers may block or ask permission when a public web page talks to `localhost`, and some block third-party
cookies. If it does not work in your browser, run the UI on the same computer instead (`npm start`), or reach EC2 with an
SSH tunnel.

## Settings from the config server

At start-up the backend fetches its settings from the Foundry config server (`foundry-config-server`, default
http://localhost:8888) before Spring reads its own configuration. It first trades its client id and secret for a short-lived token (`POST /auth/token`) and then reads its settings with `Authorization: Bearer <token>`, so only an application the config server knows can fetch them. Every setting the server holds for
the application `backend` becomes a normal Spring property, so `gym.*` settings, `spring.datasource.url` and so on can
all be managed there. The code is `ConfigServerEnvironmentPostProcessor`, registered in `META-INF/spring.factories`.

Priority, highest first: command line, JVM system properties, environment variables, **config server**,
`application.yml`. Careful with the variables in the table above (`GYM_CORS_ORIGINS`, `GYM_TIMEZONE`, ...): they are
only placeholders inside `application.yml`, so once the config server holds the same setting (for example
`gym.cors-origins`) the config server's value wins. Change such a setting in the console, or start with
`CONFIG_CLIENT_ENABLED=false`. A variable written in Spring's own form, such as `GYM_CORSORIGINS`, does beat the config
server. Changes made in the console apply the next time the backend starts.

| Variable                 | Default                 | What it does                                             |
|--------------------------|-------------------------|----------------------------------------------------------|
| `CONFIG_CLIENT_ENABLED`  | true                    | Set to `false` to ignore the config server entirely      |
| `CONFIG_SERVER_URL`      | http://localhost:8888   | Where the config server is                               |
| `CONFIG_CLIENT_ID` / `CONFIG_CLIENT_SECRET` | backend / backend-secret | The client id and secret configured on the config server (`cfg.clients`); they are only used to get a token |
| `CONFIG_APP_NAME`        | backend                 | Which application's settings to fetch                    |
| `CONFIG_CLIENT_REQUIRED` | true                    | Refuse to start when the config server cannot be reached. `false` starts anyway (then `GYM_PASSWORD_SALT` and `GYM_EMAIL_KEY` must be set) |

Active Spring profiles (`SPRING_PROFILES_ACTIVE=prod`) are passed on, so the server can hold `prod` overrides.
The log says how many settings were loaded, never their values.

## How the rules are enforced

* A class has a max-people limit. Every session (the class on one date) holds at most that many members.
* A plan has a price and visits per calendar month. Booking uses one visit, cancelling a class that has not
  started gives it back. A plan can instead be **unlimited** (`unlimited: true`, changeset `unlimited-plan-001`): such a
  member can book as many classes as they like (the usual capacity, closed days and booking window still apply).
  `visitsPerMonth` is stored as 0 for an unlimited plan and is ignored in requests; for a limited plan it is required.

### Switching unlimited plans on and off (config server)

The setting `plans.unlimited-enabled` (app `backend` in the config server; a missing value means `true`) turns the option on
or off. The backend asks the config server again every `config.client.refresh-seconds` (default 300, so every 5 minutes; env `CONFIG_REFRESH_SECONDS`), so a change in the
console takes effect within 5 minutes without restarting. The value is held in one shared instance (`FeatureFlags`), so requests never wait for the config server. When it is `false`:

* a plan cannot be created as, or switched to, unlimited, and no member can be assigned an unlimited plan (400 `Unlimited
  plans are turned off.`);
* plans that are already unlimited keep working: their members keep booking, the plan can be edited, and it can be switched
  to a limited plan;
* `GET /api/features` returns `{"unlimitedPlans": false, "mail": true, "analytics": true, "home": true}` so the UI greys out the option (`mail` is whether this backend can send email, `analytics` whether the Analytics page is switched on).

An environment variable `PLANS_UNLIMITED_ENABLED`, a JVM property or a command line argument beats the config server and fixes
the value (the config server is then not asked). If the config server is unreachable the last known value stays in force.
* Only the admin assigns plans. A member sees their plan but cannot change it, and holds one plan (or none) at a time. Removing a plan leaves classes already booked in place.
* Booking locks the member row and then the class row inside one transaction, so two requests at the same
  moment cannot take the last seat or exceed the monthly limit.
* Members and the admin log in through separate tabs. Admin credentials do not work as a member login and
  the other way round. Passwords are stored as BCrypt hashes. Sessions use an HttpOnly cookie.

## Using it from the separate UI

The UI runs on another port, so the browser treats it as another origin. `SecurityConfig` allows the
addresses in `GYM_CORS_ORIGINS`, including cookies, and the login session cookie is `SameSite=Lax`.
`localhost:5173` and `localhost:8080` count as the same site, so this works as long as you open the UI
and the API under the same host name (both `localhost` or both `127.0.0.1`).

If the UI is hosted on a different domain, set the session cookie to `SameSite=None; Secure` (needs HTTPS)
or put both behind one reverse proxy.

## API

All paths return JSON. Errors look like `{"message":"That class is full."}`.

| Method and path                      | Who      | Purpose                                              |
|--------------------------------------|----------|------------------------------------------------------|
| POST `/api/auth/login`               | anyone   | `{email, password, role: "MEMBER" or "ADMIN"}`       |
| POST `/api/auth/register`            | anyone   | `{name, email, password}`, creates a member and logs in |
| POST `/api/auth/logout`              | anyone   | Ends the session                                     |
| GET `/api/auth/me`                   | signed in| The current person                                   |
| GET `/api/plans`, `/api/classes`     | signed in| What exists                                          |
| POST/PUT/DELETE `/api/admin/plans[/{id}]`   | admin | `{name, price, visitsPerMonth, unlimited?}`    |
| POST/PUT/DELETE `/api/admin/classes[/{id}]` | admin | `{name, coach, startTime, durationMinutes, capacity, days}`, days are 1 (Mon) to 7 (Sun) |
| GET `/api/admin/members`             | admin    | Members with plan and visits used this month         |
| GET `/api/admin/attendance?month=2026-10` | admin | Every class with who is booked on which date (default: current month) |
| GET `/api/admin/members/{id}/history`| admin    | Plans the member has held (with from/to) and visits used per month |
| PUT `/api/admin/members/{id}/plan`   | admin    | `{planId}` assigns or replaces a member's plan, `{planId: null}` removes it |
| GET `/api/sessions?from=&to=`        | member   | Class occurrences with `booked`, `capacity`, `mine`  |
| GET `/api/me/bookings`               | member   | This member's bookings                               |
| POST `/api/bookings`                 | member   | `{classId, date}`                                    |
| DELETE `/api/bookings/{id}`          | member   | Cancel                                               |

## Plan history

Every plan change (assign, replace, remove, or the plan being deleted) is recorded in the `plan_history` table with the
plan's name, price and visits at that moment. Visits per month are counted from bookings. Databases created before
this feature get a period starting at first start for members who already have a plan.

## Tests

    mvn test

`GymApiTests` covers the separate logins, role protection, admin validation, the capacity limit, the
monthly visit limit with cancel, admin-only plan assignment, one member trying to cancel another member's booking, and the browser
preflight from the UI's address. `ConfigServerClientTests` runs the start-up config client against a tiny fake
config server (token requested first, settings read with the Bearer token, environment variables still win, failure handling).

## Before putting this on the internet

* CSRF protection is switched off in `SecurityConfig` (the cookie is SameSite=Lax and the API only takes
  JSON). Turn it on and send the token from the page.
* There is no limit on failed login attempts and no password reset.
* Serve it over HTTPS and set the session cookie to `secure`.
* H2 is fine for one server. For a shared database, swap the `h2` dependency for `postgresql` and set
  `spring.datasource.url`, `username` and `password`. No code changes are needed.

## Status

This code was written and reviewed without being compiled or run, because the workspace it was written in
could not download Maven dependencies. The UI was exercised in a browser against a stand-in server that follows
the same API. Run `mvn test` first; if anything fails to compile, it is likely a small fix.

## Home page items

The public home page shows items the admin manages on the "Home page" admin page: photos, Instagram posts and promotions.
`GET /api/promos` is open to visitors who are not logged in and returns active items only; `/api/admin/promos` (list,
create, update, delete) is admin only. A photo is an http(s) address or a small uploaded image stored in the `promo`
table (changeset `promo-001`). Type `CARD` is an announcement card with an extra `content` field (HTML text, up to 20000
characters, changeset `promo-004`); the UI sanitises it before showing it. An item can have `hideOn` (a date: from that day on it is no longer returned by
`GET /api/promos`, so an item created today with `hideOn` = today + 7 shows for exactly 7 days) and a tile size `colSpan`
(1-4) and `rowSpan` (1-8), where 0 means automatic, and a background: `bgColor` (`#rrggbb`) and `bgImage` (address or uploaded
image, changeset `promo-006`). Type `TILE` is a clickable tile: its background (`bgColor` and/or `bgImage`) with an optional title and text, and the whole tile opens `linkUrl`, which is required for tiles (400 otherwise). Admins still see expired items in `/api/admin/promos`. Links must start with `http://` or `https://`.

## Closed days

Sundays are always closed: `/api/sessions` returns nothing for them and booking one answers 409. The admin can also close
other dates: `POST /api/admin/closed-days` with `{"date":"2026-12-25","reason":"Christmas"}` (closing a Sunday is a 400),
`DELETE /api/admin/closed-days/2026-12-25` to open it again. `GET /api/closed-days` (any signed-in user) lists the closed
dates. `PUT /api/admin/closed-periods/WEEK` or `/MONTH` with `{"closed":true}` closes bookings for the whole current week (Monday
to Sunday) or calendar month, `{"closed":false}` opens it again, and `GET /api/admin/closed-periods` lists the closed
weeks/months that have not ended (table `closed_period`). `GET /api/closed-days` lists every closed date, with `period`
set to `WEEK` or `MONTH` for dates closed that way. Closing a date, week or month cancels the bookings already made for those days that have not started (the visit is given back, like a member cancelling) and, while `gym.mail.enabled` is true, emails each member a `CLASS_CANCELLED` notice (sent whatever their Preferences). `GET /api/admin/closed-days/impact?from=&to=` returns `{bookings, members}` for the admin's confirmation without changing anything. Table `closed_day`, changesets `closed-day-001` and `closed-day-002-rename-day`. The date column is `closed_on` because `day` is a reserved word in H2.

## Booking window

`GET /api/booking-window` (any signed-in user) returns `{"mode":"ANY|WEEK|MONTH","horizonDays":90,"lastBookable":"2027-01-12"}`
and `PUT /api/admin/booking-window` with `{"mode":"..."}` changes the mode (and returns the same three fields). `ANY` (the
default) adds no limit of its own, `WEEK` lets members book only inside the current week (Monday to Sunday) and `MONTH` only
inside the current calendar month.

The window works together with the booking horizon `gym.booking-horizon-days` (default 90; a config server setting read when
the backend starts): **a day is bookable only when it is inside the window and no more than that many days after today**, so
the window can only shorten the horizon, never extend it. `lastBookable` is the earlier of today + horizon and the end of the
window; the UI uses it to grey out later days. Days that fail either rule are not returned by `/api/sessions`; booking one
answers 409 for the window ("Booking is only open for the current week/month.") or 400 for the horizon ("You can book up to N
days ahead."). Stored in table `app_setting` (changeset `app-setting-001`).

## Profile and password

`PUT /api/auth/password` (any signed-in member or admin) with `{"currentPassword","newPassword"}` changes the caller's own
password. The current password is required (400 "Your current password is not correct." otherwise, so the session stays
signed in), the new one must be 6 to 72 characters and different from the old one. Nothing else about an account can be
changed by its owner: name and email are fixed, and the plan is assigned by the admin. `GET /api/auth/me` returns the
profile (id, name, email, role, planId).


### Mandatory at start-up

The backend will not start unless all three are in place:

* the config server answers (`CONFIG_CLIENT_REQUIRED` is `true` by default; set it to `false` only for a run without a
  config server, and then supply the keys yourself),
* `gym.security.password-salt` is known (config server, or `GYM_PASSWORD_SALT`),
* `gym.security.email-key` is known (config server, or `GYM_EMAIL_KEY`).

Each of the last two must be at least 16 characters. A local run without a config server therefore needs:
`CONFIG_CLIENT_REQUIRED=false GYM_PASSWORD_SALT=... GYM_EMAIL_KEY=... mvn spring-boot:run`.


## Emails to members (schedulers)

Off until `gym.mail.enabled` is `true` on the config server, and live after that (see below). The toggles in a member's Preferences are
honoured by timers inside the backend. They only *decide* who should get which email and put it in a table, `email_outbox`;
a second timer *sends* from that table. This is what makes them safe: the table's unique (member, key) pair means nobody
gets the same email twice (after a restart, or when a job overlaps itself), a failed send is kept and retried, and the
member's choice is checked again at the moment of sending, so switching an email off also stops one already queued.

| Toggle in Preferences | Who gets it, and when | Key (one per member) |
|---|---|---|
| **Marketing emails** | Items on the home page that the admin ticked **Also send as a marketing email** (once, after the admin saves; the item must be active and not past its hide-on date), and emails the admin sends from the **Marketing** page | `promo:<id>`, `campaign:<id>` |
| **New plans** | Plans created in the last `news-window-days` days (default 7). Plans that existed before the feature get a date long ago, so they are never announced | `plan:<id>` |
| **Plan almost used up** | A member on a limited plan who has booked at least one class and has exactly 2 visits left this month (visits per month minus classes booked in the month) | `ending:<yyyy-MM>` |
| **Visits left reminder** | From the 21st of the month (and from `reminder-hour`, default 9:00 gym time) on: a member on a limited plan who still has visits left this month | `reminder:<yyyy-MM>` |

Members on an unlimited plan get neither of the two visit emails. Wording is plain text, starts with the member's name and
ends with why they got it and how to switch it off. The address is read, and decrypted, from the account at the moment of
sending (it is never copied into the outbox).

**Timers** (`NotificationScheduler`): every `scan-seconds` (900) the four jobs queue emails, every `send-seconds` (60) the
queue is sent (up to `batch-size`), and once a day finished rows older than 90 days are removed. A failed send is tried
again after 1, 2, 4... minutes (at most an hour) and given up (`FAILED`) after `max-attempts` (5). A job that throws is
logged and the others still run. One backend is assumed; with two the unique key still prevents duplicates, but one of them
would log a failed job now and then.

| Setting (`gym.mail.*`) | Default | What it does |
|---|---|---|
| `enabled` | false | Switches everything on or off, live. When true at start-up the backend **refuses to start** unless `spring.mail.host` and `from` are set |
| `from` | | Sender, e.g. `Foundry Gym <no-reply@yourgym.com>` |
| `app-url` | | Address of the web app, shown in the emails |
| `gym-name`, `currency` | Foundry, € | Wording of the emails |
| `scan-seconds`, `send-seconds`, `initial-delay-seconds` | 900, 60, 30 | Timer intervals (read at start-up only) |
| `reminder-hour`, `news-window-days`, `max-attempts`, `batch-size` | 9, 7, 5, 50 | See above |

Every one of these settings, and the mail server's, is fetched from the config server (app `backend`); the config server creates
all of them with sensible defaults the first time it starts (or when one is missing), so you only fill in the host, user,
password and sender there and set `gym.mail.enabled` to `true`. The mail server uses the standard Spring settings, with the password as a secret:
`spring.mail.host`, `spring.mail.port`, `spring.mail.username`, `spring.mail.password`,
`spring.mail.properties.mail.smtp.auth=true`, `spring.mail.properties.mail.smtp.starttls.enable=true`.

**Live, no restart.** Like `plans.unlimited-enabled`, all of these are kept in one shared object (`MailSettings`, a Spring
singleton) that asks the config server again every `config.client.refresh-seconds` (default 300, i.e. every 5 minutes). So
turning the emails off, or changing the mail server, user, password, sender address or wording, takes effect within 5 minutes
and the very next email uses the new connection settings. Only `scan-seconds`, `send-seconds` and `initial-delay-seconds`
(the timers themselves) need a restart. Details:

* **Off** (`gym.mail.enabled=false`): the timers keep running but queue and send nothing (what is already queued waits and is
  sent after switching on again), the Marketing page cannot send (409), and members **cannot turn a Preferences toggle on**:
  `PUT /api/me/preferences` answers 409 "Emails are switched off at the moment, so they cannot be turned on. You can still
  turn them off." for a toggle going from off to on; turning one off is always allowed. `GET /api/features` returns
  `"mail": false`, which the UI uses to grey the switches out.
* **At start-up**, `enabled=true` with `spring.mail.host` or `gym.mail.from` missing stops the backend from starting.
* **Later**, a change that would leave the emails on but incomplete (no host or no sender) is refused: it is logged once and the
  previous working settings stay in force, so a typo in the console cannot break sending.
* If the config server is unreachable the last settings stay in force (logged once). An environment variable, JVM property or
  command line argument still beats the config server for that one key and keeps it fixed.
* The password is never logged.

### Marketing page and home page tick (admin)

* `POST/PUT /api/admin/promos` take a new `marketing` flag (400 "To email an item it needs some text or a link." when the item
  has neither). The marketing job queues the item for every member with marketing on and then stamps the item
  (`marketingSentAt`, `marketingRecipients`); from then on the flag can no longer be changed.
* `/api/admin/marketing` (admin only): `GET` lists the emails newest first with delivery counts (delivered, waiting, failed,
  skipped), `POST` creates a draft `{subject, body, linkUrl}`, `PUT /{id}` and `DELETE /{id}` change or remove a draft
  (409 once sent), `POST /{id}/send` queues one email per member who has marketing on (409 when `gym.mail.enabled` is off, 400
  when nobody has it on) and marks it SENT; the sending timer delivers them within about a minute. `GET /status` returns
  `{mailEnabled, optedIn}`.

Tests: `EmailJobsTests` runs the four jobs, the sender (retry, switched off meanwhile) and the Marketing API against H2 with a
fixed clock (22 Oct 2026, 10:00) and a recording stand-in for the mail server. Nothing in the test suite sends real mail.


## Analytics (admin)

`GET /api/admin/analytics?from=yyyy-MM-dd&to=yyyy-MM-dd` returns everything the admin's Analytics page shows, worked out
from data that is already stored (nothing new is written and there is no new table):

* the bookings, fill rate, members-with-a-plan and plan-usage numbers for the period, each compared with the period
  of the same length just before it; a period of 35 days or less is charted per day, longer per week;
* members with a plan at the end of a day come from `plan_history` (a member who has a plan but no history counts as
  always having had it);
* most booked classes, a weekday x hour table of bookings, members by plan (with the members who have none),
  plan usage this month, members with a plan and no booking for 30 days, who has each email preference on, and the
  last sent marketing emails. The last four blocks describe the situation now and ignore the period.

`to` defaults to today and may not be later than today; a period is at most 366 days (otherwise 400).
Tests: `AnalyticsTests` (run `mvn test`).


### Analytics settings from the config server (live)

`marketing.enabled` (default true; false = the Marketing menu entry is hidden and every `/api/admin/marketing` endpoint answers 404; emails already queued are still sent),
`analytics.enabled` (default true; false = the menu entry is hidden and `GET /api/admin/analytics` answers 404),
`analytics.follow-up-days` (default 30) and `analytics.low-fill-percent` (default 65) are read with the other live
switches (`FeatureFlags`): at start-up and then every `config.client.refresh-seconds` (5 minutes). Bad numbers fall back
to the default. The report returns `followUpDays` and `lowFillPct` so the page shows the values in force.


### Home page switch from the config server (live)

`home.enabled` (default true) is read with the other live switches (`FeatureFlags`). While it is false,
`GET /api/promos` returns an empty list (still 200, so the home page just shows no items) and every `/api/admin/promos`
call answers 404 "The home page is switched off." Items are not deleted; turning it back on restores them.
The emails for items ticked "Also send as a marketing email" are not affected by this switch.


### Several gyms (live switch, off by default)

`gyms.multi-enabled` (default false; env var `GYMS_MULTI_ENABLED` pins it) is read with the other live switches.
Design and rules: `docs/multi_gym_analysis.md`. Summary:

* Off = one gym, exactly as before. A gym "Main gym" (the default gym) always exists (changeset 009).
* On = `/api/admin/gyms` (add, edit, `PUT /{id}/default`, `PUT /{id}/status` ACTIVE/CLOSING/ARCHIVED), `GET /api/gyms` (what
  members can choose), `PUT /api/me/home-gym`, `PUT /api/admin/members/{id}/home-gym`, and an optional `?gymId=` on the
  scoped endpoints (classes, plans, sessions, closed days, promos, attendance, marketing status, analytics).
* Exactly one default gym; it must be ACTIVE and cannot be closed or archived. ARCHIVED only from CLOSING with no bookings to come.
* Plans, home page items and marketing emails may be tied to one gym or left for all gyms.
* Switching off deletes nothing: other gyms are hidden, bookings made there still stand, members' plans keep working at the
  default gym. `GET /api/admin/gyms/switch-off-check` (always available) lists what is left elsewhere, with
  `POST /api/admin/gyms/{id}/move-members-to-default` and `/share-plans` to tidy up. It is advice; it never blocks.
