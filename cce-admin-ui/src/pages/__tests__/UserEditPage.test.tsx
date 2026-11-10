import { screen, waitFor, within } from '@testing-library/react';
import { http, type PathParams } from 'msw';
import type {
  ChangeUserStatusRequest,
  PartialState,
  ReplaceUserRolesRequest,
  UpdateUserRequest,
  User,
} from '../../api/contract';
import {
  adminServiceRole,
  facilityAdminRole,
  roles,
  superAdminRole,
  testAdmin,
  users,
} from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const user01 = users.find((u) => u.username === 'user01')!;
const user02 = users.find((u) => u.username === 'user02')!;
const FACILITY_LABEL = 'Facility administrator (FACILITY_ADMIN)';
const SUPER_LABEL = 'Super administrator (SUPER_ADMIN)';
const AUDIT_LOG_ID = '50000000-0000-4000-8000-000000000003';

const section = (name: string) => screen.getByRole('region', { name });
const profileForm = () => within(section('Profile')).getByRole('form', { name: 'Profile' });
const saveProfile = () => within(section('Profile')).getByRole('button', { name: 'Save profile' });
const saveRoles = () => within(section('Roles')).getByRole('button', { name: 'Save roles' });
const field = (label: string) => within(profileForm()).getByLabelText(label);

const ref = (role: { id: string; name: string; displayName: string }) => ({
  id: role.id,
  name: role.name,
  displayName: role.displayName,
});

function requests(method: string, path: string) {
  return recordedRequests().filter((r) => r.method === method && r.path === path);
}

/** Serves `GET /users/:id` for one user from a value the test can change. */
function serveUser(initial: User) {
  let current = initial;
  server.use(
    http.get(`*/admin/v1/users/${initial.id}`, async ({ request }) => {
      await recordRequest(request);
      return okResponse(current);
    }),
  );
  return (next: Partial<User>) => {
    current = { ...current, ...next };
  };
}

async function openUser(user: User = user01) {
  const rendered = renderRoute(`/users/${user.id}`);
  await screen.findByRole('heading', { name: user.username });
  await within(section('Roles')).findByRole('checkbox', { name: FACILITY_LABEL });
  return rendered;
}

