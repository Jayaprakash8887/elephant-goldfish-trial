# Trial journal: EGM on the CCE v2 Admin Module

One entry per step, oldest first. Each entry ends with the git tag that snapshots the folder at
the end of the step. `git show step-NN:<path>` shows a file as it was then.

Related files:

- [HISTORY.md](HISTORY.md): every input sent to EGM and its reply, in order.
- [timeline.md](timeline.md): generated from the session transcripts (subagents, files, tokens).
- [REPORT.md](REPORT.md): the write-up, added at the end.

What the trial measures. EGM's FAQ says to "compare total time, useful findings, and rework on
your own tasks", so each entry records:

- time and token cost;
- review findings: how many, how severe, how many were accepted, and how many were wrong or noise;
- rework: problems found during implementation that the design missed;
- friction: permission prompts, blocked actions, and places where EGM's process did not fit.

## Step 00 · Setup

- **When:** 2026-11-10, from 12:16 UTC to the `step-00` commit. Session `f5334475`, Claude Code
  2.1.319 (VS Code extension).
- **Asked by the user:** try EGM for developing the attached PRD and record the steps. Later
  messages: run the whole development with the plugin, document it step by step, and keep a
  history of inputs and outputs.
- **What was done:**
  1. The MarkTechPost article returned HTTP 403. A web search found the source,
     `google/elephant-goldfish-model`. The repo was cloned at commit `6d2af49` (v0.1.1,
     2026-11-09) and read: README, Claude Code guide, workflow guide, FAQ, the draft, review and
     resolve skills, and the document standards.
  2. Checked the plugin's Claude Code package before installing it: Markdown skills, reviewer
     personas and JSON manifests only. No hooks, MCP servers or scripts.
  3. Installed it with project scope using the extension's bundled CLI:
     `claude plugin marketplace add google/elephant-goldfish-model --scope project` and
     `claude plugin install egm@egm --scope project`. This is recorded in
     `.claude/settings.json`. `claude plugin validate` passed. The 8 reviewer agents declare
     `tools: [Read, Grep, Glob]` and `omitClaudeMd: true`.
  4. Found the team's design docs in the MedLabs workspace: `cce-admin-service/docs`,
     `cce-admin-ui/docs`, gateway's `DATABASE_PERMISSIONS_MIGRATION.md`, and the `cce-insights-ui`
     template docs. Copied them and the PRD into `context/`, with sources and checksums in
     `context/PROVENANCE.md`.
  5. Wrote a Markdown copy of the PRD and checked it against `pdftotext` output. Every word
     matches, as do all 55 requirement and milestone IDs and all 30 error and role codes. Only the
     "Contents" heading was left out.
  6. Wrote `CLAUDE.md` (project context for the Elephant), `docs/designs/SITEMAP.md`,
     `.gitignore`, and two recording tools. `trial-log/tools/timeline.py` reads the transcripts.
     `trial-log/tools/egm.py` runs EGM turns headless and writes HISTORY.md.
- **Decisions:**
  - By the user: use EGM, record every step, and have Claude run the whole development.
  - By Claude, all reversible:
    - Install with project scope.
    - Work in this folder, not in the MedLabs repos.
    - Copy the inputs instead of linking them. Reviewers only see linked files inside this
      folder, and copies keep the trial reproducible.
    - Give each design its own EGM folder, `egm/<design>/`.
    - Use git tags as step snapshots.
- **Problems and friction:**
  - The terminal `claude` on PATH is 2.1.72. EGM needs 2.1.271 or later for verified reviewer
    isolation. The VS Code extension bundles 2.1.319, so the trial uses that binary.
  - Auto mode blocked Claude from adding read rules for the plugin cache to
    `.claude/settings.json` ("Self-Modification"). The user added the four
    `Read(~/.claude/plugins/cache/egm/egm/*/…/**)` rules by hand.
  - The gateway doc in its source repo has a default database password in plain text. The copy
    redacts it in 4 places. It should be fixed in gateway-service.
  - EGM's skills cannot be started by the model (`disable-model-invocation: true`). Also, this
    session started before the plugin was installed, so the plugin's reviewer agents are not
    registered in it. The trial therefore drives EGM in headless sessions (`claude -p`, with
    `--resume` for later turns), and `egm.py` records each turn.
- **Cost:** session `f5334475` up to this tag. See timeline.md.
- **Next:** check that a headless session sees EGM's skills and reviewers, then draft the first
  design.
- **Snapshot:** tag `step-00`

