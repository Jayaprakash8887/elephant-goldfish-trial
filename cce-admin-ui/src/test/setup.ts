import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { resetFakeAuth } from './fakeAuth';
import { clearRequests } from './mocks/requests';
import { server } from './mocks/server';

// UI-AD-01: every test file gets the fake auth module instead of the keycloak-js one.
vi.mock('../auth/keycloak', () => import('./fakeAuth'));

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
  clearRequests();
  resetFakeAuth();
});

afterAll(() => {
  server.close();
});
