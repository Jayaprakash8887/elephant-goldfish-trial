import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { PartialState } from '../../api/contract';
import { createdUser, facilityAdminRole, superAdminRole } from '../../test/mocks/fixtures';
import { errorResponse, okResponse } from '../../test/mocks/handlers';
import { recordRequest, recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

const FACILITY_LABEL = 'Facility administrator (FACILITY_ADMIN)';
const SUPER_LABEL = 'Super administrator (SUPER_ADMIN)';

const form = () => screen.getByRole('form', { name: 'New user' });
const submitButton = () => within(form()).getByRole('button', { name: 'Create user' });

function posts() {
  return recordedRequests().filter((r) => r.method === 'POST' && r.path === '/admin/v1/users');
}

async function openPage() {
  const rendered = renderRoute('/users/new');
  await screen.findByRole('checkbox', { name: FACILITY_LABEL });
  return rendered;
}

type UserEvents = Awaited<ReturnType<typeof openPage>>['user'];

async function fillValid(user: UserEvents, extra: { facilityScope?: string } = {}) {
  await user.type(screen.getByLabelText('Username'), ' new.user ');
  await user.type(screen.getByLabelText('Email'), 'new.user@cce.local');
  await user.type(screen.getByLabelText('First name'), 'New');
  await user.type(screen.getByLabelText('Last name'), 'User');
  if (extra.facilityScope)
    await user.type(screen.getByLabelText('Facility scope'), extra.facilityScope);
  await user.click(screen.getByRole('checkbox', { name: FACILITY_LABEL }));
}

const EXPECTED_BODY = {
  username: 'new.user',
  email: 'new.user@cce.local',
  firstName: 'New',
  lastName: 'User',
  roleIds: [facilityAdminRole.id],
};

/** The created user's page, which the console opens after a 201. */
function serveCreatedUser() {
  server.use(http.get(`*/admin/v1/users/${createdUser.id}`, () => okResponse(createdUser)));
}

describe('UserCreatePage', () => {
  it('offers only assignable roles, with a sync badge when a role is not synced', async () => {
    await openPage();
    expect(screen.getByRole('heading', { name: 'New user' })).toBeInTheDocument();
    const roles = screen.getByRole('group', { name: 'Roles' });
    expect(
      within(roles)
        .getAllByRole('checkbox')
        .map((c) => c.parentElement?.textContent),
    ).toEqual([FACILITY_LABEL, SUPER_LABEL]);
    expect(within(roles).queryByText(/CCE_ADMIN_SERVICE/)).toBeNull();
    const superItem = screen.getByRole('checkbox', { name: SUPER_LABEL }).closest('li')!;
    expect(within(superItem).getByText('Never synced')).toBeInTheDocument();
    const facilityItem = screen.getByRole('checkbox', { name: FACILITY_LABEL }).closest('li')!;
    expect(within(facilityItem).queryByText(/Synced/)).toBeNull();
  });

  it('shows a GET /roles 500 inline at the role list, with Retry', async () => {
    server.use(http.get('*/admin/v1/roles', () => errorResponse(500, 'INTERNAL_ERROR', 'Boom.')));
    const { user } = renderRoute('/users/new');
    const alert = await within(form()).findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong (HTTP 500, INTERNAL_ERROR).');
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    server.resetHandlers();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('checkbox', { name: FACILITY_LABEL })).toBeInTheDocument();
  });

  it('flags invalid fields before anything is sent', async () => {
    const { user } = await openPage();
    await user.type(screen.getByLabelText('Username'), 'Jane Doe');
    await user.type(screen.getByLabelText('Email'), 'jane');
    await user.click(submitButton());
    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter a valid email address.',
    );
    expect(screen.getByLabelText('First name')).toHaveAccessibleDescription('Enter a first name.');
    expect(screen.getByLabelText('Last name')).toHaveAccessibleDescription('Enter a last name.');
    expect(
      within(screen.getByRole('group', { name: 'Roles' })).getByRole('alert'),
    ).toHaveTextContent('Choose at least one role.');
    expect(posts()).toEqual([]);
  });

  it('sends one POST with the exact body and opens the new user with the created message', async () => {
    serveCreatedUser();
    const { user, router } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    await waitFor(() => expect(router.state.location.pathname).toBe(`/users/${createdUser.id}`));
    expect(
      await screen.findByText('User created. A set-password email was sent to new.user@cce.local.'),
    ).toBeInTheDocument();
    expect(posts()).toEqual([
      { method: 'POST', path: '/admin/v1/users', query: {}, body: EXPECTED_BODY },
    ]);
  });

  it('sends the facility scope when there is one', async () => {
    serveCreatedUser();
    const { user, router } = await openPage();
    await fillValid(user, { facilityScope: ' FAC-01 ' });
    await user.click(submitButton());
    await waitFor(() => expect(router.state.location.pathname).toBe(`/users/${createdUser.id}`));
    expect(posts()[0]?.body).toEqual({ ...EXPECTED_BODY, facilityScope: 'FAC-01' });
  });

  it('disables the submit button while the request is pending', async () => {
    let release: () => void = () => undefined;
    server.use(
      http.post('*/admin/v1/users', async ({ request }) => {
        await recordRequest(request);
        await new Promise<void>((resolve) => (release = resolve));
        return errorResponse(409, 'USERNAME_IN_USE', 'Taken.');
      }),
    );
    const { user } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    await waitFor(() => expect(submitButton()).toBeDisabled());
    release();
    await waitFor(() => expect(submitButton()).toBeEnabled());
    expect(posts()).toHaveLength(1);
  });

  it('shows ACTION_EMAIL_NOT_SENT on the new user', async () => {
    serveCreatedUser();
    server.use(
      http.post('*/admin/v1/users', () =>
        okResponse(createdUser, {
          status: 201,
          warnings: [{ code: 'ACTION_EMAIL_NOT_SENT', message: 'The email was not sent.' }],
        }),
      ),
    );
    const { user } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    expect(
      await screen.findByText(
        'User created, but the set-password email could not be sent. Use Send set-password email on this page.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/A set-password email was sent/)).not.toBeInTheDocument();
  });

  it.each([
    ['USERNAME_IN_USE', 'Username', 'The username new.user is in use.'],
    [
      'EMAIL_IN_USE',
      'Email',
      'new.user@cce.local belongs to a disabled Keycloak account with no local user.',
    ],
  ] as const)('shows %s on its field', async (code, label, message) => {
    server.use(http.post('*/admin/v1/users', () => errorResponse(409, code, message)));
    const { user, router } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    const field = screen.getByLabelText(label);
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'));
    expect(field).toHaveAccessibleDescription(message);
    expect(router.state.location.pathname).toBe('/users/new');
  });

  it.each([
    [
      'ROLE_NOT_SYNCED',
      'Not synced yet: Facility administrator (FACILITY_ADMIN). Open the role and use Re-sync.',
    ],
    ['ROLE_NOT_ASSIGNABLE', 'Cannot be assigned: Facility administrator (FACILITY_ADMIN).'],
  ] as const)('%s names the role under the role list', async (code, text) => {
    server.use(
      http.post('*/admin/v1/users', async ({ request }) => {
        await recordRequest(request);
        return errorResponse(409, code, 'A role cannot be assigned.', {
          details: [{ field: 'roleIds[1]', message: 'FACILITY_ADMIN' }],
        });
      }),
    );
    const { user } = await openPage();
    await user.click(screen.getByRole('checkbox', { name: SUPER_LABEL }));
    await fillValid(user);
    await user.click(submitButton());
    const roles = screen.getByRole('group', { name: 'Roles' });
    expect(await within(roles).findByRole('alert')).toHaveTextContent(text);
    expect(posts()[0]?.body).toMatchObject({
      roleIds: [superAdminRole.id, facilityAdminRole.id],
    });
  });

  it('shows a validation detail that is not a form field at the top of the form', async () => {
    server.use(
      http.post('*/admin/v1/users', () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Invalid request.', {
          details: [
            { field: 'lastName', message: 'Too long for the service.' },
            { field: 'roleIds[0]', message: 'No such role.' },
          ],
        }),
      ),
    );
    const { user } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    expect(await screen.findByText('No such role.')).toBeInTheDocument();
    expect(screen.getByLabelText('Last name')).toHaveAccessibleDescription(
      'Too long for the service.',
    );
  });

  describe('502 KEYCLOAK_SYNC_FAILED (USER_CREATE)', () => {
    const steps: PartialState = {
      operation: 'USER_CREATE',
      entityId: '30000000-0000-4000-8000-000000000099',
      failedStep: 'LOCAL_USER_SAVED',
      completedSteps: ['KEYCLOAK_USER_CREATED', 'KEYCLOAK_ROLES_ASSIGNED'],
      keycloakUserId: '40000000-0000-4000-8000-000000000099',
      auditLogId: '50000000-0000-4000-8000-000000000009',
    };

    it('with completed steps: the USER_CREATE text and the operator ids, and the form stays usable', async () => {
      server.use(
        http.post('*/admin/v1/users', async ({ request }) => {
          await recordRequest(request);
          return errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Saving the user failed.', {
            partialState: steps,
          });
        }),
      );
      const { user, router } = await openPage();
      await fillValid(user);
      await user.click(submitButton());
      const notice = await screen.findByRole('alert');
      expect(notice).toHaveTextContent('Saving the user failed.');
      expect(notice).toHaveTextContent(
        'The sign-in account may exist in Keycloak without a user here. Submit the form again with the same values: the service links the account and finishes. If it fails again, pass the ids below to an operator.',
      );
      expect(notice).toHaveTextContent(`Keycloak user id: ${steps.keycloakUserId}`);
      expect(notice).toHaveTextContent(`Audit log id: ${steps.auditLogId}`);
      expect(within(notice).getByRole('link', { name: 'Back to users' })).toHaveAttribute(
        'href',
        '/users',
      );
      expect(within(notice).queryByRole('link', { name: /new.user|99/ })).toBeNull();

      // The form keeps its values and its submit button; sending again sends the same body.
      expect(screen.getByLabelText('Username')).toHaveValue(' new.user ');
      expect(screen.getByRole('checkbox', { name: FACILITY_LABEL })).toBeChecked();
      await waitFor(() => expect(submitButton()).toBeEnabled());
      await user.click(submitButton());
      await waitFor(() => expect(posts()).toHaveLength(2));
      expect(posts()[1]?.body).toEqual(posts()[0]?.body);
      expect(router.state.location.pathname).toBe('/users/new');
    });

    it('without completed steps: "The service recorded no change."', async () => {
      server.use(
        http.post('*/admin/v1/users', () =>
          errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Keycloak did not answer.', {
            partialState: {
              ...steps,
              failedStep: 'KEYCLOAK_USER_CREATED',
              completedSteps: [],
              keycloakUserId: null,
              auditLogId: null,
            },
          }),
        ),
      );
      const { user } = await openPage();
      await fillValid(user);
      await user.click(submitButton());
      const notice = await screen.findByRole('alert');
      expect(notice).toHaveTextContent('The service recorded no change. You can try again.');
      expect(notice).not.toHaveTextContent('For the operator:');
      expect(submitButton()).toBeEnabled();
    });
  });

  it('shows "the result is unknown" after a network error', async () => {
    server.use(http.post('*/admin/v1/users', () => HttpResponse.error()));
    const { user } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    const alert = await within(form()).findByRole('alert');
    expect(alert).toHaveTextContent('Cannot reach the admin service.');
    expect(alert).toHaveTextContent('The result is unknown; the page was refreshed.');
  });

  it('shows a POST /users 403 inline in the form', async () => {
    server.use(
      http.post('*/admin/v1/users', () => errorResponse(403, 'FORBIDDEN', 'Not allowed.')),
    );
    const { user } = await openPage();
    await fillValid(user);
    await user.click(submitButton());
    expect(await within(form()).findByRole('alert')).toHaveTextContent(
      'You do not have permission to do this.Not allowed.',
    );
  });
});