## Step 01 · First drafting attempt, using the team's design docs (stopped)

- **When:** 2026-11-10, 12:43–12:58 UTC. Orchestrating session `f5334475`. EGM sessions:
  `d52e71e3` (probe), `126089df` (admin-ui) and `620c0b0a` (admin-service), all headless,
  Claude Code 2.1.319, Opus 5.5, effort high.
- **What was done:**
  1. Probe (H-001). A new headless session in this folder registers all ten `egm:egm-*` agents.
     EGM's skills are hidden from the model, as expected for skills only a person may start.
     `AskUserQuestion` is not available in headless runs.
  2. Started `/egm:egm-draft context/prd/CCE_Admin_Module_PRD.md` in two headless sessions at
     once, one per design, each with its own artifact folder (H-002, H-003). The transcript shows
     that the slash command started EGM's skill.
  3. Both Elephants read the PRD and the team's docs and stopped at EGM's first gate, which asks
     the author to confirm the context. The admin-service session wrote a header, a table of
     inputs and a register of 9 conflicts and 15 gaps to `egm/admin-service/design_draft.md`.
     The admin-ui session reported about 25 conflicts and gaps in its reply.
  4. Found the cause of the refused plugin reads and worked around it (CHALLENGES C-11).
  5. Created `docs/designs/author-decisions.md` (D-01 to D-04) for decisions both designs share
     (C-12), and sent turn 2 to both sessions (H-004, H-005).
  6. Added `trial-log/QA.md`, which pairs each EGM question with its answer, at the user's
     request.
- **Why it stopped:** at about 12:58 UTC the user asked to remove the team's existing
  admin-service and admin-ui design docs from `context/`, so that EGM creates everything fresh
  from the PRD. Both sessions had already read those docs. They were stopped before replying to
  turn 2 and will not be continued.
- **What EGM found in this attempt:** before drafting anything, the two Elephants listed
  conflicts in the inputs that a reader could easily miss:
  - The `api_permissions` shape in the data dictionary (UUID ids, wider columns) does not match
    the gateway's real table (BIGSERIAL, narrower columns, a unique key).
  - PRD §5 says "database `admin_service`", while PRD §6 and the data dictionary say "schema
    `admin_service`". The gateway queries without a schema name, so tables in another schema
    would be invisible to it.
  - A gateway stub that does nothing would break S2 and S4 locally, because only the gateway
    creates realm roles in Keycloak. The orchestrator had not noticed this.
  - `INACTIVE` is "terminal" in PRD §2 but can be re-activated in B-34 and S5. The orchestrator
    had not noticed this either.
  - Gaps in the contract: menu keys versus ids, the shape of the B-41 warning, and missing
    response fields.

  The orchestrator had found the first two conflicts on its own while reading the inputs.
- **Decisions:**
  - By the user: design from the PRD alone, and remove the team's admin-service and admin-ui
    docs from the inputs.
  - By Claude:
    - Keep the gateway contract doc and the cce-insights-ui docs. They describe existing systems
      the module must fit, not designs to create.
    - Keep this attempt in the record as tag `step-01`.
    - Restart the drafting in new sessions.
- **Cost:** about $2.39 estimated for the three EGM sessions (H-001 $0.15, H-002 $1.06, H-003
  $1.18), plus the stopped turn-2 runs, which recorded no cost. Token counts are in timeline.md.
- **Snapshot:** tag `step-01`, with the inputs and the partial draft as they were when the
  attempt stopped.

## Step 02 · Both designs drafted fresh from the PRD (`/egm:egm-draft`)

- **When:** 2026-11-10, 12:56–13:43 UTC. That is 47 minutes of wall-clock time for both designs,
  drafted in parallel. Sessions `d8132724` (admin-service, 17 turns) and `02eb6e8f` (admin-ui, 11
  turns), headless, Claude Code 2.1.319, Opus 5.5, effort high.
- **Inputs:** the PRD, the gateway's `api_permissions` doc, the cce-insights-ui docs, and
  `docs/designs/author-decisions.md`. That file held D-01 (the service design owns the contract)
  and D-03 (it defines the local environment) at the start, and D-05 (exact versions) was added
  during the step.
- **What happened:** each Elephant followed EGM's draft skill in order:
  1. confirmed the context, listing the conflicts and gaps in the inputs;
  2. drafted Overview & Goals;
  3. drafted the Technical Plan, with option gates for each design choice;
  4. drafted Alternatives Considered;
  5. drafted the Detailed Implementation;
  6. finalized, which reconciles the draft against the PRD, records deviations and saves to
     `docs/designs/` with a sitemap row.
