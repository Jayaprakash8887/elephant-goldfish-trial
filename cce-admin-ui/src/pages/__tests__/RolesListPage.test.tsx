import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { Role } from '../../api/contract';
import { createdRole, facilityAdminRole, roles, superAdminRole } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const NAME_WARNING =
  'The name cannot be changed later. It is also the Keycloak realm role name and the gateway permission name.';

const unusedRole: Role = {
  ...facilityAdminRole,
  id: '20000000-0000-4000-8000-000000000004',
  name: 'REPORT_VIEWER',
  displayName: 'Report viewer',
  description: null,
  syncStatus: 'FAILED',
  userCount: 0,
};

function requests(method: string, path = '/admin/v1/roles') {
  return recordedRequests().filter((r) => r.method === method && r.path === path);
}

function rowOf(name: string) {
  const cell = screen.getByText(name, { selector: 'td span' });
  return cell.closest('tr')!;
}

async function fillCreateForm(
  user: ReturnType<typeof renderRoute>['user'],
  values: { name: string; displayName: string; description?: string },
) {
  const form = screen.getByRole('form', { name: 'Create role' });
  await user.type(within(form).getByLabelText('Name'), values.name);
  await user.type(within(form).getByLabelText('Display name'), values.displayName);
  if (values.description) {
    await user.type(within(form).getByLabelText('Description'), values.description);
  }
  await user.click(within(form).getByRole('button', { name: 'Create role' }));
}

