import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReplaceRoleMenusRequest, Warning } from '../../api/contract';
import { facilityAdminRole, menuIds, menuTree, superAdminRole } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const facilityPath = `/roles/${facilityAdminRole.id}`;
const PATIENT_WARNING = 'Patient detail is shown only when Patients is also granted.';

function panel() {
  return screen.getByRole('tabpanel', { name: 'Menus' });
}

function saveButton() {
  return within(panel()).getByRole('button', { name: 'Save' });
}

async function openTab(path = facilityPath) {
  const rendered = renderRoute(path);
  await within(await screen.findByRole('tabpanel', { name: 'Menus' })).findByRole('checkbox', {
    name: 'Dashboard',
  });
  return rendered;
}

/** `PUT /roles/:id/menus` answers with the set it received, and the given warnings. */
function echoSave(warnings: Warning[] = []) {
  server.use(
    http.put<{ id: string }, ReplaceRoleMenusRequest>(
      '*/admin/v1/roles/:id/menus',
      async ({ request, params }) => {
        await recordRequest(request);
        const body = await request.json();
        return okResponse({ roleId: params.id, menuIds: body.menuIds }, { warnings });
      },
    ),
  );
}

function menuPuts() {
  return recordedRequests().filter((r) => r.method === 'PUT' && r.path.endsWith('/menus'));
}

