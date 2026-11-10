import { http, HttpResponse, type DefaultBodyType, type PathParams } from 'msw';
import { getToken, login, refreshToken } from '../../auth/keycloak';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { server } from '../../test/mocks/server';
import { ApiError, CLIENT_NETWORK_ERROR, CLIENT_UNEXPECTED_RESPONSE } from '../ApiError';
import { apiDelete, apiRequest } from '../client';
import type { ErrorEnvelope, SuccessEnvelope } from '../contract';

const URL_BASE = 'http://localhost:3002/admin/v1';

/** Runs `promise` and returns the ApiError it rejects with. */
async function apiErrorOf(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Expected the request to fail.');
}

describe('apiRequest', () => {
  it('builds the URL and leaves out undefined, null and empty query parameters', async () => {
    let seen = '';
    server.use(
      http.get(`${URL_BASE}/things`, ({ request }) => {
        seen = request.url;
        return okResponse([]);
      }),
    );
    await apiRequest('GET', '/things', {
      query: { a: 'x y', b: 2, c: undefined, d: null, e: '' },
    });
    expect(seen).toBe(`${URL_BASE}/things?a=x+y&b=2`);
  });

  it('sends the bearer token and Accept, and Content-Type only with a body', async () => {
    const headers: Headers[] = [];
    let body: unknown;
    server.use(
      http.get(`${URL_BASE}/things`, ({ request }) => {
        headers.push(request.headers);
        return okResponse([]);
      }),
      http.post(`${URL_BASE}/things`, async ({ request }) => {
        headers.push(request.headers);
        body = await request.json();
        return okResponse({ id: '1' }, { status: 201 });
      }),
    );
    await apiRequest('GET', '/things');
    await apiRequest('POST', '/things', { body: { name: 'n' } });
    expect(headers[0]?.get('Authorization')).toBe('Bearer test-token');
    expect(headers[0]?.get('Accept')).toBe('application/json');
    expect(headers[0]?.get('Content-Type')).toBeNull();
    expect(headers[1]?.get('Authorization')).toBe('Bearer test-token');
    expect(headers[1]?.get('Content-Type')).toBe('application/json');
    expect(body).toEqual({ name: 'n' });
  });

  it('returns the envelope with pagination and warnings', async () => {
    const pagination = { page: 2, pageSize: 20, totalItems: 25, totalPages: 2 };
    const warnings = [{ code: 'ROLE_SYNC_FAILED' as const, message: 'Not synced.' }];
    server.use(http.get(`${URL_BASE}/things`, () => okResponse(['a'], { pagination, warnings })));
    await expect(apiRequest<string[]>('GET', '/things')).resolves.toEqual({
      data: ['a'],
      pagination,
      warnings,
    });
  });

  it.each([
    ['an empty body', () => new HttpResponse(null, { status: 200 })],
    ['an HTML body', () => HttpResponse.html('<html></html>', { status: 200 })],
    ['JSON without data', () => HttpResponse.json({ items: [] }, { status: 200 })],
    ['a 204', () => new HttpResponse(null, { status: 204 })],
  ])('turns a 2xx with %s into CLIENT_UNEXPECTED_RESPONSE', async (_name, respond) => {
    server.use(http.get(`${URL_BASE}/things`, respond));
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.code).toBe(CLIENT_UNEXPECTED_RESPONSE);
    expect(error.hasEnvelope).toBe(false);
    expect([200, 204]).toContain(error.status);
  });

  it('turns an error envelope into an ApiError with details and partialState', async () => {
    const details = [{ field: 'permissions[2].uriPattern', message: 'Invalid pattern.' }];
    const partialState = {
      operation: 'USER_CREATE' as const,
      entityId: 'e1',
      failedStep: 'ASSIGN_ROLES',
      completedSteps: ['CREATE_ACCOUNT'],
      keycloakUserId: 'k1',
      auditLogId: 'a1',
    };
    server.use(
      http.post(`${URL_BASE}/users`, () =>
        errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Keycloak failed.', { details, partialState }),
      ),
    );
    const error = await apiErrorOf(apiRequest('POST', '/users', { body: {} }));
    expect(error).toMatchObject({
      name: 'ApiError',
      status: 502,
      code: 'KEYCLOAK_SYNC_FAILED',
      message: 'Keycloak failed.',
      details,
      partialState,
      hasEnvelope: true,
    });
  });

  it('turns a non-envelope error body into CLIENT_UNEXPECTED_RESPONSE with the status', async () => {
    server.use(
      http.get(`${URL_BASE}/things`, () => HttpResponse.text('Bad gateway', { status: 504 })),
    );
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.status).toBe(504);
    expect(error.code).toBe(CLIENT_UNEXPECTED_RESPONSE);
    expect(error.hasEnvelope).toBe(false);
  });

  it('turns a failed fetch into CLIENT_NETWORK_ERROR with status 0', async () => {
    server.use(http.get(`${URL_BASE}/things`, () => HttpResponse.error()));
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.status).toBe(0);
    expect(error.code).toBe(CLIENT_NETWORK_ERROR);
  });

  it('calls login and throws a 401 when there is no token', async () => {
    vi.mocked(getToken).mockReturnValue(undefined);
    const spy = vi.fn(() => okResponse([]));
    server.use(http.get(`${URL_BASE}/things`, spy));
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.status).toBe(401);
    expect(login).toHaveBeenCalledTimes(1);
    expect(spy).not.toHaveBeenCalled();
  });

  it('refreshes the token once and repeats the request after a 401', async () => {
    let calls = 0;
    server.use(
      http.post<PathParams, DefaultBodyType, SuccessEnvelope<{ ok: boolean }> | ErrorEnvelope>(
        `${URL_BASE}/things`,
        () => {
          calls += 1;
          return calls === 1
            ? errorResponse(401, 'UNAUTHENTICATED', 'Expired.')
            : okResponse({ ok: true });
        },
      ),
    );
    await expect(apiRequest('POST', '/things', { body: { a: 1 } })).resolves.toEqual({
      data: { ok: true },
    });
    expect(calls).toBe(2);
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(login).not.toHaveBeenCalled();
  });

  it('throws the second 401 without calling login', async () => {
    let calls = 0;
    server.use(
      http.get(`${URL_BASE}/things`, () => {
        calls += 1;
        return errorResponse(401, 'UNAUTHENTICATED', 'Rejected.');
      }),
    );
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.status).toBe(401);
    expect(error.code).toBe('UNAUTHENTICATED');
    expect(calls).toBe(2);
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(login).not.toHaveBeenCalled();
  });

  it('throws the 401 without repeating when refreshToken returns false', async () => {
    vi.mocked(refreshToken).mockResolvedValue(false);
    let calls = 0;
    server.use(
      http.get(`${URL_BASE}/things`, () => {
        calls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const error = await apiErrorOf(apiRequest('GET', '/things'));
    expect(error.status).toBe(401);
    expect(error.code).toBe(CLIENT_UNEXPECTED_RESPONSE);
    expect(calls).toBe(1);
  });
});

describe('apiDelete', () => {
  it('accepts a 204', async () => {
    server.use(http.delete(`${URL_BASE}/roles/r1`, () => new HttpResponse(null, { status: 204 })));
    await expect(apiDelete('/roles/r1')).resolves.toBeUndefined();
  });

  it('throws an ApiError for an error envelope', async () => {
    server.use(
      http.delete(`${URL_BASE}/roles/r1`, () =>
        errorResponse(409, 'ROLE_IN_USE', 'The role is in use.'),
      ),
    );
    const error = await apiErrorOf(apiDelete('/roles/r1'));
    expect(error.code).toBe('ROLE_IN_USE');
    expect(error.status).toBe(409);
  });
});
