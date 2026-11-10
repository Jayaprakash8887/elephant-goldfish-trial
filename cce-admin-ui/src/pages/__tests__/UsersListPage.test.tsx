import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse, type PathParams } from 'msw';
import type { ChangeUserStatusRequest, PartialState } from '../../api/contract';
import { facilityAdminRole, superAdminRole, testAdmin, users } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const user01 = users.find((u) => u.username === 'user01')!;
const user02 = users.find((u) => u.username === 'user02')!;

function userListQueries() {
  return recordedRequests()
    .filter((r) => r.method === 'GET' && r.path === '/admin/v1/users')
    .map((r) => r.query);
}

function rowOf(username: string) {
  return screen.getByText(username, { selector: 'td span' }).closest('tr')!;
}

const resultArea = () => screen.getByRole('region', { name: 'Row action result' });

async function openList(path = '/users') {
  const rendered = renderRoute(path);
  await screen.findByText('user01', { selector: 'td span' });
  return rendered;
}

/** `PATCH /users/:id/status` answering with the user in the requested status. */
function serveStatusChange() {
  server.use(
    http.patch<PathParams, ChangeUserStatusRequest>(
      '*/admin/v1/users/:id/status',
      async ({ request, params }) => {
        await recordRequest(request);
        const body = await request.json();
        const user = users.find((u) => u.id === params.id)!;
        return okResponse({ ...user, status: body.status });
      },
    ),
  );
}

