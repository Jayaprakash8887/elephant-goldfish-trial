// The route objects, shared by bootstrap (createBrowserRouter) and the tests
// (createMemoryRouter). Each page's route is added in the step that creates the page; until
// step 5 the `/` redirect to `/users` lands on the not-found page.
import { Navigate, type RouteObject } from 'react-router';
import { AppLayout } from './pages/AppLayout';
import { NotFoundPage } from './pages/NotFoundPage';

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/users" replace /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
