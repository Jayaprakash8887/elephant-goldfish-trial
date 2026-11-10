# Comprehension Report (Stage 6)

**Target:** `docs/designs/cce-admin-service.md` (FeatureRange 1-1363, DetailedRange 1364-1653)
**Reviewer:** `egm-comprehension` (status: completed). It read the target document only, with no document standard.
**Severity breakdown:** 0 Blocker, 2 Major, 10 Nit

The reviewer's full result follows, unedited.

---

**What this proposes**

The document proposes `cce-admin-service`, a new Spring Boot backend on port 8085. It manages users, roles, menus and API permissions. Every change is written to PostgreSQL and, where needed, to Keycloak (user accounts and role assignments), with an append-only audit row each time. After every permission change it calls the existing gateway's sync endpoint, so the gateway creates or removes the matching Keycloak realm roles. Partial failures between these systems come back as 502 with a `partialState` object and are logged for someone to fix by hand. Nothing is retried or undone automatically. The document also defines a one-command Docker environment (Postgres, Keycloak with a ready-made realm, Mailpit, and a stand-in gateway with a switch that makes it fail on demand). It closes with an eight-phase build plan for a coding agent, ending in API-level end-to-end checks of scenarios S1 to S6.

**How the existing system works, per this document**

`gateway-service` is owned by another team and cannot be changed. It validates each caller's JWT, then reads the `api_permissions` table to decide whether the caller's roles allow the method and path. It creates and deletes Keycloak realm role definitions through `POST /api/permissions/sync-to-keycloak`, whose request, response and authentication are undocumented and assumed here (GW-1 to GW-6). It reportedly loads permissions only at startup. `cce-insights-ui` shows the same fixed 7-link sidebar to every user and never reads roles. Today, changing roles or permissions means editing Keycloak by hand and running SQL against `api_permissions`, with no audit trail.

I could paraphrase every section, so there is no Blocker. The findings below are where a new reader has to work out the connections alone, or where two parts of the document contradict each other.

---

**Finding 1**
- **Severity:** Major
- **Affected Scope:** The mapping between local roles, `api_permissions` rows and Keycloak realm roles (Overview; Technical Plan §1 and §6; AD-11, AD-16; §3 step 5)
- **Discrepancy Description & Rationale:** The whole design depends on one chain, but the document never states it. A role's `api_permissions` rows carry the role's `name` as `permission_name`. The gateway sync creates a Keycloak realm role with that same name. Once that realm role is assigned, the user's token lists it at `realm_access.roles`. Step 5 and the gateway then use that token role to select rows. A reader has to piece this together from scattered clues: the 100-character note on `Role.name`, the "unowned rows" SQL join `r.name = p.permission_name`, AD-11's "realm role for every distinct `permission_name`", and step 5's "rows whose `permission_name` is one of them". One sentence in §1 or in the `api_permissions` entry of §6 would fix this.

**Finding 2**
- **Severity:** Major
- **Affected Scope:** How audit rows and 502s work for gateway sync failures: §4.2 (lines 567-576), §5 "What a 502 means" (line 748), §5.8, §8 (lines 1026-1027), G8
- **Discrepancy Description & Rationale:** The rules contradict each other.
  - §5 says a failure after the first named step writes one audit row with outcome `PARTIAL_FAILURE`. But §8 and G8 say a failed sync is recorded by the `ROLE_SYNC_REQUESTED` row, with outcome `FAILED`. A reader cannot tell whether a failed permission save writes two rows or three, or which outcome values to expect.
  - §4.2 and §5 say a failure before anything changed gives `completedSteps: []`, no audit row and `auditLogId: null`. §5.8 (manual sync) gives `completedSteps: []` yet `auditLogId` points to a `ROLE_SYNC_REQUESTED` row, and G8 says manual sync always writes one row.

  The sync is a third kind of failure, alongside "before any change" and "partial". It needs its own rule, stated once.

**Finding 3**
- **Severity:** Nit
- **Affected Scope:** G8 (lines 124-139) and §10 service tests (line 1133), compared with AD-12, AD-16 and the Phase 5 tests (line 1551)
- **Discrepancy Description & Rationale:** G8 names the permission save as the only endpoint that writes two audit rows, and its check lists "two rows for a permission save and one row for a manual re-sync". Since AD-12 and AD-16, role create and role delete also trigger a sync, so they also write two rows (`ROLE_CREATED` or `ROLE_DELETED`, then `ROLE_SYNC_REQUESTED`). Only Phase 5 says so. G8 and §10 should list all four cases.

