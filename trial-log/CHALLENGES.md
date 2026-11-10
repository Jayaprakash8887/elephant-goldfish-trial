# Challenges and resolutions

The question this trial answers: **can the EGM plugin carry a feature from a PRD to working,
tested code, and what has to be solved along the way?**

Each entry is something that blocked or slowed full development with EGM. It says how the
challenge was resolved here and what it means for a team adopting EGM. Entries are added as they
happen. Evidence is linked to [JOURNAL.md](JOURNAL.md) steps and [HISTORY.md](HISTORY.md) entries.

Causes:

- **EGM by design:** a deliberate choice in the plugin that a team has to work with.
- **EGM gap:** something full feature development needs that EGM does not provide.
- **Host:** Claude Code behaviour or settings.
- **Environment:** this machine or these inputs.
- **Process:** a mismatch between how EGM expects to be used and how this trial runs.

## Summary

| ID | Phase | Challenge | Cause | Status |
|---|---|---|---|---|
| C-01 | Setup | Older Claude Code versions break reviewer isolation | Host / Environment | Resolved |
| C-02 | Setup | Reviewers read files outside the project, and the agent may not grant itself access | Host | Resolved by the user |
| C-03 | Orchestration | EGM skills can only be started by a person typing the command | EGM by design | Worked around |
| C-04 | Orchestration | Plugin agents load only when a session starts | Host | Worked around |
| C-05 | Orchestration | No structured questions in headless runs, and only one question per turn | Host / EGM by design | Worked around |
| C-06 | Orchestration | Two designs at once overwrite each other's files | EGM by design | Resolved |
| C-07 | Planning | One PRD with 12 milestones is too large for one EGM design | EGM by design | Resolved |
| C-08 | Inputs | Inputs lived outside the project, and one contained a password | Environment | Resolved |
| C-09 | Process | EGM's human approval gates, when the human has delegated the work | Process | Accepted, recorded |
| C-10 | Scope | EGM stops at the design. It has no implementation or verification step | EGM gap | Worked around |
| C-11 | Orchestration | Headless sessions ignore the project's permission rules until the folder is trusted | Host | Worked around |
| C-12 | Drafting | Two designs depend on each other through the API contract | EGM gap / Process | Resolved |
| C-13 | Drafting | A headless Elephant cannot look up current versions, but the plan must pin them | Host / EGM by design | Resolved by the author |
| C-14 | Drafting | One question per turn makes drafting a long series of round trips | EGM by design | Partly mitigated |
| C-15 | Review | A stage orchestrator returned before its reviewers finished, and a reviewer read a large linked design only in part | EGM / Host | Handled by the review session; recorded |
| C-16 | Review | 145 findings for one design, from stages that disagree on direction, so the author must arbitrate | EGM by design | Resolved by author triage |
| C-17 | Review | Review rounds on a plan written at code level do not converge, because fixes create new findings | EGM by design | Stopped after round 2; the build checks take over |
| C-18 | Resolve | The plan's 5-hour usage limit stopped two sessions mid-turn, leaving partial edits and a false "resolved" status | Host / Environment | Recovered by resuming each session with a check-and-finish prompt |

## Details

### C-01 · Older Claude Code versions break reviewer isolation

- **What happened:** EGM requires Claude Code 2.1.271 or later. Earlier versions do not support
  `omitClaudeMd`, the setting that keeps project instructions (`CLAUDE.md`) out of the Goldfish
  reviewers. The `claude` on PATH here is 2.1.72. The VS Code extension bundles 2.1.319.
- **Impact:** on the old CLI, reviewers would see the project's `CLAUDE.md`. They would no longer
  be "fresh eyes", and nothing would warn about it.
- **Resolution:** every EGM run uses the extension's bundled 2.1.319 binary. `egm.py` picks the
  newest bundled version and records it in each HISTORY entry.
- **For teams:** check `claude --version` before adopting EGM, and keep every machine that runs
  EGM on 2.1.271 or later.

### C-02 · Reviewers read files outside the project

- **What happened:** EGM's reviewer personas and document standards are in the plugin cache
  (`~/.claude/plugins/cache/egm/egm/<version>/`), outside the working directory. In an
  interactive session every such read asks for permission. In a headless run nobody can answer,
  so the read is denied and the reviewer has to stop. Claude tried to add read rules to
  `.claude/settings.json`, and auto mode blocked the edit as self-modification.
- **Resolution:** the user added four read-only rules, with the version folder as `*` as EGM's
  docs recommend: `Read(~/.claude/plugins/cache/egm/egm/*/agents/**)` and the same for `docs`,
  `resources` and `skills`.
- **For teams:** commit these four rules in the project's `.claude/settings.json` next to the
  plugin setting. The `~/` form works for every developer. Do not grant broader access.

