import { renderHook, waitFor } from '@testing-library/react';
import { http } from 'msw';
import type { Role } from '../../api/contract';
import { ApiError } from '../../api/ApiError';
import { adminServiceRole, facilityAdminRole } from '../../test/mocks/fixtures';
import { okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { createWrapper } from '../../test/render';
import { useAllRoles, useRoleList } from '../useRoles';

function role(n: number, extra: Partial<Role> = {}): Role {
  return {
    ...facilityAdminRole,
    id: `20000000-0000-4000-8000-0000000${String(n).padStart(5, '0')}`,
    name: `ROLE_${n}`,
    ...extra,
  };
}

/** Answers `GET /roles` with `pages[page - 1]` and the given `totalPages`. */
function servePages(pages: Role[][], totalPages = pages.length) {
  server.use(
    http.get('*/admin/v1/roles', async ({ request }) => {
      await recordRequest(request);
      const params = new URL(request.url).searchParams;
      const page = Number(params.get('page'));
      const pageSize = Number(params.get('pageSize'));
      return okResponse(pages[page - 1] ?? [], {
        pagination: { page, pageSize, totalItems: totalPages * pageSize, totalPages },
      });
    }),
  );
}

function requestedPages() {
  return recordedRequests().map((request) => request.query);
}

describe('useAllRoles', () => {
  it('joins three pages in order', async () => {
    servePages([[role(1), role(2)], [role(3)], [role(4), role(5)]]);
    const { result } = renderHook(() => useAllRoles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items.map((r) => r.name)).toEqual([
      'ROLE_1',
      'ROLE_2',
      'ROLE_3',
      'ROLE_4',
      'ROLE_5',
    ]);
    expect(result.current.data?.truncated).toBe(false);
    expect(requestedPages()).toEqual([
      { page: '1', pageSize: '100' },
      { page: '2', pageSize: '100' },
      { page: '3', pageSize: '100' },
    ]);
  });

  it('drops repeated ids', async () => {
    // A role created between two page reads shifts the next page by one.
    servePages([
      [role(1), role(2)],
      [role(2), role(3)],
    ]);
    const { result } = renderHook(() => useAllRoles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items.map((r) => r.name)).toEqual(['ROLE_1', 'ROLE_2', 'ROLE_3']);
  });

  it('stops at five pages and reports truncated', async () => {
    const pages = Array.from({ length: 7 }, (_, i) => [role(i + 1)]);
    servePages(pages);
    const { result } = renderHook(() => useAllRoles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toHaveLength(5);
    expect(result.current.data?.truncated).toBe(true);
    expect(requestedPages().map((query) => query.page)).toEqual(['1', '2', '3', '4', '5']);
  });

  it('returns roles that are not assignable', async () => {
    const { result } = renderHook(() => useAllRoles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toContainEqual(adminServiceRole);
    expect(adminServiceRole.isAssignable).toBe(false);
  });

  it('reads one page when the service has no roles', async () => {
    servePages([[]], 0);
    const { result } = renderHook(() => useAllRoles(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ items: [], truncated: false });
    expect(recordedRequests()).toHaveLength(1);
  });
});

describe('useRoleList', () => {
  it('fails with CLIENT_UNEXPECTED_RESPONSE when the pagination block is missing', async () => {
    server.use(http.get('*/admin/v1/roles', () => okResponse([facilityAdminRole])));
    const { result } = renderHook(() => useRoleList({ page: 1, pageSize: 20 }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeInstanceOf(ApiError);
    expect((result.current.error as ApiError).code).toBe('CLIENT_UNEXPECTED_RESPONSE');
  });
});
