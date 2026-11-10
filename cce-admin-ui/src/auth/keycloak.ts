// The only production module that uses keycloak-js (F-01). The access token lives only in this
// instance, in memory; it is never written to localStorage, sessionStorage or a cookie.
import Keycloak from 'keycloak-js';
import type { AppConfig } from '../config';

const REFRESH_INTERVAL_MS = 60_000;
const REFRESH_MIN_VALIDITY_S = 70;

let keycloak: Keycloak | undefined;
let refreshTimer: ReturnType<typeof setInterval> | undefined;

function instance(): Keycloak {
  if (!keycloak) throw new Error('initAuth() has not run.');
  return keycloak;
}

/**
 * Signs in with Authorization Code and PKCE (S256). Without a session keycloak-js sends the
 * browser to the Keycloak login page, which returns to the URL first requested. Rejects if `init`
 * throws or resolves without a session.
 */
export async function initAuth(config: AppConfig): Promise<void> {
  keycloak = new Keycloak({
    url: config.keycloakUrl,
    realm: config.keycloakRealm,
    clientId: config.keycloakClientId,
  });
  const authenticated = await keycloak.init({
    onLoad: 'login-required',
    pkceMethod: 'S256',
    checkLoginIframe: false,
  });
  if (!authenticated) {
    throw new Error('Keycloak init resolved without a session.');
  }
  const kc = keycloak;
  refreshTimer = setInterval(() => {
    // A failed refresh is ignored and tried again on the next tick.
    kc.updateToken(REFRESH_MIN_VALIDITY_S).catch(() => undefined);
  }, REFRESH_INTERVAL_MS);
}

export function getToken(): string | undefined {
  return keycloak?.token;
}

/** Forces a refresh. On failure it calls login() and returns false. */
export async function refreshToken(): Promise<boolean> {
  try {
    await instance().updateToken(-1);
    return true;
  } catch {
    await login();
    return false;
  }
}

export async function login(): Promise<void> {
  await instance().login();
}

export async function logout(): Promise<void> {
  if (refreshTimer !== undefined) clearInterval(refreshTimer);
  await instance().logout({ redirectUri: window.location.origin + '/' });
}
