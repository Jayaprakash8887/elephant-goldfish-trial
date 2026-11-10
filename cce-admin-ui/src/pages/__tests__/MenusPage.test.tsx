import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import type { Menu } from '../../api/contract';
import { menuIds, menuTree } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const DEACTIVATE_BODY =
  'Roles keep the grant, but no console shows this menu while it is inactive.';
const ACTIVATE_BODY = 'Roles that were granted this menu show it again.';

/** A copy of the seed tree with `change` applied to the menu with `id`. */
function treeWith(id: string, change: Partial<Menu>): Menu[] {
  const apply = (menu: Menu): Menu => ({
    ...(menu.id === id ? { ...menu, ...change } : menu),
    children: menu.children.map(apply),
  });
  return menuTree.map(apply);
}

/** `GET /menus` answers with each tree in turn, then keeps the last one. */
function serveTrees(...trees: Menu[][]) {
  let call = 0;
  server.use(
    http.get('*/admin/v1/menus', async ({ request }) => {
      await recordRequest(request);
      const tree = trees[Math.min(call, trees.length - 1)] ?? menuTree;
      call += 1;
      return okResponse(tree);
    }),
  );
}

async function openPage() {
  const rendered = renderRoute('/menus');
  await screen.findByText('Dashboard', { selector: 'td' });
  return rendered;
}

function rowOf(label: string): HTMLElement {
  const row = labelCell(label).closest('tr');
  if (!row) throw new Error(`No row for ${label}`);
  return row;
}

/** The label cell of a row (cheaper than a role query over every cell). */
function labelCell(label: string): HTMLElement {
  return screen.getByText(label, { selector: 'td' });
}

function form() {
  return screen.getByRole('form', { name: 'Menu entry' });
}

function writes() {
  return recordedRequests().filter((r) => r.method === 'POST' || r.method === 'PUT');
}

function menuReads() {
  return recordedRequests().filter((r) => r.method === 'GET' && r.path === '/admin/v1/menus');
}

