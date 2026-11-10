// The four build-time values of PRD §8.3. This is the only file that reads the Vite environment
// (checked by src/__tests__/configSource.test.ts), and there is no auth flag or static token
// (C-01, Goal 7).

export interface AppConfig {
  keycloakUrl: string;
  keycloakRealm: string;
  keycloakClientId: string;
  apiBaseUrl: string;
}

export type ConfigResult = { ok: true; config: AppConfig } | { ok: false; missing: string[] };

/** Trims a value; an empty value counts as unset. */
function clean(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function apiBaseUrlFromEnv(): string {
  return (clean(import.meta.env.VITE_API_BASE_URL) ?? '').replace(/\/$/, '');
}

export function readConfig(): ConfigResult {
  const keycloakRealm = clean(import.meta.env.VITE_KEYCLOAK_REALM);
  if (!keycloakRealm) {
    // C-02: the realm is required and has no default.
    return { ok: false, missing: ['VITE_KEYCLOAK_REALM'] };
  }
  return {
    ok: true,
    config: {
      keycloakUrl: clean(import.meta.env.VITE_KEYCLOAK_URL) ?? `${window.location.origin}/auth`,
      keycloakRealm,
      keycloakClientId: clean(import.meta.env.VITE_KEYCLOAK_CLIENT_ID) ?? 'cce-admin-ui',
      apiBaseUrl: apiBaseUrlFromEnv(),
    },
  };
}

/** The API base URL for client.ts: trimmed, empty by default, without a trailing `/`. */
export function getApiBaseUrl(): string {
  return apiBaseUrlFromEnv();
}
