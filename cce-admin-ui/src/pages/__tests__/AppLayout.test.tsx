import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { logout } from '../../auth/keycloak';
import { errorResponse } from '../../test/mocks/handlers';
import { recordedRequests } from '../../test/mocks/requests';
import { server } from '../../test/mocks/server';
import { renderRoute } from '../../test/render';

function expectSidebarLinks() {
  const nav = screen.getByRole('navigation', { name: 'Main' });
  const links = within(nav).getAllByRole('link');
  expect(links.map((link) => link.textContent)).toEqual(['Users', 'Roles', 'Menus']);
  expect(links.map((link) => link.getAttribute('href'))).toEqual(['/users', '/roles', '/menus']);
}

describe('AppLayout', () => {
  it('shows the username and role names from GET /me in the header', async () => {
    server.use(
      http.get('*/admin/v1/me', () =>
        HttpResponse.json({
          data: {
            id: 'u1',
            username: 'jane.doe',
            roles: ['FACILITY_ADMIN', 'SUPER_ADMIN'],
            facilityScope: null,
          },
        }),
      ),
    );
    renderRoute('/');
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
    const header = screen.getByRole('banner');
    expect(await within(header).findByText('jane.doe')).toBeInTheDocument();
    expect(within(header).getByText('FACILITY_ADMIN, SUPER_ADMIN')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expectSidebarLinks();
  });

  it('records the GET /me request of the default handler', async () => {
    renderRoute('/');
    expect(await screen.findByText('cce-admin')).toBeInTheDocument();
    expect(recordedRequests()).toEqual([
      { method: 'GET', path: '/admin/v1/me', query: {}, body: undefined },
    ]);
  });

  it('shows a /me 403 in the header, and the sidebar still shows three links', async () => {
    server.use(
      http.get('*/admin/v1/me', () => errorResponse(403, 'FORBIDDEN', 'Your user is not active.')),
    );
    renderRoute('/');
    const header = screen.getByRole('banner');
    const alert = await within(header).findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to do this.');
    expect(alert).toHaveTextContent('Your user is not active.');
    expectSidebarLinks();
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('shows a /me 403 without the envelope in the header', async () => {
    server.use(http.get('*/admin/v1/me', () => new HttpResponse('Forbidden', { status: 403 })));
    renderRoute('/');
    const alert = await within(screen.getByRole('banner')).findByRole('alert');
    expect(alert).toHaveTextContent(/^You do not have permission to do this\.$/);
    expectSidebarLinks();
  });

  it('calls logout on Sign out', async () => {
    const { user } = renderRoute('/');
    await screen.findByText('cce-admin');
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('shows the not-found page for an unknown path, inside the shell', async () => {
    const { user, router } = renderRoute('/no/such/page');
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    await screen.findByText('cce-admin');
    expectSidebarLinks();
    await user.click(screen.getByRole('link', { name: 'Go to users' }));
    expect(router.state.location.pathname).toBe('/users');
  });

  it('redirects / to /users', async () => {
    const { router } = renderRoute('/');
    await screen.findByText('cce-admin');
    expect(router.state.location.pathname).toBe('/users');
  });
});
