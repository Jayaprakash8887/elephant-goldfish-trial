// Values for the browser tests (UI-AD-09). The test administrator's credentials are read only
// from the environment and never written to a file; the URLs are the fixed local values of
// admin-service §9.3.

/** The console, served by the Vite dev server (D-03). */
export const CONSOLE_URL = 'http://localhost:3002';

/** The service, reached directly (not through the Vite proxy) for test data. */
export const SERVICE_URL = 'http://localhost:8085/admin/v1';

/** The M0 Keycloak token endpoint; tokens for test data come from the local-only `cce-e2e`. */
export const TOKEN_URL = 'http://localhost:8180/auth/realms/cce/protocol/openid-connect/token';

/** The stand-in gateway's mode switch (admin-service AD-11). */
export const STUB_MODE_URL = 'http://localhost:8090/_stub/mode';

export interface AdminCredentials {
  username: string;
  password: string;
}

/** The test administrator's credentials, from `CCE_TEST_ADMIN_USERNAME` and `..._PASSWORD`. */
export function adminCredentials(): AdminCredentials {
  const username = process.env.CCE_TEST_ADMIN_USERNAME;
  const password = process.env.CCE_TEST_ADMIN_PASSWORD;
  if (!username || !password) {
    throw new Error(
      'Set CCE_TEST_ADMIN_USERNAME and CCE_TEST_ADMIN_PASSWORD in the environment, with the ' +
        'values from the admin-service .env (export them; never add them to a file).',
    );
  }
  return { username, password };
}
