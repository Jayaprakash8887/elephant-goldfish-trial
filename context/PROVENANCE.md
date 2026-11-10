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
| `cce-admin-service/docs/README.md` | `MedLabs/cce-admin-service/docs/README.md` | HEAD @ `0c9c06f` | `2083326593f1f569` | none |
| `cce-admin-service/docs/architecture-overview.md` | `MedLabs/cce-admin-service/docs/architecture-overview.md` | HEAD @ `0c9c06f` | `79b8f3654657a4ec` | none |
| `cce-admin-service/docs/data-dictionary.md` | `MedLabs/cce-admin-service/docs/data-dictionary.md` | HEAD @ `0c9c06f` | `aa9ddd8cf1275d5f` | none |
| `cce-admin-service/docs/security-model.md` | `MedLabs/cce-admin-service/docs/security-model.md` | HEAD @ `0c9c06f` | `0f3c50aa7269c2d8` | none |
| `cce-admin-service/docs/api-reference.md` | `MedLabs/cce-admin-service/docs/api-reference.md` | HEAD @ `0c9c06f` | `b3cf8c8a4a9b1c74` | none |
| `cce-admin-service/docs/flow-diagrams.md` | `MedLabs/cce-admin-service/docs/flow-diagrams.md` | HEAD @ `0c9c06f` | `119ef59e22c4436a` | none |
| `cce-admin-ui/docs/README.md` | `MedLabs/cce-admin-ui/docs/README.md` | HEAD @ `8a6d49c` | `e26f5b8561355800` | none |
| `cce-admin-ui/docs/architecture-overview.md` | `MedLabs/cce-admin-ui/docs/architecture-overview.md` | HEAD @ `8a6d49c` | `1d54cb00355f9cca` | none |
| `cce-admin-ui/docs/pages-and-wireframes.md` | `MedLabs/cce-admin-ui/docs/pages-and-wireframes.md` | HEAD @ `8a6d49c` | `424ede4c284bc2da` | none |
| `cce-admin-ui/docs/api-integration.md` | `MedLabs/cce-admin-ui/docs/api-integration.md` | HEAD @ `8a6d49c` | `b394fcc8dea63d5d` | none |
| `cce-admin-ui/docs/developer-setup.md` | `MedLabs/cce-admin-ui/docs/developer-setup.md` | HEAD @ `8a6d49c` | `9eba897b4ed1e719` | none |
| `cce-admin-ui/docs/deployment-guide.md` | `MedLabs/cce-admin-ui/docs/deployment-guide.md` | HEAD @ `8a6d49c` | `71de3547c2712a4e` | none |
| `gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` | `MedLabs/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` | HEAD @ `10d9495` | `ee72ba7dc6a8462f` | default DB password replaced by `<redacted>` (4 places) |
| `cce-insights-ui/docs/architecture-overview.md` | `MedLabs/cce-insights-ui`, git ref below (the working tree is on another branch) | `openphc/demo-rw` @ `869ac05` | `e92992d6ec977500` | none |
| `cce-insights-ui/docs/developer-setup.md` | `MedLabs/cce-insights-ui`, git ref below | `openphc/demo-rw` @ `869ac05` | `3ee963ceb12a27bc` | none |
| `cce-insights-ui/docs/deployment-guide.md` | `MedLabs/cce-insights-ui`, git ref below | `openphc/demo-rw` @ `869ac05` | `f86c3fafc456012a` | none |

## Notes

- **Which document wins.** The PRD (§1, "Golden rule") says the team's design docs are the
  source of truth for contracts, schema and behaviour. For this trial those are
  `cce-admin-service/docs/` and `cce-admin-ui/docs/`. If the PRD and a design doc disagree, report
  the conflict instead of choosing one.
- **Gateway contract.** `gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` describes the
  `api_permissions` table that gateway-service reads. Its shape is a fixed constraint (PRD §5, §12).
- **Template UI docs.** `cce-admin-ui/docs/` links to the `cce-insights-ui` docs on GitHub
  (`openphc/cce-insights-ui`, branch `demo-rw`). The three linked pages are copied into
  `cce-insights-ui/docs/`.
- **Redaction.** The gateway doc in its source repo contains a default database password in plain
  text. The copy here replaces it with `<redacted>`. The source repo is unchanged.
- **PRD Markdown check.** Every word in `pdftotext -layout` output of the PDF appears in the
  Markdown copy, except the table-of-contents heading "Contents", which was left out. All 55
  requirement and milestone IDs and all 30 error and role codes match.
