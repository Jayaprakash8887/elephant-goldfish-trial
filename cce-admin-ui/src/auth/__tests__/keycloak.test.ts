// The only test file that depends on the keycloak-js API (UI-AD-01): it runs the real module
// with keycloak-js mocked.
import type { AppConfig } from '../../config';

vi.unmock('../keycloak');

const kc = vi.hoisted(() => {
  interface FakeInstance {
    config: unknown;
    token: string | undefined;
    init: ReturnType<typeof vi.fn>;
    updateToken: ReturnType<typeof vi.fn>;
    login: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  }
  const instances: FakeInstance[] = [];
  /** What the next instance's `init` resolves with, or rejects with. */
  const state: { nextInit: boolean | Error } = { nextInit: true };
  const Keycloak = vi.fn(function (this: FakeInstance, config: unknown) {
    this.config = config;
    this.token = 'kc-token';
    this.init =
      state.nextInit instanceof Error
        ? vi.fn().mockRejectedValue(state.nextInit)
        : vi.fn().mockResolvedValue(state.nextInit);
    this.updateToken = vi.fn().mockResolvedValue(true);
    this.login = vi.fn().mockResolvedValue(undefined);
    this.logout = vi.fn().mockResolvedValue(undefined);
    instances.push(this);
  });
  return { Keycloak, instances, state, last: () => instances[instances.length - 1]! };
});

vi.mock('keycloak-js', () => ({ default: kc.Keycloak }));

import { getToken, initAuth, login, logout, refreshToken } from '../keycloak';

const config: AppConfig = {
  keycloakUrl: 'http://keycloak.test/auth',
  keycloakRealm: 'cce',
  keycloakClientId: 'cce-admin-ui',
  apiBaseUrl: '',
};

function nextInitResolves(value: boolean | Error): void {
  kc.state.nextInit = value;
}

beforeEach(() => {
  kc.state.nextInit = true;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('initAuth', () => {
  it('creates the instance and calls init with login-required and PKCE S256', async () => {
    await initAuth(config);
    expect(kc.last().config).toEqual({
      url: 'http://keycloak.test/auth',
      realm: 'cce',
      clientId: 'cce-admin-ui',
    });
    expect(kc.last().init).toHaveBeenCalledWith({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: false,
    });
    expect(getToken()).toBe('kc-token');
  });

  it('rejects when init resolves without a session', async () => {
    nextInitResolves(false);
    await expect(initAuth(config)).rejects.toThrow();
  });

  it('rejects when init throws', async () => {
    nextInitResolves(new Error('init failed'));
    await expect(initAuth(config)).rejects.toThrow('init failed');
  });

  it('calls updateToken(70) every 60 seconds', async () => {
    await initAuth(config);
    const instance = kc.last();
    expect(instance.updateToken).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(instance.updateToken).toHaveBeenCalledTimes(1);
    expect(instance.updateToken).toHaveBeenLastCalledWith(70);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(instance.updateToken).toHaveBeenCalledTimes(2);
  });

  it('ignores a failed refresh in the interval and does not call login', async () => {
    await initAuth(config);
    const instance = kc.last();
    instance.updateToken.mockRejectedValue(new Error('refresh failed'));
    await vi.advanceTimersByTimeAsync(60_000);
    expect(instance.updateToken).toHaveBeenCalledWith(70);
    expect(instance.login).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(instance.updateToken).toHaveBeenCalledTimes(2);
  });
});

describe('refreshToken', () => {
  it('forces a refresh and returns true', async () => {
    await initAuth(config);
    await expect(refreshToken()).resolves.toBe(true);
    expect(kc.last().updateToken).toHaveBeenCalledWith(-1);
    expect(kc.last().login).not.toHaveBeenCalled();
  });

  it('calls login and returns false when the refresh fails', async () => {
    await initAuth(config);
    kc.last().updateToken.mockRejectedValue(new Error('refresh failed'));
    await expect(refreshToken()).resolves.toBe(false);
    expect(kc.last().login).toHaveBeenCalledTimes(1);
  });
});

describe('login and logout', () => {
  it('login calls keycloak login', async () => {
    await initAuth(config);
    await login();
    expect(kc.last().login).toHaveBeenCalledTimes(1);
  });

  it('logout redirects to the console root', async () => {
    await initAuth(config);
    await logout();
    expect(kc.last().logout).toHaveBeenCalledWith({
      redirectUri: window.location.origin + '/',
    });
  });
});
