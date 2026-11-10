import { getApiBaseUrl, readConfig } from '../config';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('readConfig', () => {
  it('reads the values set for the tests', () => {
    expect(readConfig()).toEqual({
      ok: true,
      config: {
        keycloakUrl: 'http://keycloak.test/auth',
        keycloakRealm: 'cce',
        keycloakClientId: 'cce-admin-ui',
        apiBaseUrl: 'http://localhost:3002',
      },
    });
  });

  it('applies the defaults when the optional values are unset', () => {
    vi.stubEnv('VITE_KEYCLOAK_URL', '');
    vi.stubEnv('VITE_KEYCLOAK_CLIENT_ID', '');
    vi.stubEnv('VITE_API_BASE_URL', '');
    expect(readConfig()).toEqual({
      ok: true,
      config: {
        keycloakUrl: `${window.location.origin}/auth`,
        keycloakRealm: 'cce',
        keycloakClientId: 'cce-admin-ui',
        apiBaseUrl: '',
      },
    });
    expect(getApiBaseUrl()).toBe('');
  });

  it('reports an empty realm as missing', () => {
    vi.stubEnv('VITE_KEYCLOAK_REALM', '');
    expect(readConfig()).toEqual({ ok: false, missing: ['VITE_KEYCLOAK_REALM'] });
  });

  it('treats a realm of only spaces as missing', () => {
    vi.stubEnv('VITE_KEYCLOAK_REALM', '   ');
    expect(readConfig()).toEqual({ ok: false, missing: ['VITE_KEYCLOAK_REALM'] });
  });

  it('trims every value', () => {
    vi.stubEnv('VITE_KEYCLOAK_URL', '  https://sso.example/auth  ');
    vi.stubEnv('VITE_KEYCLOAK_REALM', ' cce ');
    vi.stubEnv('VITE_KEYCLOAK_CLIENT_ID', ' other-client ');
    vi.stubEnv('VITE_API_BASE_URL', ' https://api.example ');
    expect(readConfig()).toEqual({
      ok: true,
      config: {
        keycloakUrl: 'https://sso.example/auth',
        keycloakRealm: 'cce',
        keycloakClientId: 'other-client',
        apiBaseUrl: 'https://api.example',
      },
    });
  });

  it('removes a trailing / from the API base URL', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example/');
    const result = readConfig();
    expect(result.ok && result.config.apiBaseUrl).toBe('https://api.example');
    expect(getApiBaseUrl()).toBe('https://api.example');
  });

  it('ignores VITE_AUTH_ENABLED and VITE_AUTH_TOKEN (C-01)', () => {
    const before = readConfig();
    vi.stubEnv('VITE_AUTH_ENABLED', 'false');
    vi.stubEnv('VITE_AUTH_TOKEN', 'x');
    expect(readConfig()).toEqual(before);
  });
});
