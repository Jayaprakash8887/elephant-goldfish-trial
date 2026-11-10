import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { createQueryClient } from '../hooks/queryClient';
import { routes } from '../routes';

/** Renders the app's routes at `path` with a fresh query client that does not retry. */
export function renderRoute(path: string) {
  const queryClient = createQueryClient({ retry: false });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...result, user, router };
}

/** A provider wrapper with a fresh query client, for renderHook. */
export function createWrapper() {
  const queryClient = createQueryClient({ retry: false });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}