describe('RoleMenusTab', () => {
  it('shows a GET /menus 500 inline', async () => {
    server.use(http.get('*/admin/v1/menus', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
    const { user } = renderRoute(facilityPath);
    const alert = await within(await screen.findByRole('tabpanel')).findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('checkbox', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('shows a GET /roles/:id/menus 403 inline', async () => {
    server.use(
      http.get('*/admin/v1/roles/:id/menus', () =>
        errorResponse(403, 'FORBIDDEN', 'Your roles do not allow this.'),
      ),
    );
    renderRoute(facilityPath);
    const alert = await within(await screen.findByRole('tabpanel')).findByRole('alert');
    expect(alert).toHaveTextContent(
      'You do not have permission to do this.Your roles do not allow this.',
    );
    expect(screen.getByRole('heading', { name: 'FACILITY_ADMIN' })).toBeInTheDocument();
  });

  it('shows the tree with path and type, and the current selection', async () => {
    const inactiveTree = menuTree.map((menu) =>
      menu.id === menuIds.dashboard ? { ...menu, isActive: false } : menu,
    );
    server.use(http.get('*/admin/v1/menus', () => okResponse(inactiveTree)));
    await openTab();
    const checkboxes = within(panel()).getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(12);
    expect(checkboxes.slice(0, 4).map((box) => box.closest('label')?.textContent)).toEqual([
      'Dashboard',
      'Facilities',
      'Compliance',
      'Protocol analytics',
    ]);
    expect(screen.getByRole('checkbox', { name: 'Facilities' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Compliance' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Dashboard' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Patient detail' })).not.toBeChecked();

    const patientRow = screen.getByRole('checkbox', { name: 'Patient detail' }).closest('div')!;
    expect(within(patientRow).getByText('/compliance/patients/:id')).toBeInTheDocument();
    expect(within(patientRow).getByText('DETAIL')).toBeInTheDocument();
    const dashboardRow = screen.getByRole('checkbox', { name: 'Dashboard' }).closest('div')!;
    expect(within(dashboardRow).getByText('NAV')).toBeInTheDocument();
    expect(within(dashboardRow).getByText('Inactive')).toBeInTheDocument();
    expect(within(patientRow).queryByText('Inactive')).not.toBeInTheDocument();
  });

  it('keeps Save disabled until the selection differs from the fetched set', async () => {
    const { user } = await openTab();
    expect(saveButton()).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    expect(saveButton()).toBeEnabled();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    expect(saveButton()).toBeDisabled();
  });

  it('grants each menu on its own', async () => {
    const { user } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Patients' }));
    expect(screen.getByRole('checkbox', { name: 'Patient detail' })).not.toBeChecked();
    await user.click(screen.getByRole('checkbox', { name: 'Compliance' }));
    expect(screen.getByRole('checkbox', { name: 'Protocol analytics' })).not.toBeChecked();
  });

  it('sends the complete set and shows "Menus saved."', async () => {
    echoSave();
    const { user } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    await user.click(screen.getByRole('checkbox', { name: 'Facilities' }));
    await user.click(saveButton());
    expect(await within(panel()).findByText('Menus saved.')).toBeInTheDocument();
    expect(menuPuts()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/roles/${facilityAdminRole.id}/menus`,
        query: {},
        body: { menuIds: [menuIds.compliance, menuIds.dashboard] },
      },
    ]);
    // The saved set is now the fetched set.
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Dashboard' })).toBeChecked();
    await user.click(screen.getByRole('checkbox', { name: 'Events' }));
    expect(within(panel()).queryByText('Menus saved.')).not.toBeInTheDocument();
  });

  it('warns while editing about a DETAIL granted without its parent', async () => {
    const { user } = await openTab();
    expect(within(panel()).queryByText(PATIENT_WARNING)).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Patient detail' }));
    expect(within(panel()).getByText(PATIENT_WARNING)).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Patients' }));
    expect(within(panel()).queryByText(PATIENT_WARNING)).not.toBeInTheDocument();
  });

  it('shows the server warnings after save, with labels from the tree', async () => {
    echoSave([
      {
        code: 'DETAIL_WITHOUT_PARENT',
        message: 'Patient detail without Patients.',
        menuId: menuIds.patientDetail,
        parentMenuId: menuIds.patients,
      },
      {
        code: 'DETAIL_WITHOUT_PARENT',
        message: 'Menu 10000000-0000-4000-8000-0000000000aa is granted without its parent.',
        menuId: '10000000-0000-4000-8000-0000000000aa',
        parentMenuId: menuIds.compliance,
      },
    ]);
    const { user } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Patient detail' }));
    await user.click(saveButton());
    await within(panel()).findByText('Menus saved.');
    const list = within(panel()).getByText(PATIENT_WARNING).closest('ul')!;
    const warnings = within(list).getAllByRole('listitem');
    expect(warnings.map((item) => item.textContent)).toEqual([
      PATIENT_WARNING,
      'Menu 10000000-0000-4000-8000-0000000000aa is granted without its parent.',
    ]);
  });

  it('shows a 403 on save inline and keeps the selection', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id/menus', () =>
        errorResponse(403, 'FORBIDDEN', 'Your roles do not allow PUT /admin/v1/roles/*/menus.'),
      ),
    );
    const { user } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    await user.click(saveButton());
    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'You do not have permission to do this.Your roles do not allow PUT /admin/v1/roles/*/menus.',
    );
    expect(screen.getByRole('checkbox', { name: 'Dashboard' })).toBeChecked();
    expect(saveButton()).toBeEnabled();
  });

  it('keeps the selection after a save whose result is unknown', async () => {
    server.use(
      http.put(
        '*/admin/v1/roles/:id/menus',
        () => new HttpResponse('Bad gateway', { status: 504 }),
      ),
    );
    const { user } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    await user.click(saveButton());
    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'The result is unknown; the page was refreshed.',
    );
    expect(screen.getByRole('checkbox', { name: 'Dashboard' })).toBeChecked();
  });

  it('is read-only for a system role', async () => {
    renderRoute(`/roles/${superAdminRole.id}`);
    const dashboard = await screen.findByRole('checkbox', { name: 'Dashboard' });
    expect(
      within(panel())
        .getAllByRole('checkbox')
        .every((box) => box.hasAttribute('disabled')),
    ).toBe(true);
    expect(dashboard).toBeDisabled();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByText('System roles cannot be changed.')).toBeInTheDocument();
  });

  it('guards unsaved changes when switching tab', async () => {
    const { user, router } = await openTab();
    await user.click(screen.getByRole('checkbox', { name: 'Dashboard' }));
    await user.click(screen.getByRole('tab', { name: 'Permissions' }));

    const dialog = screen.getByRole('dialog', { name: 'Unsaved changes' });
    expect(dialog).toHaveTextContent('You have unsaved changes. Leave without saving?');
    await user.click(within(dialog).getByRole('button', { name: 'Stay' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.search).toBe('');
    expect(screen.getByRole('checkbox', { name: 'Dashboard' })).toBeChecked();

    await user.click(screen.getByRole('tab', { name: 'Permissions' }));
    await user.click(
      within(screen.getByRole('dialog', { name: 'Unsaved changes' })).getByRole('button', {
        name: 'Leave',
      }),
    );
    await waitFor(() => expect(router.state.location.search).toBe('?tab=permissions'));
    expect(screen.getByText('Permissions are not available yet.')).toBeInTheDocument();
  });

  it('does not guard when nothing changed', async () => {
    const { user, router } = await openTab();
    await user.click(screen.getByRole('link', { name: 'Roles' }));
    expect(router.state.location.pathname).toBe('/roles');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
