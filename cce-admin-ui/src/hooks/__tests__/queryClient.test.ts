import { MutationObserver, QueryObserver, type QueryClient } from '@tanstack/react-query';
import { ApiError, CLIENT_NETWORK_ERROR } from '../../api/ApiError';
import { createQueryClient } from '../queryClient';
import { queryKeys } from '../queryKeys';

function apiError(status: number, code: string, hasEnvelope = true): ApiError {
  return new ApiError({ status, code, message: 'm', hasEnvelope });
}

/** Subscribes to a query and resolves when it settles; returns the number of attempts. */
async function runQuery(client: QueryClient, error: ApiError): Promise<number> {
  const queryFn = vi.fn(() => Promise.reject(error));
  const observer = new QueryObserver(client, { queryKey: ['test'], queryFn, retryDelay: 0 });
  await new Promise<void>((resolve) => {
    const unsubscribe = observer.subscribe((result) => {
      if (result.status === 'error') {
        unsubscribe();
        resolve();
      }
    });
  });
  return queryFn.mock.calls.length;
}

/** An active query whose fetch count tells whether the refetch rule ran. */
async function activeQuery(client: QueryClient) {
  const queryFn = vi.fn(() => Promise.resolve('value'));
  const observer = new QueryObserver(client, { queryKey: ['active'], queryFn });
  const unsubscribe = observer.subscribe(() => undefined);
  await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(1));
  return { queryFn, unsubscribe };
}

async function runMutation(client: QueryClient, outcome: ApiError | 'ok'): Promise<void> {
  const observer = new MutationObserver(client, {
    mutationFn: () => (outcome === 'ok' ? Promise.resolve('done') : Promise.reject(outcome)),
  });
  await observer.mutate().catch(() => undefined);
}

describe('createQueryClient', () => {
  it.each([
    ['status 0', apiError(0, CLIENT_NETWORK_ERROR, false)],
    ['a 503', apiError(503, 'CLIENT_UNEXPECTED_RESPONSE', false)],
  ])('retries a query once on %s', async (_name, error) => {
    expect(await runQuery(createQueryClient(), error)).toBe(2);
  });

  it.each([
    ['a 400', apiError(400, 'VALIDATION_ERROR')],
    ['a 409', apiError(409, 'ROLE_IN_USE')],
  ])('does not retry a query on %s', async (_name, error) => {
    expect(await runQuery(createQueryClient(), error)).toBe(1);
  });

  it('does not retry an error that is not an ApiError', async () => {
    const client = createQueryClient();
    const queryFn = vi.fn(() => Promise.reject(new Error('bug')));
    const observer = new QueryObserver(client, { queryKey: ['bug'], queryFn, retryDelay: 0 });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(observer.getCurrentResult().status).toBe('error'));
    unsubscribe();
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  it('does not retry when created with retry: false', async () => {
    expect(await runQuery(createQueryClient({ retry: false }), apiError(503, 'X', false))).toBe(1);
  });

  it('uses the template defaults for queries and no retry for mutations', () => {
    const defaults = createQueryClient().getDefaultOptions();
    expect(defaults.queries?.staleTime).toBe(30_000);
    expect(defaults.queries?.refetchOnWindowFocus).toBe(false);
    expect(defaults.mutations?.retry).toBe(0);
  });

  it.each([
    ['a success', 'ok' as const],
    ['a 502 KEYCLOAK_SYNC_FAILED', apiError(502, 'KEYCLOAK_SYNC_FAILED')],
    ['a network error', apiError(0, CLIENT_NETWORK_ERROR, false)],
    ['an unexpected error', apiError(500, 'INTERNAL_ERROR')],
  ])('refetches active queries after %s', async (_name, outcome) => {
    const client = createQueryClient({ retry: false });
    const { queryFn, unsubscribe } = await activeQuery(client);
    await runMutation(client, outcome);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    unsubscribe();
  });

  it.each([
    ['a 409 with the envelope', apiError(409, 'ROLE_IN_USE')],
    ['a 403 with the envelope', apiError(403, 'FORBIDDEN')],
    ['a 400 with the envelope', apiError(400, 'VALIDATION_ERROR')],
  ])('does not refetch after %s', async (_name, outcome) => {
    const client = createQueryClient({ retry: false });
    const { queryFn, unsubscribe } = await activeQuery(client);
    await runMutation(client, outcome);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(queryFn).toHaveBeenCalledTimes(1);
    unsubscribe();
  });
});

describe('queryKeys', () => {
  it('returns every key of the design', () => {
    expect(queryKeys.me()).toEqual(['me']);
    expect(queryKeys.userList({ q: 'a', page: 2 })).toEqual(['users', 'list', { q: 'a', page: 2 }]);
    expect(queryKeys.userDetail('u1')).toEqual(['users', 'detail', 'u1']);
    expect(queryKeys.roleList({ page: 1, pageSize: 20 })).toEqual([
      'roles',
      'list',
      { page: 1, pageSize: 20 },
    ]);
    expect(queryKeys.allRoles()).toEqual(['roles', 'all']);
    expect(queryKeys.roleDetail('r1')).toEqual(['roles', 'detail', 'r1']);
    expect(queryKeys.roleMenus('r1')).toEqual(['roles', 'r1', 'menus']);
    expect(queryKeys.rolePermissions('r1')).toEqual(['roles', 'r1', 'permissions']);
    expect(queryKeys.menuTree()).toEqual(['menus', 'tree']);
  });
});