**Finding 4**
- **Severity:** Nit
- **Affected Scope:** AD-06 rationale and G8 ("whether every permission change reached the gateway"), compared with GW-2
- **Discrepancy Description & Rationale:** AD-06 presents `ROLE_SYNC_REQUESTED`/`SYNCED` as the answer to "is the saved permission actually in effect?". GW-2 says the gateway may load permissions only at startup. In that case a successful sync does not mean the gateway is enforcing the change. A reader could take `SYNCED`, or AS-REQ-18's "200 only when the save and the sync both succeed", as "in effect". AD-06 should say that `SYNCED` means "realm roles reconciled", and that it means "enforced" only if GW-2 is answered yes.

**Finding 5**
- **Severity:** Nit
- **Affected Scope:** Labels that are never defined: "pushback P1", "P2", "P4" (AD-07, AD-10), "console review CQ-1" (line 652), "OQ-1" to "OQ-15" (AD table), "drafting gates", "this trial" (line 26), "Drafted with EGM" (line 1653)
- **Discrepancy Description & Rationale:** None of these is defined or linked. The OQ numbers appear in the AD table, but the Open Questions section lists no OQ items, only "all decided". The AD rows do restate each question, so the decisions can still be followed. But "pushback P2" and "this trial" assume the reader knows the drafting process and the experiment. Either drop these labels or link them to where they are defined.

**Finding 6**
- **Severity:** Nit
- **Affected Scope:** Leftover drafting history: "G8 is amended to match" (AD-06), "G5 is amended to match" (AD-08), "AD-07 is amended to match" (AD-14), the "Superseded part" in AD-10, and "the original G5" (Alternatives, AD-08)
- **Discrepancy Description & Rationale:** A new reader cannot tell whether G5, G8 and AD-07 as printed are already amended or still need amending. They appear to be already amended. AD-10 also describes a rule (P2's empty-set `ROLE_IN_USE`) only to say it has been dropped. These notes should be removed, or replaced with a single statement that the goals reflect the final decisions.

**Finding 7**
- **Severity:** Nit
- **Affected Scope:** PRD labels used as evidence without saying what they mean: F-10 (DEV-4, AD-04, AD-09), "B3 notes" (G5, §10), "B5 demo" (G7), "B1 demo" (Phase 3)
- **Discrepancy Description & Rationale:** The PRD is linked, but these labels are cited to justify decisions without stating their content. For example, DEV-4 relies on F-10 describing re-activation, and the reader has to open the PRD to check. Adding a few words for each, such as "F-10 (console user edit, including re-activation)", would keep the argument readable on its own.

**Finding 8**
- **Severity:** Nit
- **Affected Scope:** §5.1 step 3 (line 756)
- **Discrepancy Description & Rationale:** "Ask Keycloak whether the username or the email is already used by an account with no local row. Otherwise 409." The logic is backwards: the 409 applies when Keycloak says yes, not otherwise. It should read "If so, 409 `USERNAME_IN_USE` or `EMAIL_IN_USE`."

**Finding 9**
- **Severity:** Nit
- **Affected Scope:** AD-15 step (4) and §5.10 "role that asked"
- **Discrepancy Description & Rationale:** §5.10 writes `ROLE_SYNC_REQUESTED` "for the role that asked" and marks that role `FAILED` on failure. When the bootstrapper triggers the sync, no role asked. The reader has to guess that it is probably `SUPER_ADMIN`, both as the audit row's `entity_id` and as the role whose sync status is updated.

**Finding 10**
- **Severity:** Nit
- **Affected Scope:** Endpoint paths in G4, §4.5 and the CUJs, compared with §3 and §4.1
- **Discrepancy Description & Rationale:** G4 and the §4.5 table write `GET /health`, `/me/menus` and `/users`. §3 writes `/admin/v1/health`. The convention ("Every endpoint is under `/admin/v1`") is only stated in §4.1, which comes after G4 and the CUJs. Stating it once in the Overview would remove the doubt.

**Finding 11**
- **Severity:** Nit
- **Affected Scope:** Detailed Implementation Plan, "How to use this plan", Paths (line 1375) and "Never commit" (line 1389)
- **Discrepancy Description & Rationale:** "All paths are relative to `cce-admin-service/`, at the root of this folder" and "The root `.gitignore`" leave unclear which folder or repository is meant. The document never says where `cce-admin-service/` lives relative to this design document, or whether it is a new repository or a directory in this one.

**Finding 12**
- **Severity:** Nit
- **Affected Scope:** Phase 5, `RoleController.java` row (line 1542)
- **Discrepancy Description & Rationale:** It says "The nine `/roles` endpoints", but §4.5 lists ten. Phase 5's own "14 role and menu endpoints" (10 + 4) and the total of 24 both depend on ten.
