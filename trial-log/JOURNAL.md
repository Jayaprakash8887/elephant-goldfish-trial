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
