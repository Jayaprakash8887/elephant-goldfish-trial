# Author decisions shared by the admin-service and admin-ui designs

The two EGM designs for the CCE v2 admin module are drafted at the same time and depend on each
other through the API contract. This file records the author's decisions that both designs must
follow, so the two stay consistent and the reviewers can see why a choice was made.

- **Who decided:** Claude, acting for the user (Jayaprakash), who delegated the development.
  Each decision is based on the PRD and the team's design docs in `context/`. Where those docs
  disagree, the conflict is listed under "Raise with the team".
- **How decisions are made:** decisions that EGM puts to the author during drafting are taken at
  those gates (see `trial-log/HISTORY.md`) and copied here when both designs need them.

| ID | Decision | Why | Sources |
|---|---|---|---|
| D-01 | **The admin-service design owns the API contract.** Wherever the inputs leave a contract detail open (full response fields, menu keys or ids, the warning shape for B-41, which lists are paginated), the admin-service design settles it, and the admin-ui design uses exactly that contract. If the console needs something the contract does not give, the admin-ui design lists it as a requirement on the admin-service design, and the admin-service design is updated. The console must not work around the contract. | One owner for each fact. The service is the provider, and other consoles (cce-insights-ui) also depend on the same contract. | [api-reference.md](../../context/cce-admin-service/docs/api-reference.md), [api-integration.md](../../context/cce-admin-ui/docs/api-integration.md) |
| D-02 | **`INACTIVE` can be reversed.** `PATCH /users/:id/status` with `ACTIVE` re-activates a user, and the kept `user_roles` rows restore access. "Terminal" in PRD §2 and data-dictionary §1 is read as "the last state, used instead of deletion". There is no hard delete. | B-34, scenario S5, flow 6 and F-10 all describe re-activation, and they are the specific statements. "Terminal" appears only where hard delete is ruled out. | PRD §2, §7.4 B-34, §3 S5; [data-dictionary.md §1](../../context/cce-admin-service/docs/data-dictionary.md); [flow-diagrams.md §6](../../context/cce-admin-service/docs/flow-diagrams.md) |
| D-03 | **The local environment is defined once, in the admin-service design (milestone M0).** It covers the Keycloak realm and clients, ports, the Postgres database, and how the gateway is stood in for. The admin-ui design uses those values for local development and does not define its own. Fixed values: service on `localhost:8085`, console dev server on `localhost:3002`, the console's Vite dev proxy forwards `/admin/v1/*` to `localhost:8085`, and the Keycloak client id `cce-admin-ui` is public with PKCE S256 and redirect `http://localhost:3002/*`. | M0 belongs to the backend track in the PRD, and one definition avoids two drifting copies. | PRD §8.3, §10 (suggested local setup); [developer-setup.md](../../context/cce-admin-ui/docs/developer-setup.md) |
| D-04 | **The `api_permissions` table follows the gateway's real shape exactly:** `BIGSERIAL` id, `permission_name VARCHAR(100)`, `resource_category VARCHAR(50)`, nullable `TIMESTAMP` columns with the gateway's trigger, and `UNIQUE (permission_name, http_method, uri_pattern)`. The console's validation uses these limits. | The data dictionary itself says to confirm the types against the gateway doc, and PRD §5 calls the table a fixed constraint. | [DATABASE_PERMISSIONS_MIGRATION.md](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md), [data-dictionary.md §6](../../context/cce-admin-service/docs/data-dictionary.md) |

## Raise with the team

Conflicts between the team's documents. The designs follow the decision above and do not change
the source documents. Each item should become a doc change, as PRD §11 asks.

1. PRD §2 and data-dictionary §1 call `INACTIVE` "terminal", while B-34, S5, flow 6 and F-10
   describe re-activation (D-02).
2. data-dictionary §6 and PRD B-01 ("UUID PKs" for all seven tables) disagree with the gateway's
   `api_permissions` shape (D-04).
3. PRD §5 says the gateway reads a database named `admin_service`, while PRD §6 and the data
   dictionary say schema `admin_service`. Settled in the admin-service design (its conflict C2).
