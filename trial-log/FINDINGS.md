# Findings ledger

This file lists every problem found during the trial, grouped by when it was found. [REPORT.md](REPORT.md) section 4 summarizes it.


Stage legend: D = drafting (Elephant), R = Goldfish review, A = author/delegate at a gate, I = implementation, T = tests/E2E.

## Fresh run, drafting (stage D/A)

| ID | Found by | What | Where recorded |
|---|---|---|---|
| E1 | admin-service Elephant | api_permissions BIGSERIAL vs B-01 UUID PKs | DEV-1 |
| E2 | admin-service Elephant (also orchestrator, first attempt) | PRD §5 database vs §6 schema; gateway reads public | AD-01, DEV-3 |
| E3 | admin-service Elephant | gateway's own V1 script vs Flyway creating the table; Flyway refuses non-empty schema without history -> baseline | AD-02 |
| E4 | admin-service Elephant | 12 routes vs 7 sidebar links; 3 URL-only pages | AD-03 |
| E5 | both Elephants | INACTIVE "terminal" vs re-activation; LOCKED undefined | AD-04, DEV-4, C-06 |
| E6 | admin-service Elephant | gateway unknowns: sync contract GW-1, restart GW-2, pattern syntax GW-3, realm-role delete GW-4, unowned rows GW-5, token fallback GW-6 | Open questions |
| E7 | admin-service Elephant | no-op stub breaks S2–S4 (only gateway creates realm roles) | AD-11 |
| E8 | admin-service Elephant (contradiction audit) | author batch: OQ-11 contradicts G8; OQ-13 contradicts G5 | H-017 |
| E9 | admin-service Elephant (pushback) | P1 last SUPER_ADMIN lock-out; P2 empty permission set on assigned role; P3 GLOBAL sync scope makes status stale; P4 check only added roles | AD-05..10 |
| E10 | admin-service Elephant | role delete + failed sync leaves nothing to re-sync | AD-12 |
| E11 | admin-service Elephant | stand-in must only delete roles it created (cce-managed tag) | AD-11 |
| E12 | admin-service Elephant (contradiction audit) | "never assignable" system role contradicts AD-07 | H-022 |
| U1 | admin-ui Elephant | no-bypass rule vs MSW-first development; test seam | TP-1, TP-2 |
| U2 | admin-ui Elephant | realm default vs required; Node 20 vs 22 vs 24; test:ci vs test:run | C-02..C-04 |
| U3 | admin-ui Elephant | 24 contract requirements on the service (AS-REQ-01..24) | AS-REQ list |
| U4 | admin-ui Elephant | gateway sample patterns /admin/users/* never match /admin/v1 | C-08 |
| U5 | admin-ui Elephant | public client token must carry the service's audience; ${origin}/auth default unreachable locally | AS-REQ-22 |
| U6 | admin-ui Elephant | e2e runs accumulate undeletable users/roles; credentials via env | AS-REQ-24 |
| O1 | orchestrator (author) | realm import must hold no credentials | G1 |
| O2 | orchestrator | audit of partial failures | G8 |
| O3 | orchestrator | offline check that stored rows match intended calls | G9 |
| O4 | orchestrator | "pass in CI" deviation | DEV-2 |
| O5 | orchestrator | browser E2E as a goal, not manual | UI Goal 9 |
| O6 | orchestrator | audit every sync attempt (stricter than Elephant's proposal) | G8, AD-06 |
| O7 | orchestrator | service-account realm role needed before first sync (bootstrap order) | OQ-6 record |

## Goldfish review (stage R), found by fresh reviewers, not by the Elephant or the author

| ID | Design | Severity | What | Report |
|---|---|---|---|---|
| R1 | admin-service | Blocker | Existing Keycloak users (every current cce-insights-ui user) have no local row: 403 on /me/menus; POST /users refuses their usernames; failed creates cannot be retried | CA-01 |
| R2 | admin-service | Blocker | Tests have no configuration: `./mvnw verify` fails on a clean checkout and reads the developer's .env elsewhere | CA-02 |
| R3 | admin-service | Blocker | Gateway test fixture "copied" from DDL the gateway doc does not contain (trigger/index only described in prose) | CA-03 |
| R4 | admin-service | Major | Audit-row/502 rules contradict each other across sections; G8 row counts wrong | CA-04 |
| R5 | admin-service | Major | First-deploy runbook fails when followed in order (restart before migration) | CA-05 |
| R6 | admin-service | Major | One global SUPER_ADMIN lock held across ~25 Keycloak calls; role diff computed before the lock | CA-06 |
| R7 | admin-service | Major | Service's own permission check (AD-17) drives matcher/cache/stand-in complexity; TTL snapshot makes S1 E2E flaky | CA-07 |
| R8 | admin-service | Major | Gateway doc contradicts itself on reload (line 8 vs 187); SYNCED presented as "in effect" | CA-08 |
| R9 | admin-service | Major | V1 would ALTER a gateway-owned table: ownership, shared flyway history, seeding before shape check | CA-10 |
| R10 | admin-service | Major | POST /roles silently takes over an existing Keycloak realm role | CA-11 |
| R11 | admin-service | Major | audit_log.entity_id NOT NULL loses the B-32 partial-failure row | CA-12 |
| R12 | admin-service | Major | Audit IP forgeable (first X-Forwarded-For trusted by default) | CA-14 |
| R13 | admin-ui | Major | ESLint layer rules overwrite each other in flat config: silently not enforced | A-17 |
| R14 | admin-ui | Major | playwright.config.ts fails lint, which fails the Docker verify stage | A-17 |
| R15 | admin-ui | Major | Step 1 exit check cannot pass; F1 file range wrong | A-16 |
| R16 | admin-ui | Major | 401 after a successful refresh triggers a sign-in redirect loop | A-07 |
| R17 | admin-ui | Major | Role-picker hint wrong (syncStatus is only a display hint); CUJ 4 precondition wrong | A-03 |
| R18 | admin-ui | Major | Dead link to a user that a USER_CREATE 502 never creates (from a backend self-contradiction) | A-04 |
| R19 | admin-ui | Blocker | No risks section (feature-spec standard) | A-01 |
| R20 | both | Process | Stage 8 triage: many earlier remedies would add mechanisms; simplify instead | both reports |

Review process notes: admin-ui Stage 9 synthesizer returned before its reviewers finished (main context compiled the report); admin-ui Stage 8 reviewer read the 1,653-line service design only in part.

## Implementation (stage I): DESIGN-GAPs reported by fresh implementation agents

| ID | Phase | Gap | Severity | Ruling |
|---|---|---|---|---|
| I1 | UI step 1 | index.css double quotes vs Prettier singleQuote | trivial | accept single quotes |
| I2 | svc phase 1 | Keycloak 26.4.7 password grant never sets auth_time (AD-05 assumption wrong for E2E tokens) | real, missed by 2 review rounds | AD-05 holds for browser sign-ins; E2E must not expect last_login_at from password grants; errata |
| I3 | svc phase 1 | imported test admin needs default-roles-cce for account console | real | accept; errata 9.2 |
| I4 | svc phase 1 | .env.example scope (VITE_* belong to UI) | wording | accept |
| I5 | svc phase 1 | health-check timings unspecified | detail | accept agent values |
| I6 | svc phase 1 | Keycloak DB user unspecified | detail | accept admin_service |
| I7 | svc phase 1 | token-claims.mjs .env path | detail | accept |
| I8 | svc phase 2 | names of PK/FK/CHECK constraints and trigger function unspecified | detail | accept conventions |
| I9-I19 | UI step 2 | 11 small gaps: unnamed codes/texts/labels, spinner slot, one item-59 vs error-section contradiction, undefined 'other messages' | minor | accept agent choices |
| I20-I23 | svc phase 3 | error-shape test lacks endpoints in this phase; field name for content-type and class-level errors; OpenAPI title text | minor | accept |
| I24-I35 | UI step 3 | 11 small gaps (texts, props, placement) + plan's method-style props fail its own lint rule | minor | accept |
| I36-I41 | UI step 4 | 6 small gaps: missing onDirtyChange prop (layer rule vs guard), texts/labels, refetch-failure case | minor | accept |
| I42-I45 | svc phase 4 | shade reduced pom not ignored; error summary format; failure-row after JSON; reactivateAsAdmin step names | minor | accept; orchestrator added .gitignore line |
| I46-I53 | UI step 5 | no navigation to /users/new in the design (real omission); resetPage race with React Router; page-vs-form contradiction; missing props/texts | 1 real, rest minor | accept |
| I54 | svc phase 5 | contract contradiction: 502 required for Keycloak failure on role create/delete but operations ROLE_CREATE/ROLE_DELETE removed in resolve; console unaware | real, cross-design, missed by reviews | accept code; errata both designs |
| I55 | svc phase 5 | Hibernate dirty-check UPDATE on append-only audit_log (JSON number type) | real runtime bug, not foreseeable in prose | fixed with @Immutable |
| I56-I58 | svc phase 5 | after-state name, menuIds order, icon length | minor | accept |
| I59-I65 | UI step 6 | 7 small gaps (texts, missing-entry case, defaults); test timeouts under load | minor | accept |
| I66 | svc phase 6 | design contradiction: commit-per-step rule vs one-transaction lock rule for 5.2-5.4 | real, missed by reviews | followed section 5 |
| I67-I71 | svc phase 6 | unnamed steps, link names, emailVerified, keycloakUserId, truncation place | minor | accept |
| I72-I73 | svc phase 8 | names for second entity per scenario; audit-row reads need DB access the plan does not list | minor | accept |
| I74-I75 | UI step 7 | test-data names; Docker check cannot reach a 127.0.0.1-bound container service as written | minor | accept |