- **Gates answered by Claude as the user's delegate:** all 31 questions and answers are in
  [QA.md](QA.md).
  - **admin-service:**
    - 11 option gates: OQ-1 to OQ-10, plus a follow-up on keeping the service-account role
      from people.
    - 1 batch of six smaller decisions.
    - 5 section approvals.
    - 2 contradiction stops, at H-017 and H-022. The Elephant refused to write until the author
      settled a contradiction with approved goals.
  - **admin-ui:**
    - 2 option gates: TP-1, the test seam for sign-in, and TP-2, development before the
      service exists.
    - 5 section approvals.
    - 1 finalization contradiction: the folder layout.
    - 1 hold, which the author imposed until the service contract was complete.
- **Outputs:**
  - [`docs/designs/cce-admin-service.md`](../docs/designs/cce-admin-service.md), 1,653 lines.
    It holds 17 author decisions (AD-01 to AD-17), 6 deviations from the PRD (DEV-1 to DEV-6),
    6 questions for the gateway owners (GW-1 to GW-6), the full API contract, the data dictionary
    and seed, the local environment, and an eight-phase build plan.
  - [`docs/designs/cce-admin-ui.md`](../docs/designs/cce-admin-ui.md), 117 KB. It answers all 24
    of its contract requirements against the service design, records 1 deviation (UI-DEV-1), and
    lists 104 files in its build plan.
- **What EGM caught while drafting** (beyond what the author raised):
  - Flyway refuses to migrate a non-empty `public` schema that has no history table, which is the
    state wherever the gateway's script already ran.
  - P1: the last `SUPER_ADMIN` could be locked out.
  - P2: emptying the permissions of a role that users hold leaves those users with a silent
    Keycloak mismatch.
  - P3: a GLOBAL sync scope makes other roles' sync status stale.
  - P4: only roles being added need the Keycloak check.
  - A role delete followed by a failed sync leaves nothing to re-sync.
  - The stand-in gateway must delete only the realm roles it created itself.
  - The configurable list of allowed methods was not exposed to the console. The console design
    found this as CQ-1.
  - `useBlocker` needs a data router.

  Two contradictions in the author's own batch of decisions were caught before anything was
  written. The full list is in REPORT §6.
- **Problems and friction:** C-12 (the designs depend on each other), C-13 (versions cannot be
  looked up headless), C-14 (one question per turn). See CHALLENGES.md.
- **Cost:** estimated at list price, $71.32 for admin-service and $46.65 for admin-ui
  ($117.97 in total, 246 model turns). Token counts are in timeline.md.
- **Snapshot:** tag `step-02`.

## Step 03 · Goldfish review and resolution (`/egm:egm-review`, `/egm:egm-resolve`), two rounds

- **When:** 2026-11-10, 13:43–16:05 UTC. That includes about 40 minutes stopped at the usage
  limit (C-18).
- **Round 1, full five-stage review** (uninterrupted preset, fresh headless sessions `8a09292b`
  and `d157201e`). Each design took about 37 minutes and 14 reviewer subagents of the plugin's
  own types (`egm:egm-zoom-out`, `-comprehension`, `-design`, `-structural`, `-style`,
  `-implementation-readiness`, `-codebase-details`, `-inventive-catalyst`, `-synthesizer`).
  - admin-service: 145 stage findings, merged into 33 actions (3 Blockers, 24 Majors, 6 Nits).
  - admin-ui: 30 actions (1 Blocker, 24 Majors, 5 groups of Nits). The console's contract
    mapping was confirmed correct.
- **Author triage:** the stages disagreed on direction (C-16). The author followed Stage 8, which
  favoured removing mechanisms over adding them, and explicitly decided the five reopened
  decisions:
  - AD-12: no sync on role delete.
  - AD-16: role create returns 201 with a warning when its sync fails.
  - AD-15: the bootstrapper does not sync.
  - AD-17: a minimal permission check that uses AntPathMatcher, with no cache.
  - AD-06: only the role that was synced is marked.
  - AD-02: the table is created only when it is absent, and never altered.

  For Blocker CA-01 (existing Keycloak users locked out), the service now links an existing
  account on create.
- **Resolve, round 1** (in each Elephant session): 39 fixes for admin-service and 36 for
  admin-ui, with nothing contested. The console design was then cross-checked against the
  resolved contract (H-040).
