import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import type { PartialState, PartialStateOperation } from '../../../api/contract';
import { SyncFailedNotice } from '../SyncFailedNotice';

const NO_CHANGE = 'The service recorded no change. You can try again.';
const ROLE_SYNC = 'The sync failed. The saved permissions are unchanged. Use Re-sync.';

function state(
  operation: PartialStateOperation,
  overrides: Partial<PartialState> = {},
): PartialState {
  return {
    operation,
    entityId: 'e1',
    failedStep: 'STEP_2',
    completedSteps: ['STEP_1'],
    keycloakUserId: null,
    auditLogId: null,
    ...overrides,
  };
}

describe('SyncFailedNotice', () => {
  it.each([
    [
      'USER_CREATE',
      'The sign-in account may exist in Keycloak without a user here. Submit the form again with the same values: the service links the account and finishes. If it fails again, pass the ids below to an operator.',
    ],
    [
      'USER_PROFILE_UPDATE',
      'Keycloak may have the new values while this page shows the old ones. Save the profile again with the same values. If it fails again, pass the ids below to an operator.',
    ],
    [
      'USER_STATUS_CHANGE',
      'Keycloak may already have the new status, but it was not saved here. Choose the same status again. If it fails again, pass the ids below to an operator.',
    ],
    [
      'USER_ROLES_UPDATE',
      'Some Keycloak role mappings may have changed, but the roles were not saved here. Save the same roles again. If it fails again, pass the ids below to an operator.',
    ],
    [
      'ROLE_PERMISSIONS_SAVE',
      'The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds.',
    ],
    ['ROLE_SYNC', ROLE_SYNC],
  ] as const)('shows the %s text when steps completed', (operation, text) => {
    render(<SyncFailedNotice partialState={state(operation)} message="Keycloak failed." />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Keycloak failed.');
    expect(alert).toHaveTextContent(text);
    expect(alert).not.toHaveTextContent(NO_CHANGE);
  });

  it.each([
    'USER_CREATE',
    'USER_PROFILE_UPDATE',
    'USER_STATUS_CHANGE',
    'USER_ROLES_UPDATE',
    'USER_PASSWORD_RESET',
    'ROLE_PERMISSIONS_SAVE',
  ] as const)('applies the empty-steps rule to %s', (operation) => {
    render(
      <SyncFailedNotice
        partialState={state(operation, { completedSteps: [] })}
        message="Timed out."
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(NO_CHANGE);
    expect(screen.getByText('Timed out.')).toBeInTheDocument();
  });

  it('always shows the ROLE_SYNC text, also with empty steps', () => {
    render(
      <SyncFailedNotice
        partialState={state('ROLE_SYNC', { completedSteps: [], auditLogId: 'a1' })}
        message="Gateway failed."
      />,
    );
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(ROLE_SYNC);
    expect(alert).not.toHaveTextContent(NO_CHANGE);
  });

  it('shows only the server message for USER_PASSWORD_RESET with steps', () => {
    render(<SyncFailedNotice partialState={state('USER_PASSWORD_RESET')} message="Failed." />);
    expect(screen.getByRole('alert')).toHaveTextContent(/^Failed\.$/);
  });

  it('shows the Keycloak user id and the audit log id for the operator', () => {
    render(
      <SyncFailedNotice
        partialState={state('USER_CREATE', { keycloakUserId: 'kc-123', auditLogId: 'audit-456' })}
        message="Keycloak failed."
      />,
    );
    expect(screen.getByText('For the operator:')).toBeInTheDocument();
    expect(screen.getByText('Keycloak user id: kc-123')).toBeInTheDocument();
    expect(screen.getByText('Audit log id: audit-456')).toBeInTheDocument();
  });

  it('leaves out the audit log id when it is null', () => {
    render(
      <SyncFailedNotice
        partialState={state('USER_CREATE', { keycloakUserId: 'kc-123', auditLogId: null })}
        message="Keycloak failed."
      />,
    );
    expect(screen.getByText('Keycloak user id: kc-123')).toBeInTheDocument();
    expect(screen.queryByText(/Audit log id/)).not.toBeInTheDocument();
  });

  it('leaves out the operator section when both ids are null', () => {
    render(<SyncFailedNotice partialState={state('USER_ROLES_UPDATE')} message="Failed." />);
    expect(screen.queryByText('For the operator:')).not.toBeInTheDocument();
  });

  it('does not link to the user for USER_CREATE, only to the users list', () => {
    const router = createMemoryRouter(
      [
        {
          path: '/users/new',
          element: (
            <SyncFailedNotice
              partialState={state('USER_CREATE', { entityId: 'new-user-id' })}
              message="Keycloak failed."
              usersLink
            />
          ),
        },
      ],
      { initialEntries: ['/users/new'] },
    );
    render(<RouterProvider router={router} />);
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/users');
    expect(links[0]).toHaveTextContent('Back to users');
    expect(screen.queryByText(/new-user-id/)).not.toBeInTheDocument();
  });

  it('offers Re-sync, disabled while pending', async () => {
    const onResync = vi.fn();
    const { rerender } = render(
      <SyncFailedNotice
        partialState={state('ROLE_PERMISSIONS_SAVE')}
        message="Gateway failed."
        onResync={onResync}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Re-sync' }));
    expect(onResync).toHaveBeenCalledTimes(1);
    rerender(
      <SyncFailedNotice
        partialState={state('ROLE_PERMISSIONS_SAVE')}
        message="Gateway failed."
        onResync={onResync}
        resyncPending
      />,
    );
    expect(screen.getByRole('button', { name: 'Re-sync' })).toBeDisabled();
  });
});
