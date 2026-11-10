import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse, type PathParams } from 'msw';
import type {
  ErrorEnvelope,
  PartialState,
  PermissionRow,
  ReplaceRolePermissionsRequest,
  RolePermissions,
  RoleSyncResult,
  SuccessEnvelope,
} from '../../api/contract';
import {
  facilityAdminRole,
  LAST_SYNC_AT,
  rolePermissions,
  superAdminRole,
} from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const facility = rolePermissions[facilityAdminRole.id]!;
const facilityPath = `/roles/${facilityAdminRole.id}?tab=permissions`;
const implicitRows = facility.permissions.filter((row) => row.implicit);

const SAVED_AT = '2026-11-10T10:00:00Z';
const AUDIT_LOG_ID = '50000000-0000-4000-8000-000000000001';
const CREATED_TEXT = 'The role was created but not synced. Use Re-sync before assigning it.';
const SAVE_FAILED_TEXT =
  'The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds.';
const ROLE_SYNC_TEXT = 'The sync failed. The saved permissions are unchanged. Use Re-sync.';

const tab = () => screen.getByRole('tabpanel', { name: 'Permissions' });
const syncPanel = () => within(tab()).getByRole('region', { name: 'Gateway sync' });
const rulesForm = () => within(tab()).getByRole('form', { name: 'Permission rules' });
const saveButton = () => within(rulesForm()).getByRole('button', { name: 'Save' });
const uri = (row: number) => screen.getByRole('textbox', { name: `URI pattern, row ${row}` });
const description = (row: number) =>
  screen.getByRole('textbox', { name: `Description, row ${row}` });

async function openTab(path = facilityPath) {
  const rendered = renderRoute(path);
  await screen.findByRole('textbox', { name: 'URI pattern, row 1' });
  return rendered;
}

/** A response body that is either envelope. */
type Body<T> = SuccessEnvelope<T> | ErrorEnvelope;

/** Serves `GET /roles/:id/permissions` from a value the test can change. */
function servePermissions(initial: RolePermissions) {
  let current = initial;
  server.use(
    http.get('*/admin/v1/roles/:id/permissions', async ({ request }) => {
      await recordRequest(request);
      return okResponse(current);
    }),
  );
  return (next: Partial<RolePermissions>) => {
    current = { ...current, ...next };
  };
}

/** The rows the service would hold after a save: implicit first, then by pattern and method. */
function savedRows(body: ReplaceRolePermissionsRequest): PermissionRow[] {
  const explicit = [...body.permissions]
    .sort(
      (a, b) =>
        a.uriPattern.localeCompare(b.uriPattern) || a.httpMethod.localeCompare(b.httpMethod),
    )
    .map((row, i) => ({
      id: 100 + i,
      httpMethod: row.httpMethod,
      uriPattern: row.uriPattern,
      description: row.description ?? null,
      resourceCategory: row.resourceCategory ?? null,
      implicit: false,
    }));
  return [...implicitRows, ...explicit];
}

function partialState(operation: PartialState['operation'], completedSteps: string[]) {
  return {
    operation,
    entityId: facilityAdminRole.id,
    failedStep: 'GATEWAY_SYNC',
    completedSteps,
    keycloakUserId: null,
    auditLogId: AUDIT_LOG_ID,
  } satisfies PartialState;
}

const requests = (method: string, suffix: string) =>
  recordedRequests().filter((r) => r.method === method && r.path.endsWith(suffix));

