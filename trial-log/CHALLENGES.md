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
