// The replacement for src/auth/keycloak.ts in every test file (UI-AD-01). setup.ts installs it
// with vi.mock; only keycloak.test.ts runs the real module.
import { vi } from 'vitest';
import type * as Real from '../auth/keycloak';

export const initAuth = vi.fn<typeof Real.initAuth>();
export const getToken = vi.fn<typeof Real.getToken>();
export const refreshToken = vi.fn<typeof Real.refreshToken>();
export const login = vi.fn<typeof Real.login>();
export const logout = vi.fn<typeof Real.logout>();

function installDefaults(): void {
  initAuth.mockResolvedValue(undefined);
  getToken.mockReturnValue('test-token');
  refreshToken.mockResolvedValue(true);
  login.mockResolvedValue(undefined);
  logout.mockResolvedValue(undefined);
}

installDefaults();

/** Resets every spy and reinstalls the defaults; setup.ts calls it after every test. */
export function resetFakeAuth(): void {
  for (const spy of [initAuth, getToken, refreshToken, login, logout]) spy.mockReset();
  installDefaults();
}

// A change to the real module's exports or signatures fails `tsc -b` here.
({ initAuth, getToken, refreshToken, login, logout }) satisfies typeof import('../auth/keycloak');
