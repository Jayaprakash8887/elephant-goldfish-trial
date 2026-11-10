# Design sitemap

Approved design documents for the CCE v2 admin module. EGM's draft skill adds a row when it
saves a design.

| Link | Date | Author | Components | Description |
|---|---|---|---|---|
| [cce-admin-service](cce-admin-service.md) | 2026-11-10 | jayaprakash8887 | cce-admin-service, gateway stand-in, local environment (M0) | Backend of the CCE v2 admin module: data dictionary and seed, the API contract the console builds against, the Keycloak and gateway flows, M0, and an eight-phase build plan (B-01 to B-50, S1 to S6). Status: Proposed, awaiting `/egm:egm-review`. |
| [cce-admin-ui](cce-admin-ui.md) | 2026-11-10 | jayaprakash8887 | cce-admin-ui | Admin console of the CCE v2 admin module: shell and Keycloak PKCE sign-in, typed API client mirroring the cce-admin-service contract, Users, Roles (Menus and Permissions tabs) and Menus screens, sync-failure handling, MSW tests and Playwright browser tests (F-01 to F-15, §8.3, S2 to S6). Status: Proposed, awaiting `/egm:egm-review`. |
