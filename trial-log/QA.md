# EGM questions and the answers given

Generated from [HISTORY.md](HISTORY.md) by `trial-log/tools/qa.py`. Do not edit by hand. In each session, the reply of one turn ends with EGM's question, and the input of the next turn is the answer. The answers were given by the orchestrating Claude session as the user's delegate.

## Session `d52e71e3`: Probe: does a headless session in this folder load EGM?

### Q1 · asked in H-001 (2026-11-10 12:43 UTC) · no question asked

**EGM asked:**

> (this reply asks no question)

## Session `126089df`: Draft admin-ui, turn 1: /egm:egm-draft on the PRD

### Q1 · asked in H-002 (2026-11-10 12:46 UTC) · **waiting for an answer**

**EGM asked:**

> **Gate:** Is this the right context for drafting the cce-admin-ui design?
> - **Start drafting.** Begin with Overview & Goals in `egm/admin-ui/design_draft.md`.
> - **Correct the context first.** Write in what to change: scope, inputs, author, or the conflicts above.

## Session `620c0b0a`: Draft admin-service, turn 1: /egm:egm-draft on the PRD

### Q1 · asked in H-003 (2026-11-10 12:47 UTC) · **waiting for an answer**

**EGM asked:**

> **Question:** Is this the right starting context, and may I start drafting the Overview and Goals?
>
> 1. Approve the context and start drafting Overview & Goals
> 2. Revise the context first (say what to add, remove or reclassify)

## Session `02eb6e8f`: Fresh draft admin-ui, turn 1: /egm:egm-draft on the PRD (no team docs)

### Q1 · asked in H-006 (2026-11-10 12:56 UTC) · answered in H-009

**EGM asked:**

> Should I start drafting Overview, Problem, Goals and Non-Goals into `egm/admin-ui/design_draft.md` on this basis?
>
> 1. Start drafting with this context
> 2. Correct or add to the context first

**Answer given:**

> 1. Start drafting with this context.
>
> My answers on the points you listed, so you can draft with them:
> 1. Auth flag: the console has no runtime bypass flag and no static-token fallback (PRD §6, F-01). How the tests get past initAuth() is a design question. Put it to me as an option gate in the Technical Plan.
> 2. Realm: follow the PRD. VITE_KEYCLOAK_REALM is required and has no default in code. A missing value is a start-up error, not a silent fallback. The local value comes from the admin-service design's M0.
> 3. Node: the reference build is the PRD's Docker image (node:20-alpine). The code must also build and test on this machine's Node 22.22. State both in the plan.
> 4. Script names: the PRD's names win (`test`, `test:ci`, `lint`, `build`, `dev`), because the PRD is the requirement. The template's names do not apply.
> 5. Network path: that is correct. Locally there is no gateway, so a 403 can come only from the service itself (for example, an unknown or inactive user). Gateway 403s are covered by tests with MSW.
> 6. Contract questions: list each one in the draft as a numbered requirement on the admin-service design. The admin-service session will receive that list.
> 7. Contract drift: agreed that it needs one place. Plan for a single TypeScript module of contract types that mirrors the admin-service design's contract section, with MSW handlers built only from those types. Link that section once it exists. Until the admin-service design is approved, mark every shape you use as "pending admin-service contract".

### Q2 · asked in H-009 (2026-11-10 12:59 UTC) · answered in H-011

**EGM asked:**

> Drafted Overview & Goals in `design_draft.md`. Please review. How would you like to proceed?
>
> 1. Approve Overview & Goals and proceed to next section
> 2. Request revisions to Overview & Goals (via inline comments or write-in)

**Answer given:**

