# Inputs and where they came from

`context/` holds read-only copies of every input to this trial, taken on 2026-11-10. Do not edit
them. If a source repo changes, take a new copy and update this table. Do not patch the copies
by hand.

The trial uses copies, not links into the source repos, for two reasons:

- EGM's Goldfish reviewers only get files that the design doc links to inside this workspace.
- A fixed copy shows exactly what the Elephant and the reviewers read at each step.

`MedLabs/` means `/home/jayaprakashn/Documents/workspace/MedLabs/`. Repo docs were taken from
the git commit shown (`git show <ref>:<path>`), not from the working tree.

| Snapshot file | Source | Ref @ commit | sha256 (snapshot, first 16) | Changes |
|---|---|---|---|---|
| `prd/CCE_Admin_Module_PRD.pdf` | `~/Downloads/CCE_Admin_Module_PRD.pdf` (same bytes as `MedLabs/CCE_Admin_Module_PRD.pdf`) | n/a | `026f34bc3006db64` | none |
| `prd/CCE_Admin_Module_PRD.md` | made from the PDF in this trial | n/a | `8c8c05595844e0f9` | Markdown copy; diagram as list |
| `gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` | `MedLabs/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` | HEAD @ `10d9495` | `ee72ba7dc6a8462f` | default DB password replaced by `<redacted>` (4 places) |
| `cce-insights-ui/docs/architecture-overview.md` | `MedLabs/cce-insights-ui`, git ref below (the working tree is on another branch) | `openphc/demo-rw` @ `869ac05` | `e92992d6ec977500` | none |
| `cce-insights-ui/docs/developer-setup.md` | `MedLabs/cce-insights-ui`, git ref below | `openphc/demo-rw` @ `869ac05` | `3ee963ceb12a27bc` | none |
| `cce-insights-ui/docs/deployment-guide.md` | `MedLabs/cce-insights-ui`, git ref below | `openphc/demo-rw` @ `869ac05` | `f86c3fafc456012a` | none |

## Notes

- **Team design docs removed.** The first drafting attempt (tag `step-01`) also used copies of
  the team's existing design docs for cce-admin-service and cce-admin-ui (data dictionary, API
  reference, flow diagrams, security model, frontend docs). The user asked for the designs to be
  created fresh by EGM, so those copies were deleted from `context/` after `step-01`. The
  originals in the MedLabs repos are unchanged. The PRD's golden rule, which names those docs as
  the source of truth, is replaced for this trial by the approved EGM designs in `docs/designs/`.
- **Gateway contract.** `gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` describes the
  `api_permissions` table that gateway-service reads. Its shape is a fixed constraint (PRD §5, §12).
- **Existing console docs.** `cce-insights-ui/docs/` holds three pages of the existing console's
  docs (`openphc/cce-insights-ui`, branch `demo-rw`): its navigation and stack, which the admin
  module's menu seed and console stack must match.
- **Redaction.** The gateway doc in its source repo contains a default database password in plain
  text. The copy here replaces it with `<redacted>`. The source repo is unchanged.
- **PRD Markdown check.** Every word in `pdftotext -layout` output of the PDF appears in the
  Markdown copy, except the table-of-contents heading "Contents", which was left out. All 55
  requirement and milestone IDs and all 30 error and role codes match.
