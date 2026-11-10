# Author decisions shared by the admin-service and admin-ui designs

The two EGM designs for the CCE v2 admin module are drafted at the same time and depend on each
other through the API contract. This file records the author's decisions that both designs must
follow, so the two stay consistent and the reviewers can see why a choice was made.

- **Who decided:** Claude, acting for the user (Jayaprakash), who delegated the development.
  Decisions are based on the PRD and the inputs in `context/`.
- **How decisions are made:** decisions that EGM puts to the author during drafting are taken at
  those gates (see `trial-log/QA.md`) and copied here when both designs need them. IDs are not
  reused.

| ID | Decision | Why | Sources |
|---|---|---|---|
| D-01 | **The admin-service design owns the API contract.** It defines every endpoint's request and response shape, error codes, pagination and warnings. The admin-ui design uses exactly that contract. If the console needs something the contract does not give, the admin-ui design lists it as a requirement on the admin-service design, and the admin-service design is updated. The console must not work around the contract. | One owner for each fact. The service is the provider, and other consoles (cce-insights-ui) also depend on the same contract. | [PRD §6, §7, §8.1 F-02, Appendix A, Appendix B](../../context/prd/CCE_Admin_Module_PRD.md) |
| D-03 | **The local environment is defined once, in the admin-service design (milestone M0).** It covers the Keycloak realm and clients, ports, the Postgres database, and how the gateway is stood in for. The admin-ui design uses those values for local development and does not define its own. Fixed values: service on `localhost:8085`, console dev server on `localhost:3002`, the console's Vite dev proxy forwards `/admin/v1/*` to `localhost:8085`, and the Keycloak client `cce-admin-ui` is public with PKCE S256 and redirect `http://localhost:3002/*`. | M0 belongs to the backend track in the PRD, and one definition avoids two drifting copies. | [PRD §6, §8.3, §10 (suggested local setup)](../../context/prd/CCE_Admin_Module_PRD.md) |

## Retired decisions

D-02 (`INACTIVE` can be reversed) and D-04 (the `api_permissions` shape) were made during the
first drafting attempt (tag `step-01`). That attempt also used the team's design documents,
which are no longer inputs. These questions are decided again, at EGM's gates, if the new drafts
raise them.