### C-03 · EGM skills can only be started by a person

- **What happened:** all seven EGM skills set `disable-model-invocation: true`. A model cannot
  start them, so a person must type `/egm:egm-draft`, `/egm:egm-review` and so on. This is
  deliberate: EGM expects a person at each step.
- **Impact:** an agent cannot run EGM from start to finish on its own.
- **Resolution here:** the session running the trial starts a separate headless Claude Code
  session with the slash command as its prompt (`claude -p "/egm:egm-draft …"`), and continues
  that session with `--resume` for each later turn. `trial-log/tools/egm.py` does this and
  records every turn in HISTORY.md.
- **For teams:** when a person drives EGM, nothing needs to change. For automation, such as a
  review in CI, use headless sessions as above. EGM's docs also allow reading a skill's
  `SKILL.md` and following it in the same thread, but only for skills nested inside an EGM run
  (resolve, review-loop).

### C-04 · Plugin agents load only when a session starts

- **What happened:** the session that installed EGM cannot spawn the plugin's reviewer agents
  (`egm:egm-zoom-out` and the others). A new headless session saw all ten of them (H-001).
- **Resolution:** EGM runs only in sessions started after installation.
- **For teams:** install or update the plugin, then start a new session.

### C-05 · No structured questions in headless runs, one question per turn

- **What happened:** `AskUserQuestion` is not available in `claude -p`. EGM's gates fall back to
  plain-chat questions, which EGM supports. EGM also allows only one question per reply, so every
  gate costs a full round trip.
- **Resolution:** `--disallowedTools AskUserQuestion` makes the fallback explicit. Each answer is
  sent with `--resume` and recorded in HISTORY.md. The number of round trips is tracked there.

### C-06 · Two designs at once overwrite each other's files

- **What happened:** EGM always writes to the same file names in its artifact folder, such as
  `design_draft.md`, `zoom_out_report.md` and `critic_report.md`. Two designs drafted at the same
  time would overwrite each other.
- **Resolution:** each design gets its own folder through EGM's documented prompt line,
  `EGM artifact directory: egm/<design>/`.
- **For teams:** use one artifact folder per design or per branch.

### C-07 · One PRD with 12 milestones is too large for one EGM design

- **What happened:** the PRD has 12 milestones (M0, B1–B5, F1–F5, E2E) and about 40
  requirements. EGM's implementation-readiness standard requires the plan to list every file and
  leave the implementer no decisions. Each review runs about 10 reviewer agents over the whole
  document. A single design for everything would be very long and expensive to review.
- **Resolution:** two designs, one per repository and PRD track: `admin-service` (M0, B1–B5 and
  the E2E checks) and `admin-ui` (F1–F5). They are drafted in parallel.
- **For teams:** size an EGM design to one repository or one pull-request-sized feature. EGM's
  FAQ also advises against big pull requests.

### C-08 · Inputs lived outside the project, and one contained a password

- **What happened:** the team's design docs were in other repositories. Goldfish reviewers only
  receive files that the design links to inside the working directory, and they cannot open URLs.
  One source doc had a default database password in plain text.
- **Resolution:** the inputs were copied into `context/`, with sources and checksums in
  `context/PROVENANCE.md`. The password was redacted. The recording tools also redact anything
  that looks like a credential.
