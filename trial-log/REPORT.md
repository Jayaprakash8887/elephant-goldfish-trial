# Can the EGM plugin carry full feature development? A trial on the CCE v2 Admin Module

**Trial date:** 2026-11-10, from 12:16 to about 18:30 UTC.

**Plugin:** Google's Elephant-Goldfish Model (EGM) 0.1.1, used in Claude Code 2.1.319.

**Feature built:** the CCE v2 admin module, from its PRD. This covers the backend
`cce-admin-service` (Spring Boot) and the admin console `cce-admin-ui` (React).

**Who did the work:** Claude orchestrated the trial at the user's request. The user delegated
every EGM decision to Claude.

## Contents

- [1. Short answer](#1-short-answer)
- [2. What was built](#2-what-was-built)
- [3. How it was done, step by step](#3-how-it-was-done-step-by-step)
- [4. What EGM caught, and what it missed](#4-what-egm-caught-and-what-it-missed)
- [5. Challenges and how they were resolved](#5-challenges-and-how-they-were-resolved)
- [6. Cost and time](#6-cost-and-time)
- [7. Recommendations for a team adopting EGM](#7-recommendations-for-a-team-adopting-egm)
- [8. Where the evidence is](#8-where-the-evidence-is)

## 1. Short answer

**Yes, with a workflow around it.** EGM took this feature from a PRD to two reviewed designs. Fresh
coding agents then built working, tested code from those designs alone. The checks passed:

- **Backend:** 278 unit and integration tests, plus all six PRD scenarios (S1 to S6) run end to end
  against real Postgres and Keycloak.
- **Console:** 403 unit tests at 99.7% coverage, plus browser tests of S2 to S6 that sign in
  through the real Keycloak login page.
- **Fixes after design:** no service code needed fixing once the end-to-end phase started.

EGM does not do full feature development on its own. It covers design and review. The trial
had to add these pieces around it:

- **A way to drive it.** Only a person can start EGM's skills, by typing a command. The trial
  started them in headless Claude Code sessions instead (C-03, C-04).
- **Coordination between designs.** EGM works on one design at a time. Two designs that share
  an API needed a shared decision record and a single owner for the contract (C-12).
- **Verified facts.** These included exact dependency versions (C-13) and a working permission
  setup for headless runs (C-11).
- **Implementation and verification.** EGM stops at the design. Fresh agents built it phase by
  phase, and the build's exit checks did the verifying (C-10).
- **Someone to answer about 60 approval gates.** Here that was Claude acting as the user's
  delegate. A human author would normally do this (C-09).

Where EGM paid off, and where it fell short:

- **Before any code.** The Elephant's stress-testing and the Goldfish reviews found real
  problems, which section 4 lists. The most important one: every existing user of
  `cce-insights-ui` would have been locked out (403 on every call) by the first design.
- **Fewer reviews.** Review rounds on an implementation plan written as code-level prose did not
  converge, because each round's fixes created new findings (C-17). The best use of EGM is a
  thorough review of the feature design, then letting the compiler and the tests check the code
  level.

## 2. What was built

| Part | What it is | Checks |
|---|---|---|
| [`cce-admin-service/`](../cce-admin-service/) | Spring Boot 3.5.16 on Java 21. It has a Flyway schema with 7 tables, matching the gateway's fixed `api_permissions` shape. It provisions users and roles in Keycloak, calls the gateway sync, writes an append-only audit log, and serves a role-aware `/me/menus`. It also checks permissions itself, on top of the gateway (defence in depth). | `./mvnw verify`: 278 tests. `./mvnw -Pe2e verify`: 7 end-to-end tests (S1 to S6, plus a live check that the service refuses a call the role is not permitted). |
| `cce-admin-service/gateway-stub/` | A local stand-in for the gateway's sync endpoint. It creates realm roles from `api_permissions`, and it has a switch that makes it fail, for S6. | 16 tests. |
| `cce-admin-service/docker-compose.yml` | The local environment from milestone M0: Postgres 16.15, Keycloak 26.4.7 with an imported realm and no secrets in the file, Mailpit, the stand-in, and the service. | Every service is healthy. |
| [`cce-admin-ui/`](../cce-admin-ui/) | React 18 and Vite 6. It signs in through Keycloak with PKCE, and has no bypass flag. It uses a typed API client and TanStack Query. It has Users, Roles (with Menus and Permissions tabs) and Menus screens, each with a distinct sync-failure state. | `npm run lint`, `build` and `test:ci`: 403 tests, 99.7% coverage. `npm run e2e`: 6 Playwright tests against the real stack. The Docker image builds and its verify stage runs on Node 20. |
| [`docs/designs/`](../docs/designs/) | The two approved EGM designs (2,077 and 2,309 lines), the shared author decisions, and the sitemap. | Two Goldfish review rounds each. Drift report at the end. |

In all, the implementation is about 27,700 lines of Java, TypeScript and SQL, in 308 tracked
files.

## 3. How it was done, step by step

Each step is a git tag. To see a file as it was at the end of a step, run
`git show step-NN:<path>`.

| Step | What happened | EGM skill | Evidence |
|---|---|---|---|
| 00 Setup | The repo was read before installing anything. The plugin was installed for this project only. The inputs were copied into `context/`. The recording tools were written. | none | [JOURNAL](JOURNAL.md#step-00--setup), C-01, C-02, C-08 |
| 01 First attempt (stopped) | Drafting started with the team's existing design docs as inputs. The user then asked for the designs to be created fresh, so this attempt was stopped and kept as a record. | `egm-draft` | [JOURNAL](JOURNAL.md#step-01--first-drafting-attempt-using-the-teams-design-docs-stopped), H-001 to H-005 |
| 02 Drafting | The two designs were drafted in parallel from the PRD, in 47 minutes and 28 EGM turns. There were about 30 option gates and section approvals, and 2 contradiction stops. | `egm-draft` | [JOURNAL](JOURNAL.md#step-02--both-designs-drafted-fresh-from-the-prd-egm-draft), [QA](QA.md), H-006 to H-033 |
| 03 Review and resolve | Round 1 was a full five-stage review of each design: 145 findings merged into 33 actions for the service, and 30 for the console. Round 2 re-ran Stage 9 on each. Each round's findings were then resolved in the original drafting session. | `egm-review`, `egm-resolve` | [JOURNAL](JOURNAL.md#step-03--goldfish-review-and-resolution-egm-review-egm-resolve-two-rounds), `egm/*/`, H-034 to H-046 |
| 04 Implementation | 15 fresh agents built one phase each: 8 for the backend and 7 for the console. Each agent got only the design and its phase. The orchestrator re-ran every exit check and committed after each phase. | none (EGM has no implementation step) | [JOURNAL](JOURNAL.md#step-04--implementation-by-fresh-agents-one-phase-at-a-time), H-047 to H-061 |
| 05 Drift report | EGM compared the code with the designs. | `egm-drift-report` | `egm/drift/`, H-062 |

How each turn was recorded:

- **[HISTORY.md](HISTORY.md):** every message sent to EGM and its exact reply, in order.
- **[QA.md](QA.md):** each question EGM asked, paired with the answer it was given.
- **[timeline.md](timeline.md):** generated from the transcripts. It shows which reviewer
  subagents ran, which files they read, and the token use.

## 4. What EGM caught, and what it missed

### Found by the Elephant, while drafting

The author had not raised any of these:

- **Schema name:** the gateway reads tables from the default `public` schema, but the PRD says
  "schema `admin_service`". Tables placed there would be invisible to the gateway (AD-01).
- **Local gateway:** a do-nothing stand-in for the gateway would break S2 to S4 locally, because
  only the gateway creates realm roles (AD-11).
- **Flyway and an existing table:** Flyway refuses to migrate a non-empty schema that has no
  history table. That is the state wherever the gateway's own script has already run (AD-02).
- **Administrator lock-out:** the last `SUPER_ADMIN` could deactivate themselves, or have their
  role removed.
- **Empty permission set:** emptying the permissions of a role that users hold leaves a silent
  mismatch in Keycloak.
- **A self-contradictory statement in the PRD:** it calls `INACTIVE` "terminal", while B-34 and
  S5 describe reactivating users.
- **Two contradictions in the author's own decisions:** EGM's contradiction check stopped two
  turns before anything was written (H-017, H-022).

### Found by the Goldfish reviewers

Fresh context, the design text only:

- **Blocker: existing users locked out.** Every existing Keycloak user (all current
  `cce-insights-ui` users) had no local row, so every call they made would get a 403. A failed
  user creation also could not be retried. **Nobody had caught this before the review.** The fix
  links an existing account when its username and email both match (CA-01).
- **Blocker: build fails on a clean checkout.** Tests had no configuration, so `./mvnw verify`
  would fail on a fresh checkout.
- **Contradictions inside the design.** These included contradictory audit rules, a runbook that
  fails when followed in order, a forgeable audit IP address, and lint rules that silently
  override each other in ESLint flat config.
- **The Stage 8 reviewer's pushback.** It argued for removing mechanisms instead of adding them.
  That kept the design from growing with each round.

### Missed by both review rounds, found during implementation

About 72 `DESIGN-GAP` notes came up, and most of them were unnamed texts, labels and
identifiers. These are the ones that mattered:

1. **`auth_time`:** Keycloak 26.4.7 never puts `auth_time` in password-grant tokens. The design
   assumed it would. The fix was to ask for less in the E2E checks.
2. **Two operation names:** the design required a 502 error on role create and delete, but its
   own resolution had removed the two operation names. The console does not know them.
3. **"Commit each step" vs "one locked transaction":** the service plan says both for the user
   flows.
4. **No route to create a user:** the console design never says how to reach the create-user
   page.
5. **Prop style vs the plan's own lint rule:** the console plan's method-style props fail its own
   lint rule.
6. **Hibernate and the audit log:** a runtime problem no prose review could see. Hibernate
   re-read JSON as a different number type and tried to UPDATE the append-only `audit_log`.
7. **`npm audit`:** 14 vulnerabilities at the pinned versions (2 critical, 1 high). The fixes need
   major-version upgrades.

Every one of these was found by compiling, running or testing the code. None of them broke the
build. The agents resolved each with the smallest change and reported it.

## 5. Challenges and how they were resolved

The full list is in [CHALLENGES.md](CHALLENGES.md). These are the ones a team will meet:

| # | Challenge | Resolution |
|---|---|---|
| C-01 | Claude Code versions older than 2.1.271 silently break reviewer isolation. | The trial used the VS Code extension's 2.1.319. Check the version first. |
| C-02, C-11 | The reviewers read files outside the project, the agent may not grant itself access, and headless runs ignore project permission rules until the folder has been trusted. | The user added four read rules. Headless runs pass the same rules as launch flags. |
| C-03, C-04, C-05 | Only a person can start EGM's skills, plugin agents load only when a session starts, and EGM allows one question per turn. | EGM is driven through headless `claude -p` sessions continued with `--resume`. Every turn is recorded. |
| C-06, C-07 | The same artifact folder for every design, and a PRD with 12 milestones. | One design per repository, with its own artifact folder. |
| C-12 | Two designs depend on each other's API. | One design owns the contract, and both read a shared author-decisions file. |
| C-13 | A headless Elephant cannot look up current versions. | The author checked the versions and recorded them in D-05. |
| C-16, C-17 | 145 findings, stages that disagree on direction, and fixes that create new findings. | Follow the Stage 8 reviewer's triage, run one round of Stage 9, then let the build checks take over. |
| C-18 | The plan's 5-hour usage limit stopped two sessions mid-turn. | Each session was resumed with a check-and-finish prompt. Commit after every turn. |

## 6. Cost and time

| Phase | Wall clock | Estimated cost (list price) |
|---|---|---|
| Setup and first attempt | about 40 min | about $2 |
| Drafting, 2 designs | 47 min | $118 |
| Reviews and resolves, 2 rounds | about 2 h 20 min, including about 40 min stopped at the usage limit | $189 |
| Implementation, 15 agents | about 1 h 55 min | about 3.3 M subagent tokens, which is not priced in the run records |
| **EGM turns in total** | | **$309, over 44 headless turns** |

Notes on the figures:

- The orchestrating session itself was about 5 h 47 min long.
- The costs are Claude Code's estimates at list price. On a subscription they are a share of the
  usage limit, not a bill.
- EGM's FAQ says to "compare total time, useful findings, and rework". This trial has no
  baseline built without EGM, so it cannot show a speed-up. It can show what EGM found before any
  code existed (section 4).

## 7. Recommendations for a team adopting EGM

1. **Use EGM for the decisions, and let the tests check the code.** Review the feature design in
   depth (Stages 5 to 8). Keep the implementation plan at the level of files, contracts,
   behaviour and exit checks. Run Stage 9 once.
2. **Size one design to one repository or one pull request.** Give each design its own artifact
   folder.
3. **When designs share an interface, name its owner first.** Keep the shared decisions in one
   file that every design links to.
4. **Put a human at the gates.** EGM's value comes from someone with context answering its option
   gates and arguing with its pushback. When an agent answers them, as here, the document quality
   holds up, but the human check that EGM is built around is gone.
5. **Prepare the environment once:**
   - Use Claude Code 2.1.271 or later.
   - Commit the four read rules for the plugin's folders.
   - Trust the folder in the terminal CLI before any headless run.
   - Give the Elephant a list of verified versions.
6. **Implement with fresh agents, one phase at a time.** Have them mark `DESIGN-GAP`s instead of
   guessing, re-run each exit check yourself, and commit after every phase. Then run the drift
   report and update the designs with what the code taught you.
7. **Plan around the usage limit.** Run fewer sessions in parallel, or use API billing for long
   unattended runs. Never trust a status line written in a turn that was interrupted.

## 8. Where the evidence is

- **[JOURNAL.md](JOURNAL.md):** one entry per step.
- **[HISTORY.md](HISTORY.md):** 61 entries with the exact inputs and outputs.
- **[QA.md](QA.md):** 44 EGM questions with the answers given.
- **[CHALLENGES.md](CHALLENGES.md):** 18 challenges.
- **[FINDINGS.md](FINDINGS.md):** every finding, by stage and with its ID (E, U, O, R, I).
- **[timeline.md](timeline.md):** generated from the transcripts.
- **`egm/admin-service/`, `egm/admin-ui/`:** EGM's stage reports, consolidated reports and
  resolution reports.
- **`egm/drift/`:** the drift report.
- **Git tags `step-00` to `step-05`.**
- **The `DESIGN-GAP:` comments in the code:**
  `grep -rn DESIGN-GAP cce-admin-service/src cce-admin-ui/src`.