describe('MenusPage', () => {
  it('shows a GET /menus 500 inline with Retry', async () => {
    server.use(http.get('*/admin/v1/menus', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
    const { user } = renderRoute('/menus');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    expect(screen.getByRole('button', { name: 'Add menu' })).toBeDisabled();
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Dashboard', { selector: 'td' })).toBeInTheDocument();
  });

  it('shows the tree with DETAIL rows indented under their NAV, and the icons', async () => {
    await openPage();
    // Role queries over the whole table are slow in jsdom; the label is each row's first cell.
    const labels = Array.from(document.querySelectorAll('tbody tr')).map(
      (row) => row.querySelector('td')?.textContent,
    );
    expect(labels).toEqual([
      'Dashboard',
      'Facilities',
      'Compliance',
      'Protocol analytics',
      'Deviations',
      'Patients',
      'Patient detail',
      'Events',
      'Ingestion',
      'Practitioners',
      'Intelligence',
      'Exports',
    ]);
    expect(labelCell('Protocol analytics')).toHaveClass('pl-10');
    expect(labelCell('Compliance')).not.toHaveClass('pl-10');

    const dashboard = rowOf('Dashboard');
    expect(within(dashboard).getByText('dashboard')).toBeInTheDocument();
    expect(within(dashboard).getByText('/')).toBeInTheDocument();
    expect(within(dashboard).getByText('ChartBarIcon')).toBeInTheDocument();
    expect(dashboard.querySelector('svg[data-icon="ChartBarIcon"]')).not.toBeNull();
    expect(within(dashboard).getByText('NAV')).toBeInTheDocument();
    expect(within(dashboard).getByText('10')).toBeInTheDocument();
    expect(within(dashboard).getByText('Active')).toBeInTheDocument();
    expect(within(dashboard).getByRole('button', { name: 'Add detail' })).toBeInTheDocument();
    expect(within(dashboard).getByRole('button', { name: 'Deactivate' })).toBeInTheDocument();

    const detail = rowOf('Patient detail');
    expect(within(detail).getByText('patients.patient-detail')).toBeInTheDocument();
    expect(within(detail).getByText('DETAIL')).toBeInTheDocument();
    expect(detail.querySelector('svg')).toBeNull();
    expect(within(detail).queryByRole('button', { name: 'Add detail' })).not.toBeInTheDocument();
  });

  it('shows the fallback icon and the raw name for an unknown icon, and inactive entries', async () => {
    serveTrees(treeWith(menuIds.dashboard, { icon: 'MysteryIcon', isActive: false }));
    await openPage();
    const dashboard = rowOf('Dashboard');
    expect(dashboard.querySelector('svg[data-icon="QuestionMarkCircleIcon"]')).not.toBeNull();
    expect(within(dashboard).getByText('MysteryIcon')).toBeInTheDocument();
    expect(within(dashboard).getByText('Inactive')).toBeInTheDocument();
    expect(within(dashboard).getByRole('button', { name: 'Activate' })).toBeInTheDocument();
  });

  it('adds a NAV entry with the default sort order', async () => {
    const { user } = await openPage();
    await user.click(screen.getByRole('button', { name: 'Add menu' }));
    const panel = form();
    expect(within(panel).getByRole('heading', { name: 'Add menu' })).toBeInTheDocument();
    // The highest NAV sort order is 100 (Exports).
    expect(within(panel).getByLabelText('Sort order')).toHaveValue(110);
    expect(within(panel).getByLabelText('Type')).toHaveValue('NAV');
    expect(within(panel).getByLabelText('Icon')).toHaveValue('');
    expect(within(panel).getByLabelText('Active')).toBeChecked();
    expect(within(panel).queryByLabelText('Parent')).not.toBeInTheDocument();

    await user.type(within(panel).getByLabelText('Key'), ' reports ');
    await user.type(within(panel).getByLabelText('Label'), 'Reports');
    await user.type(within(panel).getByLabelText('Path'), '/reports');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Menu entry' })).not.toBeInTheDocument();
    expect(writes()).toEqual([
      {
        method: 'POST',
        path: '/admin/v1/menus',
        query: {},
        body: {
          key: 'reports',
          label: 'Reports',
          path: '/reports',
          type: 'NAV',
          sortOrder: 110,
          isActive: true,
        },
      },
    ]);
    // The refetch rule reloads the tree after the save.
    await waitFor(() => expect(menuReads()).toHaveLength(2));
  });

  it('adds a DETAIL entry under a NAV entry', async () => {
    serveTrees(treeWith(menuIds.facilities, { isActive: false }));
    const { user } = await openPage();
    await user.click(within(rowOf('Patients')).getByRole('button', { name: 'Add detail' }));
    const panel = form();
    expect(
      within(panel).getByRole('heading', { name: 'Add detail under Patients' }),
    ).toBeInTheDocument();
    expect(within(panel).getByLabelText('Type')).toHaveValue('DETAIL');
    const parent = within(panel).getByLabelText('Parent');
    expect(parent).toHaveValue(menuIds.patients);
    // Every NAV entry is offered; inactive ones are marked.
    expect(
      within(parent)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual([
      'Choose a parent',
      'Dashboard',
      'Facilities (inactive)',
      'Compliance',
      'Deviations',
      'Patients',
      'Events',
      'Ingestion',
      'Practitioners',
      'Intelligence',
      'Exports',
    ]);
    // Patients has one child with sort order 10.
    expect(within(panel).getByLabelText('Sort order')).toHaveValue(20);

    await user.type(within(panel).getByLabelText('Key'), 'patients.history');
    await user.type(within(panel).getByLabelText('Label'), 'Patient history');
    await user.type(within(panel).getByLabelText('Path'), '/compliance/patients/:id/history');
    await user.selectOptions(within(panel).getByLabelText('Icon'), 'UserIcon');
    await user.click(within(panel).getByLabelText('Active'));
    await user.click(within(panel).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(writes()).toEqual([
      {
        method: 'POST',
        path: '/admin/v1/menus',
        query: {},
        body: {
          key: 'patients.history',
          label: 'Patient history',
          path: '/compliance/patients/:id/history',
          type: 'DETAIL',
          sortOrder: 20,
          isActive: false,
          icon: 'UserIcon',
          parentId: menuIds.patients,
        },
      },
    ]);
  });

  it('checks the parent rule before sending, and switching back to NAV clears the parent', async () => {
    const { user } = await openPage();
    await user.click(screen.getByRole('button', { name: 'Add menu' }));
    const panel = form();
    await user.type(within(panel).getByLabelText('Key'), 'reports');
    await user.type(within(panel).getByLabelText('Label'), 'Reports');
    await user.type(within(panel).getByLabelText('Path'), 'reports');
    await user.selectOptions(within(panel).getByLabelText('Type'), 'DETAIL');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));
    expect(
      await within(panel).findByText('Choose the NAV menu this entry belongs to.'),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Start with /.')).toBeInTheDocument();

    await user.selectOptions(within(panel).getByLabelText('Parent'), menuIds.compliance);
    await user.selectOptions(within(panel).getByLabelText('Type'), 'NAV');
    expect(within(panel).queryByLabelText('Parent')).not.toBeInTheDocument();
    await user.clear(within(panel).getByLabelText('Path'));
    await user.type(within(panel).getByLabelText('Path'), '/reports');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(writes()[0]?.body).not.toHaveProperty('parentId');
  });

  it('shows DUPLICATE_MENU_KEY on the key field and keeps the form', async () => {
    server.use(
      http.post('*/admin/v1/menus', () =>
        errorResponse(409, 'DUPLICATE_MENU_KEY', 'A menu with the key dashboard exists.'),
      ),
    );
    const { user } = await openPage();
    await user.click(screen.getByRole('button', { name: 'Add menu' }));
    const panel = form();
    await user.type(within(panel).getByLabelText('Key'), 'dashboard');
    await user.type(within(panel).getByLabelText('Label'), 'Dashboard 2');
    await user.type(within(panel).getByLabelText('Path'), '/dashboard-2');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));
    expect(
      await within(panel).findByText('A menu with the key dashboard exists.'),
    ).toBeInTheDocument();
    expect(within(panel).getByLabelText('Key')).toHaveAttribute('aria-invalid', 'true');
    expect(within(panel).getByLabelText('Label')).toHaveValue('Dashboard 2');
    expect(screen.queryByText('Menu saved.')).not.toBeInTheDocument();
  });

  it('shows a POST 403 inline in the form', async () => {
    server.use(
      http.post('*/admin/v1/menus', () => errorResponse(403, 'FORBIDDEN', 'Not allowed here.')),
    );
    const { user } = await openPage();
    await user.click(screen.getByRole('button', { name: 'Add menu' }));
    const panel = form();
    await user.type(within(panel).getByLabelText('Key'), 'reports');
    await user.type(within(panel).getByLabelText('Label'), 'Reports');
    await user.type(within(panel).getByLabelText('Path'), '/reports');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));
    const alert = await within(panel).findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to do this.');
    expect(alert).toHaveTextContent('Not allowed here.');
    // The rest of the page keeps working.
    expect(labelCell('Dashboard')).toBeInTheDocument();
  });

  it('edits a NAV entry with PUT, without key and type in the body', async () => {
    const { user } = await openPage();
    await user.click(within(rowOf('Dashboard')).getByRole('button', { name: 'Edit' }));
    const panel = form();
    expect(within(panel).getByRole('heading', { name: 'Edit Dashboard' })).toBeInTheDocument();
    expect(within(panel).getByLabelText('Key')).toHaveValue('dashboard');
    expect(within(panel).getByLabelText('Key')).toHaveAttribute('readonly');
    expect(within(panel).getByLabelText('Type')).toHaveValue('NAV');
    expect(within(panel).getByLabelText('Type')).toHaveAttribute('readonly');
    expect(within(panel).getByLabelText('Icon')).toHaveValue('ChartBarIcon');

    await user.clear(within(panel).getByLabelText('Label'));
    await user.type(within(panel).getByLabelText('Label'), 'Home');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(writes()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/menus/${menuIds.dashboard}`,
        query: {},
        body: {
          label: 'Home',
          path: '/',
          icon: 'ChartBarIcon',
          parentId: null,
          sortOrder: 10,
          isActive: true,
        },
      },
    ]);
  });

  it("edits a DETAIL entry's parent, and keeps an unknown icon selectable", async () => {
    serveTrees(treeWith(menuIds.patientDetail, { icon: 'MysteryIcon' }));
    const { user } = await openPage();
    await user.click(within(rowOf('Patient detail')).getByRole('button', { name: 'Edit' }));
    const panel = form();
    expect(within(panel).getByLabelText('Icon')).toHaveValue('MysteryIcon');
    expect(within(panel).getByLabelText('Parent')).toHaveValue(menuIds.patients);
    await user.selectOptions(within(panel).getByLabelText('Parent'), menuIds.compliance);
    await user.selectOptions(within(panel).getByLabelText('Icon'), 'No icon');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(writes()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/menus/${menuIds.patientDetail}`,
        query: {},
        body: {
          label: 'Patient detail',
          path: '/compliance/patients/:id',
          icon: null,
          parentId: menuIds.compliance,
          sortOrder: 10,
          isActive: true,
        },
      },
    ]);
  });

  it('maps a VALIDATION_ERROR on PUT to its field and shows the rest at the top', async () => {
    server.use(
      http.put('*/admin/v1/menus/:id', () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
          details: [
            { field: 'path', message: 'The service rejects this path.' },
            { field: 'body', message: 'Something else is wrong.' },
          ],
        }),
      ),
    );
    const { user } = await openPage();
    await user.click(within(rowOf('Facilities')).getByRole('button', { name: 'Edit' }));
    const panel = form();
    await user.type(within(panel).getByLabelText('Path'), '-x');
    await user.click(within(panel).getByRole('button', { name: 'Save' }));
    expect(await within(panel).findByText('The service rejects this path.')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Path')).toHaveAttribute('aria-invalid', 'true');
    expect(within(panel).getByRole('alert')).toHaveTextContent('Something else is wrong.');
    expect(within(panel).getByLabelText('Path')).toHaveValue('/facilities-x');
  });

  it('closes the form on Cancel without sending anything', async () => {
    const { user } = await openPage();
    await user.click(within(rowOf('Events')).getByRole('button', { name: 'Edit' }));
    await user.click(within(form()).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('form', { name: 'Menu entry' })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('deactivates: reads the tree again and sends its values with isActive false', async () => {
    // The second read shows a change made elsewhere since the page loaded.
    serveTrees(menuTree, treeWith(menuIds.dashboard, { label: 'Overview', sortOrder: 15 }));
    const { user } = await openPage();
    await user.click(within(rowOf('Dashboard')).getByRole('button', { name: 'Deactivate' }));
    const dialog = screen.getByRole('dialog', { name: 'Deactivate Dashboard?' });
    expect(dialog).toHaveTextContent(DEACTIVATE_BODY);
    expect(writes()).toEqual([]);
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const calls = recordedRequests().map((r) => `${r.method} ${r.path}`);
    const put = calls.indexOf(`PUT /admin/v1/menus/${menuIds.dashboard}`);
    expect(calls.slice(0, put).filter((c) => c === 'GET /admin/v1/menus')).toHaveLength(2);
    expect(writes()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/menus/${menuIds.dashboard}`,
        query: {},
        body: {
          label: 'Overview',
          path: '/',
          icon: 'ChartBarIcon',
          parentId: null,
          sortOrder: 15,
          isActive: false,
        },
      },
    ]);
  });

  it('activates an inactive entry the same way', async () => {
    serveTrees(treeWith(menuIds.patientDetail, { isActive: false }));
    const { user } = await openPage();
    await user.click(within(rowOf('Patient detail')).getByRole('button', { name: 'Activate' }));
    const dialog = screen.getByRole('dialog', { name: 'Activate Patient detail?' });
    expect(dialog).toHaveTextContent(ACTIVATE_BODY);
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));

    expect(await screen.findByText('Menu saved.')).toBeInTheDocument();
    expect(writes()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/menus/${menuIds.patientDetail}`,
        query: {},
        body: {
          label: 'Patient detail',
          path: '/compliance/patients/:id',
          icon: null,
          parentId: menuIds.patients,
          sortOrder: 10,
          isActive: true,
        },
      },
    ]);
  });

  it('sends nothing when the confirmation is cancelled', async () => {
    const { user } = await openPage();
    await user.click(within(rowOf('Exports')).getByRole('button', { name: 'Deactivate' }));
    await user.click(
      within(screen.getByRole('dialog', { name: 'Deactivate Exports?' })).getByRole('button', {
        name: 'Cancel',
      }),
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
    expect(menuReads()).toHaveLength(1);
  });

  it('shows a 403 on the deactivate PUT in the message area', async () => {
    server.use(
      http.put('*/admin/v1/menus/:id', () =>
        errorResponse(403, 'FORBIDDEN', 'No permission row for PUT /admin/v1/menus/*.'),
      ),
    );
    const { user } = await openPage();
    await user.click(within(rowOf('Events')).getByRole('button', { name: 'Deactivate' }));
    await user.click(
      within(screen.getByRole('dialog', { name: 'Deactivate Events?' })).getByRole('button', {
        name: 'Deactivate',
      }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to do this.');
    expect(alert).toHaveTextContent('No permission row for PUT /admin/v1/menus/*.');
    expect(screen.queryByText('Menu saved.')).not.toBeInTheDocument();
  });

  it('sends no PUT when the entry is gone from the tree read again', async () => {
    serveTrees(
      menuTree,
      menuTree.filter((menu) => menu.id !== menuIds.dashboard),
    );
    const { user } = await openPage();
    await user.click(within(rowOf('Dashboard')).getByRole('button', { name: 'Deactivate' }));
    await user.click(
      within(screen.getByRole('dialog', { name: 'Deactivate Dashboard?' })).getByRole('button', {
        name: 'Deactivate',
      }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('This menu no longer exists.');
    expect(writes()).toEqual([]);
  });
});