describe('UserEditPage', () => {
  it('shows the header with username, status badge and last login, and four sections', async () => {
    await openUser();
    expect(screen.getByRole('heading', { level: 1, name: 'user01' })).toBeInTheDocument();
    expect(screen.getByText('Active', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getByText('Last login: 2026-11-10 09:15')).toBeInTheDocument();
    for (const name of ['Profile', 'Roles', 'Status', 'Password']) {
      expect(section(name)).toBeInTheDocument();
    }
    expect(field('Email')).toHaveValue('user01@cce.local');
    expect(field('Facility scope')).toHaveValue('');
    expect(within(section('Roles')).getByRole('checkbox', { name: FACILITY_LABEL })).toBeChecked();
    expect(within(section('Roles')).getByRole('checkbox', { name: SUPER_LABEL })).not.toBeChecked();
    expect(
      within(section('Status'))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Deactivate', 'Lock']);
  });

  it('shows "Never" for a user who never signed in', async () => {
    serveUser({ ...user01, lastLoginAt: null });
    await openUser();
    expect(screen.getByText('Last login: Never')).toBeInTheDocument();
  });

  it.each([
    ['an unknown id', `/users/30000000-0000-4000-8000-000000000777`, () => undefined],
    [
      'a malformed id',
      '/users/not-a-uuid',
      () =>
        server.use(
          http.get('*/admin/v1/users/:id', () =>
            errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
              details: [{ field: 'id', message: 'Not a UUID.' }],
            }),
          ),
        ),
    ],
  ])('shows NotFoundPanel for %s', async (_case, path, setUp) => {
    setUp();
    const { user, router } = renderRoute(path);
    expect(await screen.findByText('This user does not exist.')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Back to users' }));
    expect(router.state.location.pathname).toBe('/users');
  });

  it('shows another GET /users/:id error inline with Retry', async () => {
    server.use(
      http.get('*/admin/v1/users/:id', () => errorResponse(403, 'FORBIDDEN', 'Not allowed.')),
    );
    renderRoute(`/users/${user01.id}`);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to do this.Not allowed.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows a GET /roles 500 inline at the role list, with Retry', async () => {
    server.use(http.get('*/admin/v1/roles', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
    const { user } = renderRoute(`/users/${user01.id}`);
    const alert = await within(await screen.findByRole('region', { name: 'Roles' })).findByRole(
      'alert',
    );
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    expect(saveRoles()).toBeDisabled();
    expect(saveProfile()).toBeEnabled();
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(
      await within(section('Roles')).findByRole('checkbox', { name: FACILITY_LABEL }),
    ).toBeInTheDocument();
  });

  describe('Profile', () => {
    it('sends PUT with all four fields, facilityScope null when empty, and resets to the result', async () => {
      server.use(
        http.put<PathParams, UpdateUserRequest>('*/admin/v1/users/:id', async ({ request }) => {
          await recordRequest(request);
          const body = await request.json();
          return okResponse({ ...user02, ...body, email: body.email.toLowerCase() });
        }),
      );
      const { user } = await openUser(user02);
      expect(field('Facility scope')).toHaveValue('FAC-01');
      await user.clear(field('Email'));
      await user.type(field('Email'), ' User02@CCE.example ');
      await user.clear(field('Facility scope'));
      await user.click(saveProfile());
      expect(await within(section('Profile')).findByRole('status')).toHaveTextContent(
        'Profile saved.',
      );
      expect(requests('PUT', `/admin/v1/users/${user02.id}`).map((r) => r.body)).toEqual([
        {
          email: 'User02@CCE.example',
          firstName: 'User',
          lastName: '02',
          facilityScope: null,
        },
      ]);
      // The form now shows the user the service returned.
      expect(field('Email')).toHaveValue('user02@cce.example');
    });

    it('validates before sending', async () => {
      const { user } = await openUser();
      await user.clear(field('First name'));
      await user.click(saveProfile());
      expect(field('First name')).toHaveAccessibleDescription('Enter a first name.');
      expect(requests('PUT', `/admin/v1/users/${user01.id}`)).toEqual([]);
    });

    it('shows EMAIL_IN_USE on the email field', async () => {
      server.use(
        http.put('*/admin/v1/users/:id', () =>
          errorResponse(409, 'EMAIL_IN_USE', 'Another user has this email.'),
        ),
      );
      const { user } = await openUser();
      await user.clear(field('Email'));
      await user.type(field('Email'), 'user02@cce.local');
      await user.click(saveProfile());
      await waitFor(() => expect(field('Email')).toHaveAttribute('aria-invalid', 'true'));
      expect(field('Email')).toHaveAccessibleDescription('Another user has this email.');
    });

    it('keeps the edited values after a 502 USER_PROFILE_UPDATE and the refetch, and saving again sends the same body', async () => {
      const partialState: PartialState = {
        operation: 'USER_PROFILE_UPDATE',
        entityId: user01.id,
        failedStep: 'LOCAL_PROFILE_SAVED',
        completedSteps: ['KEYCLOAK_PROFILE_UPDATED'],
        keycloakUserId: user01.keycloakId,
        auditLogId: AUDIT_LOG_ID,
      };
      serveUser(user01);
      server.use(
        http.put('*/admin/v1/users/:id', async ({ request }) => {
          await recordRequest(request);
          return errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Saving the profile failed.', {
            partialState,
          });
        }),
      );
      const { user } = await openUser();
      await user.clear(field('Last name'));
      await user.type(field('Last name'), 'Renamed');
      await user.click(saveProfile());
      const notice = await within(section('Profile')).findByRole('alert');
      expect(notice).toHaveTextContent('Saving the profile failed.');
      expect(notice).toHaveTextContent(
        'Keycloak may have the new values while this page shows the old ones. Save the profile again with the same values.',
      );
      expect(notice).toHaveTextContent(`Audit log id: ${AUDIT_LOG_ID}`);
      // The refetch rule reloads the user; the form keeps what was sent.
      await waitFor(() => expect(requests('GET', `/admin/v1/users/${user01.id}`)).toHaveLength(2));
      expect(field('Last name')).toHaveValue('Renamed');
      await user.click(saveProfile());
      await waitFor(() => expect(requests('PUT', `/admin/v1/users/${user01.id}`)).toHaveLength(2));
      const [first, second] = requests('PUT', `/admin/v1/users/${user01.id}`);
      expect(second?.body).toEqual(first?.body);
      expect(first?.body).toEqual({
        email: 'user01@cce.local',
        firstName: 'User',
        lastName: 'Renamed',
        facilityScope: null,
      });
    });
  });

  describe('Roles', () => {
    it('enables Save only when the selection changed, and sends the complete set', async () => {
      server.use(
        http.put<PathParams, ReplaceUserRolesRequest>(
          '*/admin/v1/users/:id/roles',
          async ({ request }) => {
            await recordRequest(request);
            const { roleIds } = await request.json();
            return okResponse({
              ...user01,
              roles: roles.filter((r) => roleIds.includes(r.id)).map(ref),
            });
          },
        ),
      );
      const { user } = await openUser();
      expect(saveRoles()).toBeDisabled();
      const superBox = within(section('Roles')).getByRole('checkbox', { name: SUPER_LABEL });
      await user.click(superBox);
      expect(saveRoles()).toBeEnabled();
      await user.click(superBox);
      expect(saveRoles()).toBeDisabled();
      await user.click(superBox);
      await user.click(saveRoles());
      expect(await within(section('Roles')).findByText('Roles saved.')).toBeInTheDocument();
      expect(requests('PUT', `/admin/v1/users/${user01.id}/roles`).map((r) => r.body)).toEqual([
        { roleIds: [facilityAdminRole.id, superAdminRole.id] },
      ]);
      // The saved set is the new baseline.
      expect(saveRoles()).toBeDisabled();
    });

    it('can remove every role', async () => {
      const { user } = await openUser();
      await user.click(within(section('Roles')).getByRole('checkbox', { name: FACILITY_LABEL }));
      await user.click(saveRoles());
      await waitFor(() =>
        expect(requests('PUT', `/admin/v1/users/${user01.id}/roles`).map((r) => r.body)).toEqual([
          { roleIds: [] },
        ]),
      );
    });

    it('lists held roles without a checkbox under "Also held:" and keeps them in the request', async () => {
      const beyond500 = '20000000-0000-4000-8000-000000000555';
      serveUser({
        ...user01,
        roles: [
          ref(adminServiceRole),
          ref(facilityAdminRole),
          { id: beyond500, name: 'FAR_AWAY', displayName: 'Far away' },
        ],
      });
      const { user } = await openUser();
      const rolesSection = section('Roles');
      expect(within(rolesSection).getByText('Also held:')).toBeInTheDocument();
      expect(
        within(rolesSection).getByText('Admin service account (CCE_ADMIN_SERVICE)'),
      ).toBeInTheDocument();
      expect(within(rolesSection).getByText(`Role ${beyond500}`)).toBeInTheDocument();
      expect(
        within(rolesSection).queryByRole('checkbox', { name: /CCE_ADMIN_SERVICE/ }),
      ).toBeNull();

      await user.click(within(rolesSection).getByRole('checkbox', { name: FACILITY_LABEL }));
      await user.click(within(rolesSection).getByRole('checkbox', { name: SUPER_LABEL }));
      await user.click(saveRoles());
      await waitFor(() =>
        expect(requests('PUT', `/admin/v1/users/${user01.id}/roles`)).toHaveLength(1),
      );
      expect(requests('PUT', `/admin/v1/users/${user01.id}/roles`)[0]?.body).toEqual({
        roleIds: [adminServiceRole.id, beyond500, superAdminRole.id],
      });
    });

    it('shows LAST_SYSTEM_ADMIN next to the roles Save button', async () => {
      server.use(
        http.put('*/admin/v1/users/:id/roles', () =>
          errorResponse(
            409,
            'LAST_SYSTEM_ADMIN',
            'cce-admin is the last active user holding SUPER_ADMIN.',
          ),
        ),
      );
      const { user } = await openUser(testAdmin);
      const rolesSection = section('Roles');
      await user.click(within(rolesSection).getByRole('checkbox', { name: SUPER_LABEL }));
      await user.click(saveRoles());
      expect(await within(rolesSection).findByRole('alert')).toHaveTextContent(
        /^cce-admin is the last active user holding SUPER_ADMIN\.$/,
      );
      expect(saveRoles().parentElement).toContainElement(within(rolesSection).getByRole('alert'));
    });

    it('ROLE_NOT_SYNCED names the role under the role list', async () => {
      server.use(
        http.put('*/admin/v1/users/:id/roles', () =>
          errorResponse(409, 'ROLE_NOT_SYNCED', 'A role is not synced.', {
            details: [{ field: 'roleIds[1]', message: 'SUPER_ADMIN has no realm role.' }],
          }),
        ),
      );
      const { user } = await openUser();
      const rolesSection = section('Roles');
      await user.click(within(rolesSection).getByRole('checkbox', { name: SUPER_LABEL }));
      await user.click(saveRoles());
      const group = within(rolesSection).getByRole('group', { name: 'Roles' });
      expect(await within(group).findByRole('alert')).toHaveTextContent(
        'Not synced yet: Super administrator (SUPER_ADMIN). Open the role and use Re-sync.',
      );
    });

    it('shows a validation error of the roles save at the top of the section', async () => {
      server.use(
        http.put('*/admin/v1/users/:id/roles', () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
            details: [{ field: 'roleIds[1]', message: 'No such role.' }],
          }),
        ),
      );
      const { user } = await openUser();
      await user.click(within(section('Roles')).getByRole('checkbox', { name: SUPER_LABEL }));
      await user.click(saveRoles());
      expect(await within(section('Roles')).findByRole('alert')).toHaveTextContent('No such role.');
    });

    it('keeps the selection after a 502 USER_ROLES_UPDATE and the refetch, and saving again sends the same body', async () => {
      serveUser(user01);
      server.use(
        http.put('*/admin/v1/users/:id/roles', async ({ request }) => {
          await recordRequest(request);
          return errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Saving the roles failed.', {
            partialState: {
              operation: 'USER_ROLES_UPDATE',
              entityId: user01.id,
              failedStep: 'LOCAL_ROLES_SAVED',
              completedSteps: ['KEYCLOAK_ROLES_ADDED'],
              keycloakUserId: user01.keycloakId,
              auditLogId: AUDIT_LOG_ID,
            },
          });
        }),
      );
      const { user } = await openUser();
      const rolesSection = section('Roles');
      await user.click(within(rolesSection).getByRole('checkbox', { name: SUPER_LABEL }));
      await user.click(saveRoles());
      const notice = await within(rolesSection).findByRole('alert');
      expect(notice).toHaveTextContent(
        'Some Keycloak role mappings may have changed, but the roles were not saved here. Save the same roles again.',
      );
      await waitFor(() => expect(requests('GET', `/admin/v1/users/${user01.id}`)).toHaveLength(2));
      expect(within(rolesSection).getByRole('checkbox', { name: SUPER_LABEL })).toBeChecked();
      expect(saveRoles()).toBeEnabled();
      await user.click(saveRoles());
      await waitFor(() =>
        expect(requests('PUT', `/admin/v1/users/${user01.id}/roles`)).toHaveLength(2),
      );
      const [first, second] = requests('PUT', `/admin/v1/users/${user01.id}/roles`);
      expect(second?.body).toEqual(first?.body);
      expect(first?.body).toEqual({ roleIds: [facilityAdminRole.id, superAdminRole.id] });
    });
  });

  describe('Status', () => {
    it('changes the status after confirmation and shows the new status', async () => {
      const setUser = serveUser(user01);
      server.use(
        http.patch<PathParams, ChangeUserStatusRequest>(
          '*/admin/v1/users/:id/status',
          async ({ request }) => {
            await recordRequest(request);
            const { status } = await request.json();
            setUser({ status });
            return okResponse({ ...user01, status });
          },
        ),
      );
      const { user } = await openUser();
      const statusSection = section('Status');
      await user.click(within(statusSection).getByRole('button', { name: 'Deactivate' }));
      const dialog = screen.getByRole('dialog', { name: 'Deactivate user01?' });
      await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }));
      expect(await within(statusSection).findByRole('status')).toHaveTextContent(
        'user01 is now Inactive.',
      );
      expect(requests('PATCH', `/admin/v1/users/${user01.id}/status`).map((r) => r.body)).toEqual([
        { status: 'INACTIVE' },
      ]);
      // The refetched user drives the header badge and the buttons.
      await waitFor(() =>
        expect(
          within(statusSection)
            .getAllByRole('button')
            .map((b) => b.textContent),
        ).toEqual(['Activate', 'Lock']),
      );
      expect(screen.getByText('Inactive', { selector: 'span' })).toBeInTheDocument();
    });

    it('Cancel sends nothing', async () => {
      const { user } = await openUser();
      await user.click(within(section('Status')).getByRole('button', { name: 'Lock' }));
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(recordedRequests().filter((r) => r.method === 'PATCH')).toEqual([]);
    });

    it('shows LAST_SYSTEM_ADMIN inline in the Status section', async () => {
      server.use(
        http.patch('*/admin/v1/users/:id/status', () =>
          errorResponse(
            409,
            'LAST_SYSTEM_ADMIN',
            'cce-admin is the last active user holding SUPER_ADMIN.',
          ),
        ),
      );
      const { user } = await openUser(testAdmin);
      const statusSection = section('Status');
      await user.click(within(statusSection).getByRole('button', { name: 'Lock' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Lock' }));
      expect(await within(statusSection).findByRole('alert')).toHaveTextContent(
        'cce-admin is the last active user holding SUPER_ADMIN.',
      );
    });
  });

  describe('Password', () => {
    it('sends the set-password email after confirmation and shows the address', async () => {
      const { user } = await openUser();
      const passwordSection = section('Password');
      await user.click(
        within(passwordSection).getByRole('button', { name: 'Send set-password email' }),
      );
      const dialog = screen.getByRole('dialog', { name: 'Send a set-password email to user01?' });
      expect(dialog).toHaveTextContent(
        'Keycloak sends an email to user01@cce.local with a link to set a new password.',
      );
      await user.click(within(dialog).getByRole('button', { name: 'Send' }));
      expect(await within(passwordSection).findByRole('status')).toHaveTextContent(
        'Set-password email sent to user01@cce.local.',
      );
      expect(requests('POST', `/admin/v1/users/${user01.id}/reset-password`)).toHaveLength(1);
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
      const { user } = await openUser();
      const passwordSection = section('Password');
      await user.click(
        within(passwordSection).getByRole('button', { name: 'Send set-password email' }),
      );
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send' }));
      expect(await within(passwordSection).findByRole('alert')).toHaveTextContent(
        'The service recorded no change. You can try again.',
      );
    });
  });

  it('runs the four actions separately, each with its own pending state', async () => {
    let release: () => void = () => undefined;
    server.use(
      http.put('*/admin/v1/users/:id', async ({ request }) => {
        await recordRequest(request);
        await new Promise<void>((resolve) => (release = resolve));
        return okResponse(user01);
      }),
    );
    const { user } = await openUser();
    await user.click(saveProfile());
    await waitFor(() => expect(saveProfile()).toBeDisabled());
    expect(
      within(section('Password')).getByRole('button', { name: 'Send set-password email' }),
    ).toBeEnabled();
    expect(within(section('Status')).getByRole('button', { name: 'Lock' })).toBeEnabled();
    await user.click(within(section('Roles')).getByRole('checkbox', { name: SUPER_LABEL }));
    expect(saveRoles()).toBeEnabled();
    release();
    expect(await within(section('Profile')).findByText('Profile saved.')).toBeInTheDocument();
    expect(recordedRequests().filter((r) => r.method !== 'GET')).toHaveLength(1);
  });

  it('shows no created message without the navigation state', async () => {
    await openUser();
    expect(screen.queryByText(/^User created/)).not.toBeInTheDocument();
  });
});