> Approve Overview & Goals and proceed to the next section, after one change to Goals and Non-Goals:
>
> Do not make automated browser end-to-end tests a non-goal. Add a goal instead: CUJ 2 to CUJ 6 (scenarios S2 to S6) run as automated browser tests against the real local stack (the admin-service design's M0, plus the service on 8085). That includes signing in through the real Keycloak login page. Use Playwright as a dev-only dependency with its own script, `npm run e2e`. It is not part of `test:ci`, because it needs the stack running. List Playwright as an addition to the PRD's testing stack (PRD §6 lists Vitest, Testing Library and MSW), with the reason: the E2E milestone needs a browser driver to show that the console works against the real service, and a manual run gives no regression coverage. S1 stays out of scope, as you have it. The admin-service design checks `/me/menus` at API level.
>
> Your AS-REQ-01 to AS-REQ-22 list is clear. I will pass it to the admin-service session for its contract section, and bring its answers back to you.

### Q3 · asked in H-011 (2026-11-10 13:03 UTC) · answered in H-012

**EGM asked:**

> Which test seam should the plan use?
>
> 1. Swap the auth module in tests
> 2. Pass in the auth client
> 3. Mock `keycloak-js` itself

**Answer given:**

> 1. Swap the auth module in tests. Two conditions go with it:
> - The fake is typed against the real module: `satisfies typeof import('src/auth/keycloak')`, or an explicitly typed factory. A change to the module's exports then breaks the test build instead of passing silently.
> - `src/auth/keycloak.ts` gets its own test file with `keycloak-js` mocked and fake timers. That file covers what component tests cannot: initAuth() redirects before render (login-required, PKCE S256), the 60-second refresh calling updateToken(70), and the 401 refresh-once-then-login path. Only this file depends on keycloak-js's API.
> Reason: no test-only seam in production code (option 2 makes the "no bypass" guarantee depend on a lint rule), and only one file depends on keycloak-js details (option 3 makes every component test depend on them).
>
> Your AS-REQ-23 (a sync-failure switch that does not need a restart) and AS-REQ-24 (reset of test data, and test credentials from environment variables) go to the admin-service session with the rest of the list.

### Q4 · asked in H-012 (2026-11-10 13:04 UTC) · answered in H-014

**EGM asked:**

> Which approach should the plan use for development before the service exists?
>
> 1. MSW only in tests
> 2. MSW in the browser via `npm run dev:mock`
> 3. Separate mock server on port 8085

**Answer given:**

> 1. MSW only in tests.
>
> Reasons:
> - No mock code path can reach a production bundle, and the scripts stay the PRD's five plus `e2e`.
> - In this trial both tracks are implemented at the same time, and Goal 9's browser tests run against the real service. So the gap you describe (no click-through before the backend lands) is short, and it is closed by automated tests, not by a demo.
> - The F1 exit check "header shows /me" is shown with a component test against the MSW handler, and in the browser once the service runs.
> Write the F1–F5 exit checks in the plan so that each one says whether it is shown by a test (MSW) or in the browser against the real stack.

### Q5 · asked in H-014 (2026-11-10 13:05 UTC) · **waiting for an answer**

**EGM asked:**

> Drafted Technical Plan in `design_draft.md`. Please review. How would you like to proceed?
>
> 1. Approve Technical Plan and proceed to next section
> 2. Request revisions to Technical Plan (via inline comments or write-in)

## Session `d8132724`: Fresh draft admin-service, turn 1: /egm:egm-draft on the PRD (no team docs)

### Q1 · asked in H-007 (2026-11-10 12:56 UTC) · answered in H-008

**EGM asked:**

> **Question:** Is this the right starting context, and should I start drafting Overview & Goals in `egm/admin-service/design_draft.md`?
> - Confirm the context and start drafting Overview & Goals
> - Correct the context first (write in what to change)

**Answer given:**

> Confirm the context and start drafting Overview & Goals.
>
> On your list:
> 1. ID type: api_permissions keeps the gateway's exact shape, including `id BIGSERIAL`, because the table is a fixed constraint. B-01's UUID primary keys apply to the other six tables. Record this as a deviation from B-01 to raise with the team (PRD §11 asks that deviations be raised as doc changes).
> 2, 3, 4, 5, 7, 8, and the sync authentication in 6: put each to me as its own option gate when you reach it, as you proposed.
> 6. For the other gateway behaviour the inputs do not document (the sync endpoint's request and response, the restart needed before new permissions take effect, and the pattern syntax), write each one down as an open question for the gateway-service owners. Design the service so that the answers can change in configuration, not in code, wherever that is reasonable.

### Q2 · asked in H-008 (2026-11-10 12:59 UTC) · answered in H-010

**EGM asked:**

> **Question:** I've drafted Overview & Goals in `design_draft.md`. Please review. How would you like to proceed?
> - Approve Overview & Goals and proceed to the Technical Plan
> - Request revisions to Overview & Goals (inline comments or write-in)

**Answer given:**

> Approve Overview & Goals and proceed to the Technical Plan, after applying these four updates to the section:
>
> 1. G1: the realm import must hold no credentials. The confidential client's secret and the test administrator's password are placeholders, filled from environment variables when the container starts. Commit an example env file with placeholder values. The real env file is git-ignored (PRD §11, B-13).
> 2. G8: also say what is audited when a call fails part-way. The 502 path writes one audit row that records the partial state and the Keycloak user id (B-32). Decide in the Technical Plan how the result of the sync after a permission change is recorded, so that "one row per mutation" (B-50) still holds and can be tested.
> 3. G9: add an offline check of the hard control. For each permission row the scenarios write, a test checks the intended (method, path) calls against the stored rows, using the matching rules assumed under GW-3. A pattern that would not allow the intended call is then caught locally. Say plainly that this checks the rows, not the gateway.
> 4. Deviations: add DEV-2 for "pass in CI" in the definition of done (PRD §11). No CI pipeline file is delivered. `mvn verify` and the image build run locally instead.

### Q3 · asked in H-010 (2026-11-10 13:00 UTC) · answered in H-013

**EGM asked:**

> Which option should the design use for OQ-1?
> - A: all tables in `public`
> - B: all tables in schema `admin_service`, with a gateway configuration change
> - C: six tables in `admin_service`, `api_permissions` in `public`

**Answer given:**

> A: all tables in `public`.
>
> Reasons:
> - The gateway needs no change. B makes the module depend on a configuration change in a deployment this team does not own, and the failure (the gateway loads no permissions) blocks every call.
> - C splits one module across two schemas only to satisfy PRD §6's wording. PRD §5 and the gateway doc contradict that wording, and the gateway doc's own DDL puts the table in the default schema.
> - The `admin_service` database belongs to this module, so the risk of clashing names in `public` is low.
> Record it as DEV-3, a doc change to raise with the team: PRD §6 "schema `admin_service`" is read as the database `admin_service`, with all tables in `public`.
>
> Before you write the contract section: the console design has written 22 requirements on this design's contract. They are in egm/admin-ui/design_draft.md under "Requirements on the admin-service design" (AS-REQ-01 to AS-REQ-22). Read them, and answer each one in your contract section, citing its ID, so that the console session can check the answers. Several of them overlap your own open questions (AS-REQ-07 with OQ-4, AS-REQ-22 with M0). Where one needs an author decision, bring it to me as a gate as usual.

### Q4 · asked in H-013 (2026-11-10 13:05 UTC) · answered in H-015

**EGM asked:**

> Which option should the design use for OQ-2?
> - A: create if missing, then check the shape
> - B: the gateway's script creates it, and the service only checks
> - C: Flyway owns it, and existing environments drop and recreate it

**Answer given:**

> A: create the table if it is missing, then check its shape. Two additions:
> - The shape check also covers the gateway's unique constraint on (permission_name, http_method, uri_pattern) and the two indexes, not only the column names and types. Replace semantics and the duplicate-row error both depend on that constraint.
> - Leftover rows that no role owns, such as the gateway doc's samples, are not deleted, because the gateway may depend on them. The bootstrap steps for a new environment include a read-only SQL query that lists `permission_name` values with no matching row in `roles`, so that an operator can review them. Record this risk under the open questions for the gateway owners.
> Reason: B-01 holds on an empty database, an environment where the gateway's script already ran keeps working, and no manual data migration is needed. B makes every new environment depend on a manual step. C risks a window in which the gateway loads no permissions.
>
> The choices you made yourself in 4.1–4.3 are accepted. Two items in the console's requirement list were added after you first read it, so check that they are covered:
> - AS-REQ-23: a way to make the local gateway sync fail and then succeed again without restarting the service, so that a browser test can run S6.
> - AS-REQ-24: how to reset the local database and realm to a clean state, and the environment variables that pass the test admin's username and password to tests. No committed file holds them.
> Both are M0 or OQ-5 matters.

### Q5 · asked in H-015 (2026-11-10 13:06 UTC) · answered in H-016

**EGM asked:**

> Which option should the design use for OQ-3?
> - A: seed as inactive `NAV`
> - B: seed as active `NAV`
> - C: add a `ROUTE` type

**Answer given:**

> B: seed as active `NAV`.
>
> Reasons:
> - Under the new model a menu only controls what a role sees (PRD §4, soft control), and a role sees exactly the menus it is granted. "Granted means shown" is the one rule a console needs.
> - A gives `is_active` two meanings ("retired" and "routed but hidden").
> - C adds a type that PRD §4 does not define, and every console has to filter on it.
> - The decision to keep these pages out of the sidebar belonged to the old sidebar, which was the same for everyone. Under per-role menus an administrator decides per role.
> Seed sort_order so that the current 7 links come first, in today's sidebar order, then Practitioners, Intelligence and Exports. A role granted all ten then sees today's order with three links added at the end. The seed grants no menus to any role. SUPER_ADMIN is the admin console's role, and console roles are configured through the API.

### Q6 · asked in H-016 (2026-11-10 13:07 UTC) · **waiting for an answer**

**EGM asked:**

> Which option should the design use for OQ-4?
> - A: two statuses, either way
> - B: three statuses, `INACTIVE` terminal
> - C: three statuses, every change allowed