- **Round 2, Stage 9 only** (sessions `213e827e` and `c7d68ef0`):
  - admin-service: 1 Blocker, 26 Majors, 36 Nits. 18 findings re-opened, 6 of them caused by
    round-1 fix text.
  - admin-ui: 0 Blockers, 11 Majors, 23 Nits.

  The review did not converge (C-17). The author resolved round 2 once (66 and 34 fixes). The
  usage limit interrupted both resolves, and they were recovered by resuming each session
  (C-18).
- **Outputs:**
  - The resolved designs, `docs/designs/cce-admin-service.md` (2,077 lines) and
    `docs/designs/cce-admin-ui.md` (2,309 lines).
  - Stage reports, consolidated reports and `goldfish_review_report*.md` in `egm/<design>/`.
- **Decisions by the author:** see [QA.md](QA.md) and the resolve prompts (H-036, H-037, H-043,
  H-044). A third review round was not run. The implementation's exit checks take over (C-17).
- **Cost:** about $189 estimated at list price, for 2 full reviews, 2 Stage 9 re-reviews, 6
  resolve turns, 1 cross-check and 2 recovery turns.
- **Snapshot:** tag `step-03`.

## Step 04 · Implementation by fresh agents, one phase at a time

- **When:** 2026-11-10, 16:10–18:03 UTC (about 1 hour 55 minutes). The backend and console tracks
  ran in parallel.
- **How:** each phase was given to a new general-purpose agent (the Agent tool). The agent
  received only the design document, its phase number and a fixed brief
  (`scratchpad/prompts/impl-template.txt`), with these rules:
  - build only what the phase lists;
  - mark any point the design does not settle with a `DESIGN-GAP:` comment and report it;
  - never commit;
  - run the phase's exit check until it passes.

  After each phase, the orchestrator re-ran the exit check itself, logged the report in
  HISTORY.md (H-047 to H-061), and committed.
- **Backend (cce-admin-service), 8 phases:**
  - M0 stack: Postgres, Keycloak 26.4.7, Mailpit, the gateway stand-in and the service.
  - 278 unit, migration, web and service tests.
  - 7 API-level E2E tests (S1 to S6 and the live hard-control check), passing against the real
    stack.
  - 16 stand-in tests.
- **Console (cce-admin-ui), 7 steps:**
  - 403 unit and component tests at 99.7% coverage.
  - 6 Playwright browser tests (sign-in through the real Keycloak login page, then CUJ 2 to 6
    for S2 to S6), passing against the real stack.
  - The Docker verify stage passes on Node 20, and the image builds.
- **DESIGN-GAPs:** about 72 across 15 phases. Most were small details the design did not name:
  texts, labels, constraint names, step names. These defects were missed by both review rounds:
  1. Keycloak 26.4.7 never puts `auth_time` in password-grant tokens, so AD-05's assumption fails
     for E2E tokens (browser sign-in is fine).
  2. The imported test administrator needs `default-roles-cce` for the account console.
  3. Two phases of the service design contradict each other: role create and delete must return
     502 on a Keycloak failure, but the resolve step had removed their operation names. The
     console does not know them.
  4. Within the service plan, "commit each database step" contradicts "one locked transaction
     across Keycloak calls" for flows 5.2 to 5.4.
  5. The console design gives no way to reach the create-user page.
  6. The console plan's method-style props fail its own lint rule (`unbound-method`).
  7. A runtime bug no prose review could see: Hibernate re-read JSON as a different number type
     and tried to UPDATE the append-only `audit_log`. It was fixed with `@Immutable`.
  8. The image check assumes a host-run service, but the compose service binds to 127.0.0.1.
- **Service fixes needed by the E2E phase:** none. **Backend defects found by the browser
  tests:** none.
- **Other findings:**
  - `npm audit` reports 14 vulnerabilities at the D-05 pins (2 critical, 1 high). The fixes need
    major-version bumps.
  - Unit tests can time out at 5 s under heavy machine load.
- **Cost:** 15 implementation agents, about 3.3 million subagent tokens (124k to 294k each).
- **Snapshot:** tag `step-04`.

## Step 05 · Drift report (`/egm:egm-drift-report`)

- **When:** 2026-11-10, 18:03–18:14 UTC (10.9 minutes, one turn, est. $11.22). H-062.
- **How:** a fresh headless session ran `/egm:egm-drift-report docs/designs/` with the artifact
  directory `egm/drift/`. The prompt named the two designs, said that `author-decisions.md` and
  `SITEMAP.md` are not designs, and asked for every `DESIGN-GAP:` comment to be listed as a
  divergence. It started two background reviewers, one for each design, then checked their
  findings itself.
