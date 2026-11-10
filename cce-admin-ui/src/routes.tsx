// The route objects, shared by bootstrap (createBrowserRouter) and the tests
// (createMemoryRouter). Each page's route is added in the step that creates the page.
import { Navigate, type RouteObject } from 'react-router';
import { AppLayout } from './pages/AppLayout';
import { MenusPage } from './pages/MenusPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { RoleDetailPage } from './pages/RoleDetailPage';
import { RolesListPage } from './pages/RolesListPage';
import { UserCreatePage } from './pages/UserCreatePage';
import { UserEditPage } from './pages/UserEditPage';
import { UsersListPage } from './pages/UsersListPage';

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/users" replace /> },
      { path: 'users', element: <UsersListPage /> },
      { path: 'users/new', element: <UserCreatePage /> },
      { path: 'users/:id', element: <UserEditPage /> },
      { path: 'roles', element: <RolesListPage /> },
      { path: 'roles/:id', element: <RoleDetailPage /> },
      { path: 'menus', element: <MenusPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
