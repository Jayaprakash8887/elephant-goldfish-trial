import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { facilityAdminRole, superAdminRole } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const facilityPath = `/roles/${facilityAdminRole.id}`;

function displayForm() {
  return screen.getByRole('form', { name: 'Role details' });
}

function puts() {
  return recordedRequests().filter((r) => r.method === 'PUT');
}

describe('RoleDetailPage', () => {
  it('shows the header and the tabs, Menus by default', async () => {
    renderRoute(facilityPath);
    expect(await screen.findByRole('heading', { name: 'FACILITY_ADMIN' })).toBeInTheDocument();
    expect(screen.getByText('Synced · 2026-11-10 09:20')).toBeInTheDocument();
    expect(screen.getByText('Users: 24')).toBeInTheDocument();
    expect(within(displayForm()).getByLabelText('Display name')).toHaveValue(
      'Facility administrator',
    );
    expect(within(displayForm()).getByLabelText('Description')).toHaveValue(
      'Manages users of one facility.',
    );
    expect(screen.queryByRole('img', { name: 'System role' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Menus' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Permissions' })).toHaveAttribute(
      'aria-selected',
      'false',
    );
    expect(await screen.findByRole('checkbox', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('switches to the Permissions tab through the URL', async () => {
    const { user, router } = renderRoute(facilityPath);
    await screen.findByRole('checkbox', { name: 'Dashboard' });
    await user.click(screen.getByRole('tab', { name: 'Permissions' }));
    expect(router.state.location.search).toBe('?tab=permissions');
    expect(
      await within(screen.getByRole('tabpanel', { name: 'Permissions' })).findByText(
        "Synced means the gateway's sync call succeeded.",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Menus' }));
    expect(router.state.location.search).toBe('?tab=menus');
    expect(await screen.findByRole('checkbox', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('opens ?tab=permissions directly', async () => {
    renderRoute(`${facilityPath}?tab=permissions`);
    expect(await screen.findByRole('textbox', { name: 'URI pattern, row 1' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Permissions' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('saves the display form with PUT /roles/:id and shows "Saved."', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id', async ({ request }) => {
        await recordRequest(request);
        return okResponse({
          ...facilityAdminRole,
          displayName: 'Facility admin',
          description: null,
        });
      }),
    );
    const { user } = renderRoute(facilityPath);
    await screen.findByRole('heading', { name: 'FACILITY_ADMIN' });
    const form = displayForm();
    await user.clear(within(form).getByLabelText('Display name'));
    await user.type(within(form).getByLabelText('Display name'), ' Facility admin ');
    await user.clear(within(form).getByLabelText('Description'));
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    expect(await within(form).findByText('Saved.')).toBeInTheDocument();
    expect(puts()).toEqual([
      {
        method: 'PUT',
        path: `/admin/v1/roles/${facilityAdminRole.id}`,
        query: {},
        body: { displayName: 'Facility admin', description: null },
      },
    ]);
    // The form resets to the role the save returned.
    expect(within(form).getByLabelText('Display name')).toHaveValue('Facility admin');
    expect(within(form).getByLabelText('Description')).toHaveValue('');
  });

  it('shows SYSTEM_ROLE_IMMUTABLE inline next to Save', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id', () =>
        errorResponse(409, 'SYSTEM_ROLE_IMMUTABLE', 'FACILITY_ADMIN is a system role.'),
      ),
    );
    const { user } = renderRoute(facilityPath);
    await screen.findByRole('heading', { name: 'FACILITY_ADMIN' });
    const form = displayForm();
    await user.type(within(form).getByLabelText('Display name'), '!');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      /^FACILITY_ADMIN is a system role\.$/,
    );
    expect(within(form).queryByText('Saved.')).not.toBeInTheDocument();
    expect(within(form).getByLabelText('Display name')).toHaveValue('Facility administrator!');
  });

  it('maps a VALIDATION_ERROR detail to its field', async () => {
    server.use(
      http.put('*/admin/v1/roles/:id', () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
          details: [{ field: 'description', message: 'Too long for the service.' }],
        }),
      ),
    );
    const { user } = renderRoute(facilityPath);
    await screen.findByRole('heading', { name: 'FACILITY_ADMIN' });
    const form = displayForm();
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    const description = within(form).getByLabelText('Description');
    await waitFor(() => expect(description).toHaveAttribute('aria-invalid', 'true'));
    expect(description).toHaveAccessibleDescription('Too long for the service.');
  });

  it('rejects an empty display name before sending', async () => {
    const { user } = renderRoute(facilityPath);
    await screen.findByRole('heading', { name: 'FACILITY_ADMIN' });
    const form = displayForm();
    await user.clear(within(form).getByLabelText('Display name'));
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(within(form).getByLabelText('Display name')).toHaveAttribute('aria-invalid', 'true'),
    );
    expect(puts()).toEqual([]);
  });

  it('disables the form for a system role, with the note', async () => {
    renderRoute(`/roles/${superAdminRole.id}`);
    expect(
      await screen.findByRole('heading', { name: 'SUPER_ADMIN System role' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'System role' })).toBeInTheDocument();
    expect(screen.getByText('Never synced')).toBeInTheDocument();
    const form = displayForm();
    expect(within(form).getByText('System roles cannot be changed.')).toBeInTheDocument();
    expect(within(form).getByLabelText('Display name')).toBeDisabled();
    expect(within(form).getByLabelText('Description')).toBeDisabled();
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('shows NotFoundPanel on ROLE_NOT_FOUND', async () => {
    const { user, router } = renderRoute('/roles/20000000-0000-4000-8000-0000000000ff');
    expect(await screen.findByText('This role does not exist.')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Back to roles' }));
    expect(router.state.location.pathname).toBe('/roles');
  });

  it('shows NotFoundPanel for a malformed id', async () => {
    server.use(
      http.get('*/admin/v1/roles/:id', () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
          details: [{ field: 'id', message: 'Must be a UUID.' }],
        }),
      ),
    );
    renderRoute('/roles/not-a-uuid');
    expect(await screen.findByText('This role does not exist.')).toBeInTheDocument();
  });

  it('shows a GET /roles/:id 500 inline with Retry', async () => {
    server.use(
      http.get('*/admin/v1/roles/:id', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')),
    );
    const { user } = renderRoute(facilityPath);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'FACILITY_ADMIN' })).toBeInTheDocument();
  });
});