- **Output:** [`egm/drift/2026-11-10_DRIFT_REPORT.md`](../egm/drift/2026-11-10_DRIFT_REPORT.md).
  No other file changed. It records:
  - **Marked:** all 82 `DESIGN-GAP:` markers in tracked files (28 service, 54 console), each with
    its design section and `file:line`. The agents reported about 72 distinct gaps; some gaps
    are marked in more than one place.
  - **Unmarked:** about 30 places where the code differs from the design and no marker says so.
- **Unmarked drift that matters** (none of it broke a test):
  1. **Console, lost edits:** a failed background refetch makes Role detail, the Menus tab and
     the Menus form unmount, losing unsaved permission edits, a menu selection or an open form.
     `UserEditPage` and `RolePermissionsTab` already handle this case. The reviewer inferred this
     from TanStack Query 5's documented behaviour; it was not observed.
  2. **Console, Retry everywhere:** every failed query shows Retry, including 401 and 403. The
     design allows Retry only for network and unexpected errors.
  3. **Service, account linking:** the match on username and email ignores case, where the design
     says "exact".
  4. **Service, lost cause:** when the Keycloak check fails on role create or delete, the cause
     exception is dropped, not logged.
  5. **Service, migration V2:** it fails if the gateway team ever adds a column to
     `api_permissions`. That is stricter than §6 says, and it would stop this service migrating.
  6. **Service, configuration:** a missing `DATABASE_*` variable fails without naming it, though
     the README says every missing variable is named.
  7. **Two markers out of date:** one says `.gitignore` does not cover a file that it now covers.
     The other says a role's name "is not loaded" when it is.
  8. **Behaviour the design does not describe:** the `/health` token handling, the stand-in's
     405, 500 and 404 responses, the `@Immutable` audit entity, and component helpers that other
     modules depend on.
- **What the drift report adds:** it is the only step that read the code against the design
  section by section. The build checks prove the code works. This report shows where the code
  and the design disagree, and that is what the next design update needs. Items 1 and 2 are
  console defects that no test covered.
- **The report's own suggestions:** the service design names about 10 source paths in 2,080
  lines, while the console design names 78. A file map in the service design's §2 would make the
  next drift check cheaper.
- **Friction:** one background reviewer ran a search that also matched the git-ignored
  `cce-admin-service/.env`, and so read local values. The session said so in its reply. A check
  of every value in that file against the report, HISTORY.md and the run record found only
  non-secret identifiers (a database username, a test username, an audience and a URL). No
  password or secret was copied anywhere. See C-19.
- **Not done:** the drift findings were recorded, not fixed. Fixing them, and updating the
  designs to match the code, is the next cycle.
- **Snapshot:** tag `step-05`.

## Step 06 · Docs from the code, compared with the team's docs

- **When:** 2026-11-11. Asked for by the user after the trial.
- **How:** two fresh agents each wrote one repository's `docs/` from the code alone, with the
  same six file names as the team's docs on GitHub. They were told not to read the team's docs,
  the MedLabs repos or any `.env` file. The orchestrator then downloaded the team's docs
  (`Jayaprakash8887/cce-admin-service` at `0c9c06f`, `Jayaprakash8887/cce-admin-ui` at
  `8a6d49c`) and compared the two sets.
- **Output:**
  - `cce-admin-service/docs/` (6 files, 1,517 lines, 14 Mermaid diagrams).
  - `cce-admin-ui/docs/` (6 files, 1,289 lines). It passes the repository's Prettier check.
  - [DOCS-COMPARISON.md](DOCS-COMPARISON.md).
- **Result:**
  - **Console:** the team's plan and the code largely agree.
  - **Service:** they differ where the PRD's fixed constraints apply. The code uses the
    `public` schema and the gateway's real `api_permissions` shape. It links existing Keycloak
    users, gives every role implicit `/me` rows, and has last-administrator protection and
    `ROLE_NOT_SYNCED`. The team's docs miss all of these.
- **New defects found while writing the docs**, beyond the drift report:
  - Two simultaneous creates with the same name give 500, not 409.
  - The OpenAPI document lists 200 for responses that are 201 or 204.
  - `/health` is always `UP`.
  - Roles are read from the token, so a removed role works until the token expires.
  - No rule limits which paths a permission row may grant.
  - Caddy caches a missing asset's `index.html` for a year.
  - The text filters keep stale text after the browser's Back button.
- **Snapshot:** tag `step-06`.