- **For teams:** keep design inputs in the repository (EGM's `docs/designs/` convention) or copy
  them in. Remove secrets first, because reviewers read everything the design links to.

### C-09 · Human approval gates when the human has delegated the work

- **What happened:** EGM is built so that a person makes the trade-offs and approves each step.
  The skills will not choose between design options themselves ("Author Decides Architecture").
  In this trial the user asked Claude to carry out the whole development, so the session running
  the trial answers EGM's gates as the user's delegate. Each answer is based on the PRD and the
  team's design docs and is recorded as an Input in HISTORY.md.
- **Impact:** the trial tests EGM's documents, reviews and process. It does not test what a
  person's judgment at the gates would add.
- **For teams:** EGM expects the gates to be where people add value. Handing them to an agent
  removes the check that EGM is built around.

### C-10 · EGM stops at the design

- **What happened:** EGM covers ideas, drafting, review and resolution, plus a drift report
  after the code exists. For implementation it only says "tell AI to build to the plan". It has
  no build, test or verification step.
- **Resolution here:** fresh coding agents implement each approved design from the document
  alone. This also tests EGM's claim that the plan leaves the implementer no decisions. Builds,
  tests and end-to-end checks are run and recorded by the session running the trial.
- **For teams:** add your own implementation and verification steps (tests, linters, review
  checklists), as EGM itself recommends.

### C-11 · Headless sessions ignore the project's permission rules until the folder is trusted

- **What happened:** the first draft turn for the console (H-002) reported that EGM's own
  `execution_instructions.md` and design template could not be read, even though the user had
  added read rules for them. A test run showed the cause on stderr: "Ignoring 4
  permissions.allow entries from .claude/settings.json: this workspace has not been trusted." The
  folder had been opened only in the VS Code extension, so the trust flag that headless CLI
  sessions check was never set. The session itself never sees this warning. It only sees refused
  reads.
- **Impact:** the first turn of both drafts ran without EGM's execution rules and template. The
  admin-ui session said so in its reply. The admin-service session did not mention it.
- **Resolution here:** `egm.py` passes the same four read-only rules to each headless session as
  `--allowedTools` launch flags, in the absolute form EGM's docs use. Nothing beyond the folders
  the user approved is granted. The trust flag in `~/.claude.json` was left alone: changing trust
  settings is for the user. Turn 2 of each draft told the session to read the two files before
  drafting.
- **For teams:** before running EGM headless (CI, scripts), open the folder once with the
  interactive `claude` CLI and accept the trust dialog. Otherwise pass the read rules on the
  command line. Check stderr for "Ignoring … permissions.allow entries".

### C-12 · Two designs depend on each other through the API contract

- **What happened:** both Elephants found the same gaps in the API reference, such as full
  response shapes, menu keys versus ids for a role's menus, the shape of the B-41 warning,
  pagination, the realm name and the local wiring. Each would have settled them on its own, so
  the two designs could have disagreed. EGM works on one design at a time and has no way to share
  decisions between designs.
- **Resolution:** `docs/designs/author-decisions.md` records the decisions both designs must
  follow. Both designs link to it, so reviewers see it as well. Decision D-01 makes the
  admin-service design the single owner of the API contract: the console design uses that
  contract and lists anything missing as a requirement on it. Architecture questions that only
  affect one design still go through that design's own EGM gates.
- **For teams:** when a feature spans services, decide which design owns each shared interface
  before drafting. Keep a shared decision record that every design links to.
- **Also seen:** the console design reached its Detailed Implementation step before the service
  contract was complete. EGM's Zero-Decision Rule cannot be met against a contract that is half
  written, so the author paused the console session (H-019) until the service's Technical Plan
  was approved (H-026).

### C-13 · A headless Elephant cannot look up current versions

- **What happened:** EGM's implementation-readiness standard requires a plan that leaves the
  implementer no choices, which includes exact versions. The admin-service Elephant pushed back
  when the author asked for a pinned Keycloak tag. It pointed out that `26.4` still floats, and
  that `postgres:16-alpine` and an untagged Mailpit image do too. Its own attempt to look up the
  tags needed network approval, which a headless run cannot give.
- **Resolution:** the author checked every version on 2026-11-10 against Maven Central, the npm
  registry and the image registries (`docker manifest inspect`), and recorded them as D-05 in
  `docs/designs/author-decisions.md`. Both designs pin from that table.
- **For teams:** give the Elephant a verified version list as an input, or let interactive
  sessions approve its registry lookups. Otherwise a "zero-decision" plan quietly guesses its
  versions.

### C-14 · One question per turn makes drafting a long series of round trips

- **What happened:** EGM's execution rules allow exactly one question per reply, and every
  design choice with more than one defensible answer must go to the author as a gate. Drafting
  the admin-service design took 16 turns to reach its Detailed Implementation section: 4
  section gates, 10 option gates and 2 contradiction stops. Each turn takes 1 to 6 minutes
  headless.
- **Mitigation tried:** at H-017 the author answered six smaller questions in one message and
  asked the Elephant to push back. That did not skip any checks. The Elephant's contradiction
  audit stopped the turn, named two contradictions with goals already approved, and proposed
  four improvements the author had not thought of (P1 to P4 in H-017). Batching saved round
  trips without losing scrutiny.
- **For teams:** batch the questions you have firm answers to, and keep the option gates for
  real trade-offs. Expect several hours of wall-clock time per design when the gates are
  answered as they come.

### C-15 · Review reliability: an orchestrator that returned early, and partial reading

- **What happened:** two things went wrong in the console design's review (`d157201e`, H-034).
  - The Stage 9 synthesizer, the subagent that spawns that stage's three reviewers and merges
    their reports, returned before its reviewers had finished. The review's main context then
    collected the three reports itself and wrote `implementation_readiness_report.md`.
  - The Stage 8 reviewer read the 1,653-line service design, which the console design links to,
    only in part (lines 1–860 and 995–1363).

  Both are recorded in the consolidated report's "Process notes". The service design's review
  (`8a09292b`) had neither problem: "every reviewer completed without a retry".
- **Impact:** small in this run, because the main context caught both. But a reviewer that
  reads only part of a long linked file can miss contradictions in the part it skipped.
- **For teams:** read the "Process notes" and the stage table in every consolidated report.
  Keep designs and their linked files short enough for a reviewer to read whole. When a reviewer
  reports partial coverage, rerun that stage.

### C-16 · Many findings, from stages that disagree on direction

- **What happened:** the service design's review produced 145 stage findings, merged into 33
  actions (3 Blockers, 24 Majors, 6 Nits). The console design's review produced 30 actions.
  The stages disagreed on direction:
  - Stages 5 and 7 proposed new mechanisms: adopt endpoints, intent rows, a drift detector,
    background re-sync and a shadow mode.
  - Stage 8 argued that most findings came from mechanisms the design had already added beyond
    the PRD, and that removing them was the cheaper fix.

  Five actions reopened decisions the author had made at drafting gates (AD-02, AD-06, AD-12,
  AD-15 and AD-17).
- **Resolution:** the author followed Stage 8's direction where the stages conflicted, and
  decided the five reopened decisions explicitly in the resolve prompt (H-036). For example,
  AD-17's own permission check stays, but in its smallest form.
- **For teams:** budget author time for triage after every review. The Stage 8 reviewer, which
  checks earlier findings against the document's purpose, is the most useful guard against a
  design growing with each round.

### C-17 · Review rounds on a code-level plan do not converge

- **What happened:** after round 1's fixes were applied, a second Stage 9 (implementation
  readiness) review ran on each design (H-041, H-042).

  | Design | Stage 9 round 1 | Stage 9 round 2 |
  |---|---|---|
  | admin-service | 2 Blockers, 22 Majors, 23 Nits | 1 Blocker, 26 Majors, 36 Nits |
  | admin-ui | 0 Blockers, 17 Majors, 30 Nits | 0 Blockers, 11 Majors, 23 Nits |

  - **admin-service:** 18 of round 1's findings came back in some form. In six of them, the text
    added by the round-1 fix was itself the new defect. Examples are a "shared" Testcontainers
    base that Spring's context cache cannot reuse, repository method names Spring Data cannot
    derive, and a timestamp rule for columns three tables do not have.
  - **admin-ui:** four of its Majors appeared where round 1's fixes had landed.
