// Fixtures for the browser tests (UI-AD-09). Starting data is created through the API with a
// token from the local-only client `cce-e2e` (password grant, admin-service §9.2); the steps under
// test go through the console. Every name carries a per-test suffix.
import { test as base, expect, type Page } from '@playwright/test';
import type {
  ErrorEnvelope,
  PermissionRowInput,
  Role,
  RolePermissions,
  SuccessEnvelope,
  User,
} from '../src/api/contract';
import { adminCredentials, SERVICE_URL, STUB_MODE_URL, TOKEN_URL } from './env';

/** A role name: `E2E_{STEM}_{suffix}`. A stem is 1 to 10 upper-case letters or digits. */
export function roleName(stem: string, suffix: string): string {
  return `E2E_${stem}_${suffix}`;
}

// DESIGN-GAP: item 100 names roles, usernames and emails but not a role's display name or a
// user's first and last name; `E2E {STEM} {suffix}`, "E2E" and the stem are used.
/** A role's display name: `E2E {STEM} {suffix}`. */
export function roleDisplayName(stem: string, suffix: string): string {
  return `E2E ${stem} ${suffix}`;
}

/** A username: `e2e-{stem}-{suffix}`, with the stem in lower case. */
export function username(stem: string, suffix: string): string {
  return `e2e-${stem.toLowerCase()}-${suffix}`;
}

/** An email: `e2e-{stem}-{suffix}@cce.local`, with the stem in lower case. */
export function email(stem: string, suffix: string): string {
  return `${username(stem, suffix)}@cce.local`;
}

/** An access token for the test administrator from the local-only client `cce-e2e`. */
export async function apiToken(): Promise<string> {
  const { username: user, password } = adminCredentials();
  const response = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'cce-e2e',
      username: user,
      password,
    }),
  });
  if (!response.ok) {
    throw new Error(`The token request to ${TOKEN_URL} failed with HTTP ${response.status}.`);
  }
  const body = (await response.json()) as { access_token: string };
  return body.access_token;
}

/** One call to the service; returns the success envelope, or throws with the error's code. */
export async function apiCall<T>(
  token: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<SuccessEnvelope<T>> {
  const response = await fetch(`${SERVICE_URL}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    let code = 'no envelope';
    try {
      code = (JSON.parse(text) as ErrorEnvelope).error.code;
    } catch {
      // Not the service's error envelope; the status is enough.
    }
    throw new Error(`${method} ${path} failed with HTTP ${response.status} (${code}): ${text}`);
  }
  return JSON.parse(text) as SuccessEnvelope<T>;
}

/** Test data through the API, named with the test's suffix. */
export class Api {
  constructor(
    private readonly token: string,
    private readonly suffix: string,
  ) {}

  async createRole(stem: string): Promise<Role> {
    const { data } = await apiCall<Role>(this.token, 'POST', '/roles', {
      name: roleName(stem, this.suffix),
      displayName: roleDisplayName(stem, this.suffix),
    });
    return data;
  }

  async setRolePermissions(id: string, rows: PermissionRowInput[]): Promise<RolePermissions> {
    const { data } = await apiCall<RolePermissions>(this.token, 'PUT', `/roles/${id}/permissions`, {
      permissions: rows,
    });
    return data;
  }

  async createUser(stem: string, roleIds: string[]): Promise<User> {
    const { data } = await apiCall<User>(this.token, 'POST', '/users', {
      username: username(stem, this.suffix),
      email: email(stem, this.suffix),
      firstName: 'E2E',
      lastName: stem,
      roleIds,
    });
    return data;
  }

  async getUser(id: string): Promise<User> {
    const { data } = await apiCall<User>(this.token, 'GET', `/users/${id}`);
    return data;
  }
}

// The second fixture argument is named `provide`, not `use`, so the react-hooks lint rule does not
// take it for React's `use` hook.
export const test = base.extend<{ suffix: string; api: Api }>({
  /** Digits only: `Date.now()` followed by three random digits (16 digits). */
  // eslint-disable-next-line no-empty-pattern -- Playwright requires the fixtures argument.
  suffix: async ({}, provide) => {
    const random = String(Math.floor(Math.random() * 1000)).padStart(3, '0');
    await provide(`${Date.now()}${random}`);
  },
  api: async ({ suffix }, provide) => {
    await provide(new Api(await apiToken(), suffix));
  },
});

export { expect };

/** Switches the stand-in gateway (admin-service §9.3); no token. */
export async function setStubMode(mode: 'ok' | 'fail'): Promise<void> {
  const response = await fetch(STUB_MODE_URL, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/plain' },
    body: mode,
  });
  if (!response.ok) {
    throw new Error(`PUT ${STUB_MODE_URL} (${mode}) failed with HTTP ${response.status}.`);
  }
}

/** The header shows the test administrator's username (F1 exit check, "Header shows /me"). */
export async function expectHeaderUser(page: Page): Promise<void> {
  await expect(
    page.getByRole('banner').getByText(adminCredentials().username, { exact: true }),
  ).toBeVisible();
}
