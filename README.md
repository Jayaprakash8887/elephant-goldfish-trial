# EGM trial: CCE v2 Admin Module

This folder records a trial of Google's [Elephant-Goldfish Model](https://github.com/google/elephant-goldfish-model)
(EGM) plugin on a real PRD. EGM was used to design and build the CCE v2 admin module, and each
step was recorded.

The admin module has two parts:

- `cce-admin-service`: a Spring Boot service that manages users, roles, menus and API
  permissions, and keeps Keycloak in step.
- `cce-admin-ui`: a React console for platform administrators.

EGM has three roles:

- **Elephant:** a Claude Code session that holds the project context and drafts the design.
- **Goldfish:** fresh, read-only reviewer agents. Each sees only the design and the files it
  links to.
- **Human:** makes the trade-offs and approves. In this trial the user asked Claude to run the
  whole process, so Claude answered EGM's questions as the user's delegate. Every answer is in
  [trial-log/HISTORY.md](trial-log/HISTORY.md).

## Where to look

| File | What it holds |
|---|---|
| [trial-log/REPORT.md](trial-log/REPORT.md) | The step-by-step write-up (added at the end) |
| [trial-log/JOURNAL.md](trial-log/JOURNAL.md) | One entry per step: what was done, decisions, problems, cost |
| [trial-log/CHALLENGES.md](trial-log/CHALLENGES.md) | What got in the way of full development with EGM, and how each problem was solved |
| [trial-log/HISTORY.md](trial-log/HISTORY.md) | Every input sent to EGM and its exact reply, in order |
| [trial-log/QA.md](trial-log/QA.md) | Each question EGM asked, paired with the answer it got |
| [trial-log/timeline.md](trial-log/timeline.md) | Generated from session transcripts: subagents, files read and written, tokens |
| [context/](context/) | The inputs: the PRD, the gateway's `api_permissions` contract, and the existing console's docs (see `PROVENANCE.md`). The designs are created fresh from these. |
| [docs/designs/](docs/designs/) | The approved designs EGM produced |
| `egm/<design>/` | EGM's drafts and review reports for each design |
| `cce-admin-service/`, `cce-admin-ui/` | The implementation |

Each step is a git tag (`step-00`, `step-01`, …). For example, `git show step-03:egm/admin-service/design_draft.md`
shows a draft as it was at the end of step 3.

## Setup facts

- EGM 0.1.1 (commit `6d2af49`) is installed with project scope. It is enabled only in this folder
  (`.claude/settings.json`).
- Use Claude Code 2.1.271 or later. The VS Code extension here bundles 2.1.319. The `claude` on
  PATH is 2.1.72, so run `claude update` before using EGM from a terminal.
- EGM's commands, for running them yourself in a new session: `/egm:egm-brainstorm`,
  `/egm:egm-draft`, `/egm:egm-review <design>`, `/egm:egm-resolve`, `/egm:egm-drift-report`.
- To regenerate the timeline: `python3 trial-log/tools/timeline.py`. Claude Code deletes
  transcripts after 30 days by default.