describe('UsersListPage', () => {
  it('lists users with their columns, row actions and paging footer', async () => {
    await openList();
    expect(screen.getByRole('heading', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New user' })).toHaveAttribute('href', '/users/new');

    const admin = rowOf('cce-admin');
    expect(within(admin).getByText('cce-admin@cce.local')).toBeInTheDocument();
    expect(within(admin).getByText('Active')).toBeInTheDocument();
    expect(within(admin).getByText('Super administrator')).toBeInTheDocument();
    expect(within(admin).getByText('—')).toBeInTheDocument();
    expect(within(admin).getByText('2026-11-10 09:15')).toBeInTheDocument();
    expect(within(admin).getByRole('link', { name: 'View / edit' })).toHaveAttribute(
      'href',
      `/users/${testAdmin.id}`,
    );
    expect(
      within(admin)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Deactivate', 'Lock', 'Send set-password email']);

    // user03: LOCKED, never signed in, facility scope FAC-01 is on even numbers only.
    const locked = rowOf('user03');
    expect(within(locked).getByText('Locked')).toBeInTheDocument();
    expect(within(locked).getByText('Never')).toBeInTheDocument();
    expect(
      within(locked)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Activate', 'Deactivate', 'Send set-password email']);
    expect(within(rowOf('user02')).getByText('Inactive')).toBeInTheDocument();
    expect(within(rowOf('user02')).getByText('FAC-01')).toBeInTheDocument();

    expect(screen.getByText('Page 1 of 2 · 25 items')).toBeInTheDocument();
    expect(userListQueries()).toEqual([{ page: '1', pageSize: '20' }]);
  });

  describe('filters and paging', () => {
    it('sends each filter and page change as its query parameter', async () => {
      const { user, router } = await openList();

      await user.selectOptions(screen.getByLabelText('Status'), 'INACTIVE');
      await waitFor(() =>
        expect(userListQueries()).toContainEqual({ status: 'INACTIVE', page: '1', pageSize: '20' }),
      );
      await user.selectOptions(screen.getByLabelText('Role'), facilityAdminRole.id);
      await waitFor(() =>
        expect(userListQueries()).toContainEqual({
          status: 'INACTIVE',
          roleId: facilityAdminRole.id,
          page: '1',
          pageSize: '20',
        }),
      );
      expect(router.state.location.search).toBe(
        `?status=INACTIVE&page=1&roleId=${facilityAdminRole.id}`,
      );

      await user.selectOptions(screen.getByLabelText('Status'), '');
      await user.selectOptions(screen.getByLabelText('Role'), '');
      await screen.findByText('Page 1 of 2 · 25 items');
      await user.click(screen.getByRole('button', { name: 'Next' }));
      expect(await screen.findByText('Page 2 of 2 · 25 items')).toBeInTheDocument();
      expect(screen.getByText('user24', { selector: 'td span' })).toBeInTheDocument();
      await user.selectOptions(screen.getByLabelText('Page size'), '50');
      expect(await screen.findByText('Page 1 of 1 · 25 items')).toBeInTheDocument();

      expect(userListQueries()).toEqual([
        { page: '1', pageSize: '20' },
        { status: 'INACTIVE', page: '1', pageSize: '20' },
        { status: 'INACTIVE', roleId: facilityAdminRole.id, page: '1', pageSize: '20' },
        { roleId: facilityAdminRole.id, page: '1', pageSize: '20' },
        // `?page=1&pageSize=20` without filters comes from the cache (staleTime 30 s).
        { page: '2', pageSize: '20' },
        { page: '1', pageSize: '50' },
      ]);
    });

    it('sets page to 1 when a filter changes', async () => {
      const { user, router } = renderRoute('/users?page=2');
      await screen.findByText('Page 2 of 2 · 25 items');
      await user.selectOptions(screen.getByLabelText('Status'), 'LOCKED');
      await waitFor(() => expect(router.state.location.search).toBe('?page=1&status=LOCKED'));
      await waitFor(() =>
        expect(userListQueries()).toEqual([
          { page: '2', pageSize: '20' },
          { status: 'LOCKED', page: '1', pageSize: '20' },
        ]),
      );
    });

    it('waits 300 ms after the last keystroke before writing search and facility scope', async () => {
      const { user, router } = renderRoute('/users?page=2');
      await screen.findByText('Page 2 of 2 · 25 items');

      await user.type(screen.getByLabelText('Search'), 'user0');
      expect(router.state.location.search).toBe('?page=2');
      await waitFor(() => expect(router.state.location.search).toBe('?page=1&q=user0'));

      await user.type(screen.getByLabelText('Facility scope'), ' FAC-01 ');
      await waitFor(() =>
        expect(router.state.location.search).toBe('?page=1&q=user0&facilityScope=FAC-01'),
      );
      await waitFor(() => expect(userListQueries()).toHaveLength(3));
      // One request per settled draft, none for the keystrokes in between.
      expect(userListQueries()).toEqual([
        { page: '2', pageSize: '20' },
        { q: 'user0', page: '1', pageSize: '20' },
        { q: 'user0', facilityScope: 'FAC-01', page: '1', pageSize: '20' },
      ]);
    });

    it('reads the filters from the URL', async () => {
      renderRoute(`/users?status=ACTIVE&roleId=${facilityAdminRole.id}&facilityScope=F&q=jane`);
      await screen.findByText('user01', { selector: 'td span' });
      expect(screen.getByLabelText('Status')).toHaveValue('ACTIVE');
      expect(screen.getByLabelText('Search')).toHaveValue('jane');
      expect(screen.getByLabelText('Facility scope')).toHaveValue('F');
      await waitFor(() => expect(screen.getByLabelText('Role')).toHaveValue(facilityAdminRole.id));
      expect(userListQueries()).toEqual([
        {
          status: 'ACTIVE',
          roleId: facilityAdminRole.id,
          facilityScope: 'F',
          q: 'jane',
          page: '1',
          pageSize: '20',
        },
      ]);
    });

    it('does not send a status that is not one of the three', async () => {
      renderRoute('/users?status=DELETED');
      await screen.findByText('user01', { selector: 'td span' });
      expect(screen.getByLabelText('Status')).toHaveValue('');
      expect(userListQueries()).toEqual([{ page: '1', pageSize: '20' }]);
    });

    it('corrects invalid paging in the URL before any request', async () => {
      const { router } = renderRoute('/users?page=0&pageSize=30&q=x');
      await screen.findByText('user01', { selector: 'td span' });
      expect(router.state.location.search).toBe('?page=1&pageSize=20&q=x');
      expect(userListQueries()).toEqual([{ q: 'x', page: '1', pageSize: '20' }]);
    });

    it('goes to the last page when the page is past it', async () => {
      const { router } = renderRoute('/users?page=5');
      expect(await screen.findByText('Page 2 of 2 · 25 items')).toBeInTheDocument();
      expect(router.state.location.search).toBe('?page=2');
      expect(userListQueries()).toEqual([
        { page: '5', pageSize: '20' },
        { page: '2', pageSize: '20' },
      ]);
    });

    it('shows the empty state when no user matches', async () => {
      server.use(
        http.get('*/admin/v1/users', () =>
          okResponse([], { pagination: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } }),
        ),
      );
      renderRoute('/users?q=nobody');
      expect(await screen.findByText('No users on this page.')).toBeInTheDocument();
    });

    it('offers only assignable roles in the role filter, by display name', async () => {
      await openList();
      const select = screen.getByLabelText('Role');
      await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(3));
      expect(
        within(select)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['All', 'Facility administrator', 'Super administrator']);
      expect(within(select).queryByRole('option', { name: 'Admin service account' })).toBeNull();
      expect(screen.queryByText('Showing the first 500 roles.')).not.toBeInTheDocument();
    });

    it('says when only the first 500 roles are offered', async () => {
      server.use(
        http.get('*/admin/v1/roles', ({ request }) => {
          const page = Number(new URL(request.url).searchParams.get('page'));
          return okResponse(page === 1 ? [superAdminRole, facilityAdminRole] : [], {
            pagination: { page, pageSize: 100, totalItems: 600, totalPages: 6 },
          });
        }),
      );
      await openList();
      expect(await screen.findByText('Showing the first 500 roles.')).toBeInTheDocument();
    });
  });

  describe('errors of GET /users', () => {
    it('shows a 500 inline with Retry', async () => {
      server.use(http.get('*/admin/v1/users', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
      const { user } = renderRoute('/users');
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
      server.resetHandlers();
      await user.click(within(alert).getByRole('button', { name: 'Retry' }));
      expect(await screen.findByText('user01', { selector: 'td span' })).toBeInTheDocument();
    });

    describe('403 shown inline (F-04)', () => {
      it.each([
        [
          'with the envelope',
          () => errorResponse(403, 'FORBIDDEN', 'Your roles do not allow this.'),
        ],
        ['without the envelope', () => new HttpResponse('Forbidden', { status: 403 })],
      ])('%s: the inline message, and the shell still works', async (_case, answer) => {
        server.use(http.get('*/admin/v1/users', answer));
        const { user, router } = renderRoute('/users');
        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('You do not have permission to do this.');
        expect(screen.getByLabelText('Search')).toBeInTheDocument();
        expect(within(screen.getByRole('banner')).getByText('cce-admin')).toBeInTheDocument();
        await user.click(screen.getByRole('link', { name: 'Roles' }));
        expect(router.state.location.pathname).toBe('/roles');
      });
    });
  });

  describe('row actions', () => {
    it.each([
      [
        'Deactivate',
        user01,
        'INACTIVE',
        'Their Keycloak account is disabled and their sessions end. Their roles are kept.',
        'user01 is now Inactive.',
      ],
      [
        'Lock',
        user01,
        'LOCKED',
        'Their Keycloak account is disabled and their sessions end until you activate it again. Their roles are kept.',
        'user01 is now Locked.',
      ],
      [
        'Activate',
        user02,
        'ACTIVE',
        'Their Keycloak account is enabled again, with the roles they hold.',
        'user02 is now Active.',
      ],
    ] as const)(
      '%s asks for confirmation, sends PATCH status and refetches the list',
      async (label, target, status, body, success) => {
        serveStatusChange();
        const { user } = await openList();
        await user.click(within(rowOf(target.username)).getByRole('button', { name: label }));
        const dialog = screen.getByRole('dialog', { name: `${label} ${target.username}?` });
        expect(dialog).toHaveTextContent(body);
        await user.click(within(dialog).getByRole('button', { name: label }));
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
        expect(within(resultArea()).getByText(target.username)).toBeInTheDocument();
        expect(within(resultArea()).getByRole('status')).toHaveTextContent(success);
        expect(
          recordedRequests()
            .filter((r) => r.method === 'PATCH')
            .map((r) => [r.path, r.body]),
        ).toEqual([[`/admin/v1/users/${target.id}/status`, { status }]]);
        await waitFor(() => expect(userListQueries()).toHaveLength(2));
      },
    );

    it('Cancel sends nothing', async () => {
      const { user } = await openList();
      await user.click(within(rowOf('user01')).getByRole('button', { name: 'Deactivate' }));
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(recordedRequests().filter((r) => r.method !== 'GET')).toEqual([]);
    });

    it('shows LAST_SYSTEM_ADMIN inline in the message area, and does not refetch', async () => {
      server.use(
        http.patch('*/admin/v1/users/:id/status', () =>
          errorResponse(
            409,
            'LAST_SYSTEM_ADMIN',
            'cce-admin is the last active user holding SUPER_ADMIN.',
          ),
        ),
      );
      const { user } = await openList();
      await user.click(within(rowOf('cce-admin')).getByRole('button', { name: 'Deactivate' }));
      await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Deactivate' }),
      );
      const area = await screen.findByRole('region', { name: 'Row action result' });
      expect(within(area).getByText('cce-admin')).toBeInTheDocument();
      expect(within(area).getByRole('alert')).toHaveTextContent(
        /^cce-admin is the last active user holding SUPER_ADMIN\.$/,
      );
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(userListQueries()).toHaveLength(1);
    });

    it('shows a 502 USER_STATUS_CHANGE with SyncFailedNotice and the operator ids', async () => {
      const partialState: PartialState = {
        operation: 'USER_STATUS_CHANGE',
        entityId: user01.id,
        failedStep: 'LOCAL_STATUS_SAVED',
        completedSteps: ['KEYCLOAK_ENABLED_CHANGED', 'KEYCLOAK_SESSIONS_ENDED'],
        keycloakUserId: user01.keycloakId,
        auditLogId: '50000000-0000-4000-8000-000000000002',
      };
      server.use(
        http.patch('*/admin/v1/users/:id/status', () =>
          errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Saving the status failed.', {
            partialState,
          }),
        ),
      );
      const { user } = await openList();
      await user.click(within(rowOf('user01')).getByRole('button', { name: 'Lock' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Lock' }));
      const notice = await within(await screen.findByRole('region')).findByRole('alert');
      expect(notice).toHaveTextContent('Saving the status failed.');
      expect(notice).toHaveTextContent(
        'Keycloak may already have the new status, but it was not saved here. Choose the same status again.',
      );
      expect(notice).toHaveTextContent(`Keycloak user id: ${user01.keycloakId}`);
      // The refetch rule runs after a 502; the button stays usable to choose the same status.
      await waitFor(() => expect(userListQueries()).toHaveLength(2));
      expect(within(rowOf('user01')).getByRole('button', { name: 'Lock' })).toBeEnabled();
    });

    it('sends the set-password email after confirmation and shows the address', async () => {
      server.use(
        http.post('*/admin/v1/users/:id/reset-password', async ({ request }) => {
          await recordRequest(request);
          return okResponse({ userId: user01.id, email: 'user01@cce.example' });
        }),
      );
      const { user } = await openList();
      await user.click(
        within(rowOf('user01')).getByRole('button', { name: 'Send set-password email' }),
      );
      const dialog = screen.getByRole('dialog', { name: 'Send a set-password email to user01?' });
      expect(dialog).toHaveTextContent(
        'Keycloak sends an email to user01@cce.local with a link to set a new password.',
      );
      await user.click(within(dialog).getByRole('button', { name: 'Send' }));
      expect(await within(resultArea()).findByRole('status')).toHaveTextContent(
        'Set-password email sent to user01@cce.example.',
      );
      expect(within(resultArea()).getByText('user01')).toBeInTheDocument();
      expect(
        recordedRequests()
          .filter((r) => r.method === 'POST')
          .map((r) => [r.path, r.body]),
      ).toEqual([[`/admin/v1/users/${user01.id}/reset-password`, undefined]]);
    });

    it('shows a 502 USER_PASSWORD_RESET with the empty-steps text', async () => {
      server.use(
        http.post('*/admin/v1/users/:id/reset-password', () =>
          errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Keycloak did not send the email.', {
            partialState: {
              operation: 'USER_PASSWORD_RESET',
              entityId: user01.id,
              failedStep: 'KEYCLOAK_ACTION_EMAIL_SENT',
              completedSteps: [],
              keycloakUserId: null,
              auditLogId: null,
            },
          }),
        ),
      );
      const { user } = await openList();
      await user.click(
        within(rowOf('user01')).getByRole('button', { name: 'Send set-password email' }),
      );
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send' }));
      const notice = await within(await screen.findByRole('region')).findByRole('alert');
      expect(notice).toHaveTextContent('Keycloak did not send the email.');
      expect(notice).toHaveTextContent('The service recorded no change. You can try again.');
      expect(notice).not.toHaveTextContent('For the operator:');
    });

    it('shows a row action 403 inline in the message area', async () => {
      server.use(
        http.patch('*/admin/v1/users/:id/status', () =>
          errorResponse(403, 'FORBIDDEN', 'Your roles do not allow PATCH.'),
        ),
      );
      const { user } = await openList();
      await user.click(within(rowOf('user01')).getByRole('button', { name: 'Lock' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Lock' }));
      expect(await within(await screen.findByRole('region')).findByRole('alert')).toHaveTextContent(
        'You do not have permission to do this.Your roles do not allow PATCH.',
      );
    });
  });
});
