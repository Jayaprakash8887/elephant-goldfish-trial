import { act, within } from '@testing-library/react';
import { bootstrap } from '../bootstrap';
import { initAuth } from '../auth/keycloak';

function newRoot(): HTMLElement {
  const root = document.createElement('div');
  document.body.appendChild(root);
  return root;
}

afterEach(() => {
  vi.unstubAllEnvs();
  document.body.innerHTML = '';
});

describe('bootstrap', () => {
  it('renders nothing while sign-in is pending', async () => {
    vi.mocked(initAuth).mockReturnValue(new Promise<void>(() => undefined));
    const root = newRoot();
    await act(async () => {
      void bootstrap(root);
      await Promise.resolve();
    });
    expect(initAuth).toHaveBeenCalledWith({
      keycloakUrl: 'http://keycloak.test/auth',
      keycloakRealm: 'cce',
      keycloakClientId: 'cce-admin-ui',
      apiBaseUrl: 'http://localhost:3002',
    });
    expect(root).toBeEmptyDOMElement();
  });

  it('shows the sign-in start-up texts when sign-in is rejected', async () => {
    vi.mocked(initAuth).mockRejectedValue(new Error('no session'));
    const root = newRoot();
    await act(() => bootstrap(root));
    const screen = within(root);
    expect(screen.getByRole('heading', { name: 'Sign-in failed' })).toBeInTheDocument();
    expect(
      screen.getByText(
        'Sign-in did not complete with the sign-in service at http://keycloak.test/auth.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('names VITE_KEYCLOAK_REALM and never calls initAuth when the realm is missing', async () => {
    vi.stubEnv('VITE_KEYCLOAK_REALM', '');
    const root = newRoot();
    await act(() => bootstrap(root));
    const screen = within(root);
    expect(screen.getByRole('heading', { name: 'The console cannot start' })).toBeInTheDocument();
    expect(
      screen.getByText('Configuration error: VITE_KEYCLOAK_REALM is not set.'),
    ).toBeInTheDocument();
    expect(initAuth).not.toHaveBeenCalled();
  });

  it('renders the shell after sign-in', async () => {
    const root = newRoot();
    await act(() => bootstrap(root));
    const screen = within(root);
    // Scoped to the header: since step 5 the Users list also shows the username cce-admin.
    expect(await within(screen.getByRole('banner')).findByText('cce-admin')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
  });
});