describe('RolePermissionsTab', () => {
  it('shows a GET /roles/:id/permissions 403 inline', async () => {
    server.use(
      http.get('*/admin/v1/roles/:id/permissions', () =>
        errorResponse(
          403,
          'FORBIDDEN',
          'Your roles do not allow GET /admin/v1/roles/*/permissions.',
        ),
      ),
    );
    renderRoute(facilityPath);
    const alert = await within(await screen.findByRole('tabpanel')).findByRole('alert');
    expect(alert).toHaveTextContent(
      'You do not have permission to do this.Your roles do not allow GET /admin/v1/roles/*/permissions.',
    );
    expect(screen.getByRole('heading', { name: 'FACILITY_ADMIN' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
  });

  it('shows the created-with-sync-warning notice from navigation state only', async () => {
    const { router } = renderRoute('/roles');
    await act(() => router.navigate(facilityPath, { state: { createdWithSyncWarning: true } }));
    expect(
      await within(await screen.findByRole('tabpanel', { name: 'Permissions' })).findByText(
        CREATED_TEXT,
      ),
    ).toBeInTheDocument();
    await act(() => router.navigate(`/roles/${superAdminRole.id}?tab=permissions`));
    await screen.findByRole('heading', { name: 'SUPER_ADMIN System role' });
    expect(screen.queryByText(CREATED_TEXT)).not.toBeInTheDocument();
  });

  it('shows the sync panel with the server status and the help text', async () => {
    servePermissions({ ...facility, syncStatus: 'FAILED', lastSyncAt: LAST_SYNC_AT });
    await openTab();
    expect(within(syncPanel()).getByText('Sync failed · 2026-11-10 09:20')).toBeInTheDocument();
    expect(
      within(syncPanel()).getByText("Synced means the gateway's sync call succeeded."),
    ).toBeInTheDocument();
    expect(within(syncPanel()).getByRole('button', { name: 'Re-sync' })).toBeEnabled();
  });

  it('shows the implicit rows read-only and never sends them', async () => {
    const { user } = await openTab();
    const implicitTable = within(tab()).getByRole('table', { name: 'Always included' });
    expect(
      within(tab()).getByText(
        'Every non-system role keeps these two rules so that its users can load their profile and menus.',
      ),
    ).toBeInTheDocument();
    const rows = within(implicitTable).getAllByRole('row').slice(1);
    expect(rows.map((row) => row.textContent)).toEqual([
      'GET/admin/v1/meRead own profileSelf',
      'GET/admin/v1/me/menusRead own menu treeSelf',
    ]);
    expect(within(implicitTable).queryByRole('textbox')).not.toBeInTheDocument();

    // The editable rows are the two explicit rows only.
    const rulesTable = within(tab()).getByRole('table', { name: 'Rules' });
    expect(within(rulesTable).getAllByRole('textbox', { name: /^URI pattern/ })).toHaveLength(2);
    expect(uri(1)).toHaveValue('/admin/v1/users/**');
    expect(description(1)).toHaveValue('Read users');
    expect(screen.getByRole('textbox', { name: 'Category, row 1' })).toHaveValue('Users');
    expect(uri(2)).toHaveValue('/v1/insights/**');
    expect(description(2)).toHaveValue('');
    expect(screen.getByRole('combobox', { name: 'Method, row 2' })).toHaveValue('GET');

    await user.type(description(2), 'Read insights');
    await user.click(saveButton());
    await within(tab()).findByText('Saved.');
    expect(requests('PUT', '/permissions')).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/roles/${facilityAdminRole.id}/permissions`,
        query: {},
        body: {
          permissions: [
            {
              httpMethod: 'GET',
              uriPattern: '/admin/v1/users/**',
              description: 'Read users',
              resourceCategory: 'Users',
            },
            { httpMethod: 'GET', uriPattern: '/v1/insights/**', description: 'Read insights' },
          ],
        },
      },
    ]);
  });

  it('adds rows starting with GET and empty fields, and removes rows', async () => {
    const { user } = await openTab();
    await user.click(within(tab()).getByRole('button', { name: 'Add rule' }));
    expect(screen.getByRole('combobox', { name: 'Method, row 3' })).toHaveValue('GET');
    expect(uri(3)).toHaveValue('');
    expect(description(3)).toHaveValue('');
    expect(screen.getByRole('textbox', { name: 'Category, row 3' })).toHaveValue('');
    expect(
      within(screen.getByRole('combobox', { name: 'Method, row 3' }))
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']);

    await user.selectOptions(screen.getByRole('combobox', { name: 'Method, row 3' }), 'POST');
    await user.type(uri(3), ' /admin/v1/users ');
    await user.type(description(3), 'Create users');
    await user.click(screen.getByRole('button', { name: 'Remove row 1' }));
    expect(uri(1)).toHaveValue('/v1/insights/**');
    expect(uri(2)).toHaveValue(' /admin/v1/users ');

    await user.click(saveButton());
    await within(tab()).findByText('Saved.');
    expect(requests('PUT', '/permissions')[0]?.body).toEqual({
      permissions: [
        { httpMethod: 'GET', uriPattern: '/v1/insights/**' },
        { httpMethod: 'POST', uriPattern: '/admin/v1/users', description: 'Create users' },
      ],
    });
  });

  it('sends an empty set when every explicit row is removed', async () => {
    const { user } = await openTab();
    await user.click(screen.getByRole('button', { name: 'Remove row 2' }));
    await user.click(screen.getByRole('button', { name: 'Remove row 1' }));
    await user.click(saveButton());
    await within(tab()).findByText('Saved.');
    expect(requests('PUT', '/permissions')[0]?.body).toEqual({ permissions: [] });
  });

  it('rejects duplicate rows, rows equal to an implicit row and invalid patterns', async () => {
    const { user } = await openTab();
    const add = within(tab()).getByRole('button', { name: 'Add rule' });
    await user.click(add);
    await user.type(uri(3), '/admin/v1/users/**');
    await user.click(add);
    await user.type(uri(4), '/admin/v1/me');
    await user.click(add);
    await user.type(uri(5), '/admin/**/users');
    await user.click(saveButton());

    await waitFor(() => expect(uri(3)).toHaveAttribute('aria-invalid', 'true'));
    expect(uri(3)).toHaveAccessibleDescription('Another rule has the same method and URI pattern.');
    expect(uri(4)).toHaveAccessibleDescription('Already included for every role');
    expect(uri(5)).toHaveAccessibleDescription('** may only be the whole last segment.');
    expect(uri(1)).not.toHaveAttribute('aria-invalid');
    expect(requests('PUT', '/permissions')).toEqual([]);
  });

  it('keeps Save disabled until the rules differ from the fetched set', async () => {
    const { user } = await openTab();
    expect(saveButton()).toBeDisabled();
    // Whitespace and an empty description are not a change.
    await user.type(uri(2), '  ');
    expect(saveButton()).toBeDisabled();
    await user.type(description(2), 'x');
    expect(saveButton()).toBeEnabled();
    await user.clear(description(2));
    expect(saveButton()).toBeDisabled();
    await user.click(within(tab()).getByRole('button', { name: 'Add rule' }));
    expect(saveButton()).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Remove row 3' }));
    expect(saveButton()).toBeDisabled();
  });

  it('on a 200 shows "Saved." and the refetched "Synced" status', async () => {
    const setPermissions = servePermissions({
      ...facility,
      syncStatus: 'FAILED',
      lastSyncAt: LAST_SYNC_AT,
    });
    server.use(
      http.put<{ id: string }, ReplaceRolePermissionsRequest>(
        '*/admin/v1/roles/:id/permissions',
        async ({ request }) => {
          await recordRequest(request);
          const body = await request.json();
          setPermissions({
            syncStatus: 'SYNCED',
            lastSyncAt: SAVED_AT,
            permissions: savedRows(body),
          });
          return okResponse({
            ...facility,
            syncStatus: 'SYNCED',
            lastSyncAt: SAVED_AT,
            permissions: savedRows(body),
          });
        },
      ),
    );
    const { user } = await openTab();
    expect(within(syncPanel()).getByText('Sync failed · 2026-11-10 09:20')).toBeInTheDocument();
    await user.click(within(tab()).getByRole('button', { name: 'Add rule' }));
    await user.type(uri(3), '/admin/v1/audit');
    await user.click(saveButton());

    expect(await within(tab()).findByText('Saved.')).toBeInTheDocument();
    expect(within(syncPanel()).getByText('Synced · 2026-11-10 10:00')).toBeInTheDocument();
    // The editor was remounted with the saved rows, in the service's order.
    expect(uri(1)).toHaveValue('/admin/v1/audit');
    expect(uri(2)).toHaveValue('/admin/v1/users/**');
    expect(uri(3)).toHaveValue('/v1/insights/**');
    expect(saveButton()).toBeDisabled();
    // An edit clears "Saved.".
    await user.type(description(1), 'Audit');
    expect(within(tab()).queryByText('Saved.')).not.toBeInTheDocument();
  });

  it('on a 502 ROLE_PERMISSIONS_SAVE refetches the rows, remounts the editor and shows "Sync failed"', async () => {
    const setPermissions = servePermissions(facility);
    server.use(
      http.put<{ id: string }, ReplaceRolePermissionsRequest>(
        '*/admin/v1/roles/:id/permissions',
        async ({ request }) => {
          await recordRequest(request);
          const body = await request.json();
          setPermissions({
            syncStatus: 'FAILED',
            lastSyncAt: SAVED_AT,
            permissions: savedRows(body),
          });
          return errorResponse(
            502,
            'KEYCLOAK_SYNC_FAILED',
            'The permissions were saved, but the gateway sync failed.',
            { partialState: partialState('ROLE_PERMISSIONS_SAVE', ['PERMISSIONS_SAVED']) },
          );
        },
      ),
    );
    const { user } = await openTab();
    await user.click(within(tab()).getByRole('button', { name: 'Add rule' }));
    await user.type(uri(3), '/admin/v1/audit');
    await user.click(saveButton());

    const notice = await within(tab()).findByRole('alert');
    expect(notice).toHaveTextContent('The permissions were saved, but the gateway sync failed.');
    expect(notice).toHaveTextContent(SAVE_FAILED_TEXT);
    expect(notice).toHaveTextContent(`Audit log id: ${AUDIT_LOG_ID}`);
    expect(within(notice).getByRole('button', { name: 'Re-sync' })).toBeEnabled();
    await waitFor(() => expect(uri(1)).toHaveValue('/admin/v1/audit'));
    expect(within(syncPanel()).getByText('Sync failed · 2026-11-10 10:00')).toBeInTheDocument();
    expect(uri(3)).toHaveValue('/v1/insights/**');
    expect(saveButton()).toBeDisabled();
    expect(within(tab()).queryByText('Saved.')).not.toBeInTheDocument();
    expect(requests('GET', '/permissions').length).toBeGreaterThanOrEqual(2);
  });

  it('Re-sync 200 shows "Synced"; Re-sync 502 shows the ROLE_SYNC text', async () => {
    const setPermissions = servePermissions({
      ...facility,
      syncStatus: 'FAILED',
      lastSyncAt: LAST_SYNC_AT,
    });
    let syncOk = false;
    server.use(
      http.post<PathParams, never, Body<RoleSyncResult>>(
        '*/admin/v1/roles/:id/sync',
        async ({ request }) => {
          await recordRequest(request);
          if (!syncOk) {
            setPermissions({ lastSyncAt: SAVED_AT });
            return errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'The gateway sync failed.', {
              partialState: partialState('ROLE_SYNC', []),
            });
          }
          setPermissions({ syncStatus: 'SYNCED', lastSyncAt: '2026-11-10T10:05:00Z' });
          return okResponse<RoleSyncResult>({
            roleId: facilityAdminRole.id,
            syncStatus: 'SYNCED',
            lastSyncAt: '2026-11-10T10:05:00Z',
          });
        },
      ),
    );
    const { user } = await openTab();

    await user.click(within(syncPanel()).getByRole('button', { name: 'Re-sync' }));
    const notice = await within(tab()).findByRole('alert');
    expect(notice).toHaveTextContent('The gateway sync failed.');
    expect(notice).toHaveTextContent(ROLE_SYNC_TEXT);
    expect(
      await within(syncPanel()).findByText('Sync failed · 2026-11-10 10:00'),
    ).toBeInTheDocument();

    // A failed Re-sync from the notice shows the notice again.
    await user.click(within(notice).getByRole('button', { name: 'Re-sync' }));
    expect(await within(tab()).findByText(ROLE_SYNC_TEXT)).toBeInTheDocument();

    syncOk = true;
    await user.click(within(syncPanel()).getByRole('button', { name: 'Re-sync' }));
    expect(await within(syncPanel()).findByText('Synced · 2026-11-10 10:05')).toBeInTheDocument();
    expect(within(tab()).queryByRole('alert')).not.toBeInTheDocument();
    const syncs = requests('POST', '/sync');
    expect(syncs).toHaveLength(3);
    expect(syncs[0]).toEqual({
      method: 'POST',
      path: `/admin/v1/roles/${facilityAdminRole.id}/sync`,
      query: {},
      body: undefined,
    });
  });

  it('keeps unsaved edits through a Re-sync', async () => {
    const setPermissions = servePermissions({
      ...facility,
      syncStatus: 'FAILED',
      lastSyncAt: LAST_SYNC_AT,
    });
    server.use(
      http.post('*/admin/v1/roles/:id/sync', async ({ request }) => {
        await recordRequest(request);
        setPermissions({ syncStatus: 'SYNCED', lastSyncAt: SAVED_AT });
        return okResponse<RoleSyncResult>({
          roleId: facilityAdminRole.id,
          syncStatus: 'SYNCED',
          lastSyncAt: SAVED_AT,
        });
      }),
    );
    const { user } = await openTab();
    await user.type(description(2), 'Read insights');
    const resync = within(syncPanel()).getByRole('button', { name: 'Re-sync' });
    expect(resync).toBeEnabled();
    await user.click(resync);
    expect(await within(syncPanel()).findByText('Synced · 2026-11-10 10:00')).toBeInTheDocument();
    expect(description(2)).toHaveValue('Read insights');
    expect(saveButton()).toBeEnabled();
    expect(requests('PUT', '/permissions')).toEqual([]);
  });

  it('shows a Re-sync 403 inline', async () => {
    server.use(
      http.post('*/admin/v1/roles/:id/sync', () =>
        errorResponse(403, 'FORBIDDEN', 'Your roles do not allow POST /admin/v1/roles/*/sync.'),
      ),
    );
    const { user } = await openTab();
    await user.click(within(syncPanel()).getByRole('button', { name: 'Re-sync' }));
    expect(await within(tab()).findByRole('alert')).toHaveTextContent(
      'You do not have permission to do this.Your roles do not allow POST /admin/v1/roles/*/sync.',
    );
  });

  it('maps a VALIDATION_ERROR on save to its row, and other details to the top', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id/permissions', () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
          details: [
            { field: 'permissions[1].uriPattern', message: 'Not a pattern the gateway accepts.' },
            { field: 'body', message: 'The body could not be read.' },
          ],
        }),
      ),
    );
    const { user } = await openTab();
    await user.type(description(2), 'x');
    await user.click(saveButton());
    await waitFor(() => expect(uri(2)).toHaveAttribute('aria-invalid', 'true'));
    expect(uri(2)).toHaveAccessibleDescription('Not a pattern the gateway accepts.');
    expect(within(rulesForm()).getByRole('alert')).toHaveTextContent('The body could not be read.');
    expect(description(2)).toHaveValue('x');
  });

  it('shows SYSTEM_ROLE_IMMUTABLE on save inline and keeps the edits', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id/permissions', () =>
        errorResponse(409, 'SYSTEM_ROLE_IMMUTABLE', 'FACILITY_ADMIN is a system role.'),
      ),
    );
    const { user } = await openTab();
    await user.type(description(2), 'x');
    await user.click(saveButton());
    expect(await within(rulesForm()).findByRole('alert')).toHaveTextContent(
      /^FACILITY_ADMIN is a system role\.$/,
    );
    expect(description(2)).toHaveValue('x');
    expect(saveButton()).toBeEnabled();
  });

  it('keeps the edits when the refetch after a save fails', async () => {
    let failReads = false;
    server.use(
      http.get<PathParams, never, Body<RolePermissions>>('*/admin/v1/roles/:id/permissions', () =>
        failReads ? errorResponse(500, 'INTERNAL_ERROR', 'Boom.') : okResponse(facility),
      ),
      http.put('*/admin/v1/roles/:id/permissions', () => {
        failReads = true;
        return okResponse(facility);
      }),
    );
    const { user } = await openTab();
    await user.type(description(2), 'Read insights');
    await user.click(saveButton());
    expect(await within(tab()).findByText('Saved.')).toBeInTheDocument();
    expect(within(tab()).getByRole('alert')).toHaveTextContent(
      'Something went wrong (HTTP 500, INTERNAL_ERROR).',
    );
    expect(description(2)).toHaveValue('Read insights');
    failReads = false;
    await user.click(within(tab()).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(within(tab()).queryByRole('alert')).not.toBeInTheDocument());
  });

  it('is read-only for a system role, with Re-sync enabled', async () => {
    const { user } = await openTab(`/roles/${superAdminRole.id}?tab=permissions`);
    expect(within(tab()).queryByRole('table', { name: 'Always included' })).not.toBeInTheDocument();
    const textboxes = within(rulesForm()).getAllByRole('textbox');
    expect(textboxes).toHaveLength(15);
    for (const box of textboxes) expect(box).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Method, row 1' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove row 1' })).toBeDisabled();
    expect(within(tab()).getByRole('button', { name: 'Add rule' })).toBeDisabled();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByText('System roles cannot be changed.')).toBeInTheDocument();

    const resync = within(syncPanel()).getByRole('button', { name: 'Re-sync' });
    expect(resync).toBeEnabled();
    await user.click(resync);
    await waitFor(() => expect(requests('POST', '/sync')).toHaveLength(1));
    expect(requests('POST', '/sync')[0]?.path).toBe(`/admin/v1/roles/${superAdminRole.id}/sync`);
  });

  it('guards unsaved changes when switching tab', async () => {
    const { user, router } = await openTab();
    await user.type(description(2), 'x');
    await user.click(screen.getByRole('tab', { name: 'Menus' }));

    const dialog = screen.getByRole('dialog', { name: 'Unsaved changes' });
    expect(dialog).toHaveTextContent('You have unsaved changes. Leave without saving?');
    await user.click(within(dialog).getByRole('button', { name: 'Stay' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=permissions');
    expect(description(2)).toHaveValue('x');

    await user.click(screen.getByRole('tab', { name: 'Menus' }));
    await user.click(
      within(screen.getByRole('dialog', { name: 'Unsaved changes' })).getByRole('button', {
        name: 'Leave',
      }),
    );
    await waitFor(() => expect(router.state.location.search).toBe('?tab=menus'));
    expect(await screen.findByRole('checkbox', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('shows a network error on save as a result that is unknown', async () => {
    server.use(http.put('*/admin/v1/roles/:id/permissions', () => HttpResponse.error()));
    const { user } = await openTab();
    await user.type(description(2), 'x');
    await user.click(saveButton());
    const alert = await within(rulesForm()).findByRole('alert');
    expect(alert).toHaveTextContent('Cannot reach the admin service.');
    expect(alert).toHaveTextContent('The result is unknown; the page was refreshed.');
    expect(description(2)).toHaveValue('x');
  });
});
