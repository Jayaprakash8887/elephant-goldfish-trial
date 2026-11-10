# EGM trial: CCE v2 Admin Module

This folder is a trial of Google's Elephant-Goldfish Model (EGM) plugin
(<https://github.com/google/elephant-goldfish-model>, v0.1.1). It designs and builds the CCE v2
admin module from its PRD: `cce-admin-service` (Spring Boot backend) and `cce-admin-ui` (React
admin console). Every step is recorded in `trial-log/`.

Each design keeps its EGM working files in its own folder, `egm/<design>/`. Every EGM prompt
names it with a line `EGM artifact directory: egm/<design>/`, so two designs can be worked on at
the same time without overwriting each other's `design_draft.md` or reports.

## Inputs (read-only, in `context/`)

- `context/prd/CCE_Admin_Module_PRD.md`: the PRD. It is a Markdown copy of the PDF next to it,
  and the PDF is authoritative.
- `context/cce-admin-service/docs/`: the team's backend design docs (architecture, data
  dictionary, security model, API reference, flow diagrams).
- `context/cce-admin-ui/docs/`: the team's frontend design docs.
- `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md`: the `api_permissions` table that
  gateway-service reads. Its shape is a fixed constraint.
- `context/cce-insights-ui/docs/`: the existing console whose stack `cce-admin-ui` follows.
- `context/PROVENANCE.md`: where each input came from.

The PRD's golden rule: the team's design docs are the source of truth for contracts, schema and
behaviour, and the PRD summarises and sequences them. If two inputs disagree, say so and write
the conflict down. Do not pick one silently. Do not edit anything under `context/`.

## Layout

- `egm/<design>/`: EGM working files for one design (`design_draft.md`, review reports). A new
  cycle overwrites them. Earlier versions are kept in git tags.
- `docs/designs/`: approved design docs, listed in `docs/designs/SITEMAP.md`.
- `cce-admin-service/`, `cce-admin-ui/`: the implementation, one folder per repo named in the PRD.
  Built from the approved designs.
- `trial-log/`: the trial record. `JOURNAL.md` has one entry per step. `HISTORY.md` lists every
  input sent to an EGM session and its reply, in order. `timeline.md` is generated from the
  session transcripts by `tools/timeline.py`. `REPORT.md` is the write-up.

## Design doc conventions

- Link inputs with relative paths, for example
  `../../context/cce-admin-service/docs/data-dictionary.md`. EGM reviewers only receive the files
  a design doc links to inside this folder.
- Cite PRD requirement IDs (B-01, F-10, S1, milestone B1) so coverage can be checked.

## Rules

- `trial-log/` is written by the session that runs the trial. Do not edit it during an EGM
  skill run.
- Never commit credentials, `.env` files or Keycloak exports (PRD §11). Local-only values go in
  example files with placeholders.
