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

## Settings from the config server

At start-up the backend fetches its settings from the Foundry config server (`foundry-config-server`, default
http://localhost:8888) with HTTP Basic, before Spring reads its own configuration. Every setting the server holds for
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
| `CONFIG_CLIENT_USER` / `CONFIG_CLIENT_PASSWORD` | backend / backend-secret | The client account configured on the server |
| `CONFIG_APP_NAME`        | backend                 | Which application's settings to fetch                    |
| `CONFIG_CLIENT_REQUIRED` | false                   | `true`: refuse to start when the config server cannot be reached |

Active Spring profiles (`SPRING_PROFILES_ACTIVE=prod`) are passed on, so the server can hold `prod` overrides.
The log says how many settings were loaded, never their values.

## How the rules are enforced

* A class has a max-people limit. Every session (the class on one date) holds at most that many members.
* A plan has a price and visits per calendar month. Booking uses one visit, cancelling a class that has not
  started gives it back.
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
| POST/PUT/DELETE `/api/admin/plans[/{id}]`   | admin | `{name, price, visitsPerMonth}`                |
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
config server (settings applied, Basic auth sent, environment variables still win, failure handling).

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