- **Why:** EGM's implementation-readiness standard requires a plan with no decisions left
  ("Zero-Decision Rule"). On a service this size, that pushes the plan down to class names,
  repository methods, annotations and test fixtures, which is code written as prose. Prose code
  is never compiled. Each review finds the errors in it, each fix adds more prose code, and the
  next review finds errors in that. The service design grew from 1,653 to 1,937 lines after one
  round of fixes.
- **Resolution here:** EGM allows one re-review per stage, and round 2 used it. The author
  resolved round 2's findings once, so the design stays the source of truth that the later
  drift report compares against. Then review stopped, and the plan's exit checks during
  implementation (`./mvnw verify`, `npm run build`, the tests) act as the next "review". Gaps
  the implementer meets are recorded as `DESIGN-GAP` notes and counted.
- **For teams:**
  - Keep the plan at the level of files, contracts, behaviour and exit checks. Leave code-level
    details (repository method names, annotation sets, fixture wiring) to the implementer and
    the compiler.
  - Review the feature-level design (Stages 5 to 8) thoroughly. Treat Stage 9 as a check of the
    plan's completeness and order, not as a code review.
  - Stop at one round of Stage 9 unless it finds Blockers.

### C-18 · The usage limit stops sessions mid-turn and leaves partial state

- **What happened:** at 14:59 UTC both round-2 resolve turns (H-043, H-044) ended with "You've
  hit your 5-hour limit · resets 8:50pm (Asia/Kolkata)".
  - The **service session** had already written `goldfish_review_report_round2.md` with
    "Status: resolved" at the top, but had not applied any of its 66 fixes to the design.
  - The **console session** had applied part of its fixes, about 170 changed lines, before it
    stopped.

  All the EGM sessions, the reviewer subagents and the orchestrating session draw on the same
  plan limit. By then the trial had used about 3.5 hours of mostly parallel work: two drafts,
  two full five-stage reviews, two resolves and two Stage 9 re-reviews.
- **Impact:** about 40 minutes of waiting. Worse, the state on disk was misleading: a
  "resolved" report next to an unchanged design.
- **Resolution:** after the reset the user said "resume". Each session was resumed with a prompt
  that stated what was on disk and asked it to check the design against its report item by
  item, apply what was missing, and make the status line true (H-045, H-046).
- **For teams:**
  - Never trust a report's status line after an interrupted turn. Compare it with the files.
  - Commit after every completed EGM turn, so an interruption is visible as a diff.
  - Plan multi-design runs around the usage limit. Run fewer sessions in parallel, or use API
    billing for long unattended runs.