describe('RolesListPage', () => {
  it('lists roles with their columns, badges and actions', async () => {
    server.use(
      http.get('*/admin/v1/roles', ({ request }) => {
        void recordRequest(request);
        return okResponse([...roles, unusedRole], {
          pagination: { page: 1, pageSize: 20, totalItems: 4, totalPages: 1 },
        });
      }),
    );
    renderRoute('/roles');
    expect(await screen.findByText('FACILITY_ADMIN')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Roles' })).toBeInTheDocument();

    const facility = rowOf('FACILITY_ADMIN');
    expect(within(facility).getByText('Facility administrator')).toBeInTheDocument();
    expect(within(facility).getByText('Manages users of one facility.')).toBeInTheDocument();
    expect(within(facility).getByText('24')).toBeInTheDocument();
    expect(within(facility).getByText('Synced · 2026-11-10 09:20')).toBeInTheDocument();
    expect(within(facility).getByRole('link', { name: 'Open' })).toHaveAttribute(
      'href',
      `/roles/${facilityAdminRole.id}`,
    );

    const service = rowOf('CCE_ADMIN_SERVICE');
    expect(within(service).getByText('Service account, cannot be assigned')).toBeInTheDocument();
    expect(within(service).getByRole('img', { name: 'System role' })).toBeInTheDocument();
    expect(within(service).getByText('Never synced')).toBeInTheDocument();

    expect(within(rowOf('REPORT_VIEWER')).getByText(/^Sync failed · /)).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 1 · 4 items')).toBeInTheDocument();
  });

  it('shows a GET /roles 500 inline with Retry', async () => {
    server.use(http.get('*/admin/v1/roles', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
    const { user } = renderRoute('/roles');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('FACILITY_ADMIN')).toBeInTheDocument();
  });

  describe('403 shown inline (F-04)', () => {
    it.each([
      ['with the envelope', () => errorResponse(403, 'FORBIDDEN', 'Your roles do not allow this.')],
      ['without the envelope', () => new HttpResponse('Forbidden', { status: 403 })],
    ])('%s: the inline message, and the shell still works', async (_case, answer) => {
      server.use(http.get('*/admin/v1/roles', answer));
      const { user, router } = renderRoute('/roles');
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('You do not have permission to do this.');
      expect(screen.getByRole('form', { name: 'Create role' })).toBeInTheDocument();
      expect(within(screen.getByRole('banner')).getByText('cce-admin')).toBeInTheDocument();
      await user.click(screen.getByRole('link', { name: 'Menus' }));
      expect(router.state.location.pathname).toBe('/menus');
    });

    it('shows the server message after the fixed text when there is one', async () => {
      server.use(
        http.get('*/admin/v1/roles', () =>
          errorResponse(403, 'FORBIDDEN', 'Your roles do not allow GET /admin/v1/roles.'),
        ),
      );
      renderRoute('/roles');
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'You do not have permission to do this.Your roles do not allow GET /admin/v1/roles.',
      );
    });
  });

  describe('paging', () => {
    function serveManyPages() {
      server.use(
        http.get('*/admin/v1/roles', async ({ request }) => {
          await recordRequest(request);
          const params = new URL(request.url).searchParams;
          const page = Number(params.get('page'));
          const pageSize = Number(params.get('pageSize'));
          const totalPages = pageSize === 20 ? 3 : 1;
          return okResponse(page <= totalPages ? roles : [], {
            pagination: { page, pageSize, totalItems: 45, totalPages },
          });
        }),
      );
    }

    it('requests page 1 of 20 by default, and follows Next, Previous and the page size', async () => {
      serveManyPages();
      const { user, router } = renderRoute('/roles');
      expect(await screen.findByText('Page 1 of 3 · 45 items')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Next' }));
      expect(await screen.findByText('Page 2 of 3 · 45 items')).toBeInTheDocument();
      expect(router.state.location.search).toBe('?page=2');

      await user.click(screen.getByRole('button', { name: 'Previous' }));
      expect(await screen.findByText('Page 1 of 3 · 45 items')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Next' }));
      await screen.findByText('Page 2 of 3 · 45 items');
      await user.selectOptions(screen.getByLabelText('Page size'), '50');
      expect(await screen.findByText('Page 1 of 1 · 45 items')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
      expect(router.state.location.search).toBe('?page=1&pageSize=50');

      expect(requests('GET').map((r) => r.query)).toEqual([
        { page: '1', pageSize: '20' },
        { page: '2', pageSize: '20' },
        // Pages 1 and 2 of 20 come from the cache when revisited (staleTime 30 s).
        { page: '1', pageSize: '50' },
      ]);
    });

    it('corrects invalid paging in the URL before any request', async () => {
      const { router } = renderRoute('/roles?page=abc&pageSize=7');
      await screen.findByText('FACILITY_ADMIN');
      expect(router.state.location.search).toBe('?page=1&pageSize=20');
      expect(requests('GET').map((r) => r.query)).toEqual([{ page: '1', pageSize: '20' }]);
    });

    it('goes to the last page when the page is past it', async () => {
      serveManyPages();
      const { router } = renderRoute('/roles?page=9');
      expect(await screen.findByText('Page 3 of 3 · 45 items')).toBeInTheDocument();
      expect(router.state.location.search).toBe('?page=3');
    });

    it('shows the empty state for a page without roles', async () => {
      server.use(
        http.get('*/admin/v1/roles', () =>
          okResponse([], { pagination: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } }),
        ),
      );
      renderRoute('/roles');
      expect(await screen.findByText('No roles on this page.')).toBeInTheDocument();
    });
  });

  describe('create', () => {
    it('validates the name rule as the user types and sends nothing for an invalid name', async () => {
      const { user } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      const name = screen.getByLabelText('Name');
      await user.type(name, 'facility admin');
      expect(name).toHaveAttribute('aria-invalid', 'true');
      expect(name).toHaveAccessibleDescription(expect.stringContaining('Upper-case letters'));

      await user.type(screen.getByLabelText('Display name'), 'Facility admin');
      await user.click(screen.getByRole('button', { name: 'Create role' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

      await user.clear(name);
      await user.type(name, 'FACILITY_ADMIN');
      expect(name).not.toHaveAttribute('aria-invalid');
      expect(requests('POST')).toEqual([]);
    });

    it('shows the permanent-name warning, and Cancel sends nothing', async () => {
      const { user } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      expect(screen.getByLabelText('Name')).toHaveAccessibleDescription(
        expect.stringContaining(NAME_WARNING),
      );
      await fillCreateForm(user, { name: ' NEW_ROLE ', displayName: 'New role' });
      const dialog = screen.getByRole('dialog', { name: 'Create NEW_ROLE?' });
      expect(dialog).toHaveTextContent(NAME_WARNING);
      await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(requests('POST')).toEqual([]);
    });

    it('sends POST /roles only after Confirm and opens the new role', async () => {
      const { user, router } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      await fillCreateForm(user, { name: 'NEW_ROLE', displayName: 'New role' });
      expect(requests('POST')).toEqual([]);
      await user.click(screen.getByRole('button', { name: 'Create' }));
      await waitFor(() => expect(router.state.location.pathname).toBe(`/roles/${createdRole.id}`));
      expect(router.state.location.search).toBe('');
      expect(requests('POST')).toEqual([
        {
          method: 'POST',
          path: '/admin/v1/roles',
          query: {},
          body: { name: 'NEW_ROLE', displayName: 'New role' },
        },
      ]);
    });

    it('sends the description when there is one', async () => {
      const { user, router } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      await fillCreateForm(user, {
        name: 'NEW_ROLE',
        displayName: 'New role',
        description: ' Reads reports. ',
      });
      await user.click(screen.getByRole('button', { name: 'Create' }));
      await waitFor(() => expect(router.state.location.pathname).toBe(`/roles/${createdRole.id}`));
      expect(requests('POST')[0]?.body).toEqual({
        name: 'NEW_ROLE',
        displayName: 'New role',
        description: 'Reads reports.',
      });
    });

    it('opens the Permissions tab with the sync warning state on ROLE_SYNC_FAILED', async () => {
      server.use(
        http.post('*/admin/v1/roles', () =>
          okResponse(
            { ...createdRole, syncStatus: 'FAILED' },
            {
              status: 201,
              warnings: [{ code: 'ROLE_SYNC_FAILED', message: 'The first sync failed.' }],
            },
          ),
        ),
      );
      const { user, router } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      await fillCreateForm(user, { name: 'NEW_ROLE', displayName: 'New role' });
      await user.click(screen.getByRole('button', { name: 'Create' }));
      await waitFor(() => expect(router.state.location.pathname).toBe(`/roles/${createdRole.id}`));
      expect(router.state.location.search).toBe('?tab=permissions');
      expect(router.state.location.state).toEqual({ createdWithSyncWarning: true });
    });

    it('shows DUPLICATE_ROLE_NAME on the name field', async () => {
      server.use(
        http.post('*/admin/v1/roles', () =>
          errorResponse(409, 'DUPLICATE_ROLE_NAME', 'A Keycloak realm role FACILITY_ADMIN exists.'),
        ),
      );
      const { user, router } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN', { selector: 'td span' });
      await fillCreateForm(user, { name: 'FACILITY_ADMIN', displayName: 'Again' });
      await user.click(screen.getByRole('button', { name: 'Create' }));
      const name = screen.getByLabelText('Name');
      await waitFor(() => expect(name).toHaveAttribute('aria-invalid', 'true'));
      expect(name).toHaveAccessibleDescription(
        expect.stringContaining('A Keycloak realm role FACILITY_ADMIN exists.'),
      );
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe('/roles');
    });

    it('shows a validation detail that is not a form field at the top of the form', async () => {
      server.use(
        http.post('*/admin/v1/roles', () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
            details: [
              { field: 'displayName', message: 'Too long for the service.' },
              { field: 'body', message: 'Unknown property.' },
            ],
          }),
        ),
      );
      const { user } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      await fillCreateForm(user, { name: 'NEW_ROLE', displayName: 'New role' });
      await user.click(screen.getByRole('button', { name: 'Create' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Unknown property.');
      expect(screen.getByLabelText('Display name')).toHaveAccessibleDescription(
        'Too long for the service.',
      );
    });

    it('shows a POST /roles 403 inline in the form', async () => {
      server.use(
        http.post('*/admin/v1/roles', () => errorResponse(403, 'FORBIDDEN', 'Not allowed.')),
      );
      const { user } = renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      await fillCreateForm(user, { name: 'NEW_ROLE', displayName: 'New role' });
      await user.click(screen.getByRole('button', { name: 'Create' }));
      const form = screen.getByRole('form', { name: 'Create role' });
      expect(await within(form).findByRole('alert')).toHaveTextContent(
        'You do not have permission to do this.Not allowed.',
      );
    });
  });

  describe('delete', () => {
    function serveWithUnusedRole() {
      server.use(
        http.get('*/admin/v1/roles', async ({ request }) => {
          await recordRequest(request);
          return okResponse([...roles, unusedRole], {
            pagination: { page: 1, pageSize: 20, totalItems: 4, totalPages: 1 },
          });
        }),
      );
    }

    it('hides Delete for system roles and disables it while users hold the role', async () => {
      renderRoute('/roles');
      await screen.findByText('FACILITY_ADMIN');
      for (const name of [superAdminRole.name, 'CCE_ADMIN_SERVICE']) {
        expect(within(rowOf(name)).queryByRole('button', { name: 'Delete' })).toBeNull();
      }
      const facility = rowOf('FACILITY_ADMIN');
      expect(within(facility).getByRole('button', { name: 'Delete' })).toBeDisabled();
      expect(within(facility).getByText('Assigned to 24 users')).toBeInTheDocument();
    });

    it('deletes after Confirm and refetches the list', async () => {
      serveWithUnusedRole();
      server.use(
        http.delete('*/admin/v1/roles/:id', async ({ request }) => {
          await recordRequest(request);
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { user } = renderRoute('/roles');
      await screen.findByText('REPORT_VIEWER');
      await user.click(within(rowOf('REPORT_VIEWER')).getByRole('button', { name: 'Delete' }));
      const dialog = screen.getByRole('dialog', { name: 'Delete REPORT_VIEWER?' });
      expect(dialog).toHaveTextContent(
        'The role, its menu grants and its permission rules are deleted. The name cannot be reused until a later gateway sync removes its realm role.',
      );
      await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(requests('DELETE', `/admin/v1/roles/${unusedRole.id}`)).toHaveLength(1);
      await waitFor(() => expect(requests('GET')).toHaveLength(2));
    });

    it('Cancel sends nothing', async () => {
      serveWithUnusedRole();
      const { user } = renderRoute('/roles');
      await screen.findByText('REPORT_VIEWER');
      await user.click(within(rowOf('REPORT_VIEWER')).getByRole('button', { name: 'Delete' }));
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(recordedRequests().filter((r) => r.method === 'DELETE')).toEqual([]);
    });

    it('shows ROLE_IN_USE inline above the table, and does not refetch', async () => {
      serveWithUnusedRole();
      server.use(
        http.delete('*/admin/v1/roles/:id', () =>
          errorResponse(409, 'ROLE_IN_USE', 'A Keycloak user holds the realm role REPORT_VIEWER.'),
        ),
      );
      const { user } = renderRoute('/roles');
      await screen.findByText('REPORT_VIEWER');
      await user.click(within(rowOf('REPORT_VIEWER')).getByRole('button', { name: 'Delete' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        /^A Keycloak user holds the realm role REPORT_VIEWER\.$/,
      );
      expect(screen.getByText('REPORT_VIEWER')).toBeInTheDocument();
      expect(requests('GET')).toHaveLength(1);
    });

    it('shows a DELETE 403 inline above the table', async () => {
      serveWithUnusedRole();
      server.use(
        http.delete('*/admin/v1/roles/:id', () =>
          errorResponse(403, 'FORBIDDEN', 'Your roles do not allow DELETE /admin/v1/roles/*.'),
        ),
      );
      const { user } = renderRoute('/roles');
      await screen.findByText('REPORT_VIEWER');
      await user.click(within(rowOf('REPORT_VIEWER')).getByRole('button', { name: 'Delete' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'You do not have permission to do this.Your roles do not allow DELETE /admin/v1/roles/*.',
      );
      expect(within(screen.getByRole('banner')).getByText('cce-admin')).toBeInTheDocument();
    });
  });
});
