# Author decisions shared by the admin-service and admin-ui designs

The two EGM designs for the CCE v2 admin module are drafted at the same time and depend on each
other through the API contract. This file records the author's decisions that both designs must
follow, so the two stay consistent and the reviewers can see why a choice was made.

- **Who decided:** Claude, acting for the user (Jayaprakash), who delegated the development.
  Decisions are based on the PRD and the inputs in `context/`.
- **How decisions are made:** decisions that EGM puts to the author during drafting are taken at
  those gates (see `trial-log/QA.md`) and copied here when both designs need them. IDs are not
  reused.

| ID | Decision | Why | Sources |
|---|---|---|---|
| D-01 | **The admin-service design owns the API contract.** It defines every endpoint's request and response shape, error codes, pagination and warnings. The admin-ui design uses exactly that contract. If the console needs something the contract does not give, the admin-ui design lists it as a requirement on the admin-service design, and the admin-service design is updated. The console must not work around the contract. | One owner for each fact. The service is the provider, and other consoles (cce-insights-ui) also depend on the same contract. | [PRD §6, §7, §8.1 F-02, Appendix A, Appendix B](../../context/prd/CCE_Admin_Module_PRD.md) |
| D-03 | **The local environment is defined once, in the admin-service design (milestone M0).** It covers the Keycloak realm and clients, ports, the Postgres database, and how the gateway is stood in for. The admin-ui design uses those values for local development and does not define its own. Fixed values: service on `localhost:8085`, console dev server on `localhost:3002`, the console's Vite dev proxy forwards `/admin/v1/*` to `localhost:8085`, and the Keycloak client `cce-admin-ui` is public with PKCE S256 and redirect `http://localhost:3002/*`. | M0 belongs to the backend track in the PRD, and one definition avoids two drifting copies. | [PRD §6, §8.3, §10 (suggested local setup)](../../context/prd/CCE_Admin_Module_PRD.md) |

## D-05: Pinned versions (checked on 2026-11-10)

Both designs pin every image and dependency to the exact versions below, so the implementer
makes no version choices. The author checked each one on 2026-11-10: Maven artifacts against
Maven Central, npm packages against the npm registry (newest release inside the major version
that PRD §6 names), and image tags with `docker manifest inspect`. Images were also pulled on
this machine.

**Backend (cce-admin-service and its stand-in gateway)**

| Item | Version | Note |
|---|---|---|
| Spring Boot (`spring-boot-starter-parent`) | 3.5.16 | Newest 3.x (PRD §6 says 3.x). Its dependency management fixes the Spring, Hibernate, Flyway, PostgreSQL JDBC, Jackson and Testcontainers versions for the service. |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.10.0 | Built against Spring Boot 3.5.16 (its parent POM). The 3.x line is for Spring Boot 4. |
| `org.keycloak:keycloak-admin-client` | 26.0.12 | For the Keycloak 26 server. |
| `org.postgresql:postgresql` (stand-in gateway only, outside Boot management) | 42.7.14 | |
| Maven | 3.9.16, through the Maven Wrapper | The machine has Maven 3.8.7. The wrapper makes local and image builds use the same Maven. |
| `org.apache.maven.plugins:maven-wrapper-plugin` (and the wrapper it installs) | 3.3.4 | Generates `mvnw` and `.mvn/wrapper/`. Run it with the machine's Maven 3.8.7: `mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16`. |
| Image: Postgres | `postgres:16.15-alpine` | |
| Image: Keycloak | `quay.io/keycloak/keycloak:26.4.7` | |
| Image: Mailpit | `axllent/mailpit:v1.33.1` | |
| Image: build stage | `maven:3.9.16-eclipse-temurin-21` | |
| Image: runtime | `eclipse-temurin:21.0.13_9-jre-alpine` | |

**Console (cce-admin-ui)**

| Package | Version | Package | Version |
|---|---|---|---|
| `react`, `react-dom` | 18.3.1 | `@types/react` / `@types/react-dom` | 18.3.31 / 18.3.7 |
| `typescript` | 5.9.3 | `vite` | 6.4.6 |
| `@vitejs/plugin-react` | 4.7.0 | `react-router` | 7.18.5 |
| `@tanstack/react-query` | 5.107.1 | `@tanstack/react-table` | 8.21.3 |
| `tailwindcss`, `@tailwindcss/vite` | 4.3.5 | `@heroicons/react` | 2.2.0 |
| `date-fns` | 4.4.0 | `react-hook-form` | 7.91.0 |
| `zod` | 3.25.76 | `@hookform/resolvers` | 3.10.0 |
| `keycloak-js` | 26.2.5 | `vitest` | 3.2.7 |
| `jsdom` | 26.1.0 | `@testing-library/react` | 16.3.3 |
| `@testing-library/user-event` | 14.6.9 | `@testing-library/jest-dom` | 6.10.0 |
| `@testing-library/dom` | 10.4.2 | `msw` | 2.15.0 |
| `@playwright/test` | 1.65.0 | `eslint`, `@eslint/js` | 9.39.5 |
| `typescript-eslint` | 8.72.2 | `eslint-plugin-react-hooks` | 5.2.0 |
| `prettier` | 3.9.10 | `globals` | 16.5.0 |
| `@vitest/coverage-v8` | 3.2.7 | `eslint-config-prettier` | 10.1.8 |
| `@types/node` | 22.20.5 | | |
| Image: build | `node:20.20.2-alpine` | Image: runtime | `caddy:2.11.9-alpine` |

Packages are pinned to exact versions in `package.json` (no `^` or `~`), and `package-lock.json`
is committed.

## Retired decisions

D-02 (`INACTIVE` can be reversed) and D-04 (the `api_permissions` shape) were made during the
first drafting attempt (tag `step-01`). That attempt also used the team's design documents,
which are no longer inputs. These questions are decided again, at EGM's gates, if the new drafts
raise them.
