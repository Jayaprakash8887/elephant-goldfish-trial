// Start-up: configuration, then sign-in, then render. There is no code path that renders a route
// without a Keycloak session, and no flag or static token that skips initAuth() (C-01, Goal 7).
import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { initAuth } from './auth/keycloak';
import { readConfig } from './config';
import { createQueryClient } from './hooks/queryClient';
import { StartupErrorScreen } from './pages/StartupErrorScreen';
import { routes } from './routes';

export async function bootstrap(root: HTMLElement): Promise<void> {
  const reactRoot = createRoot(root);

  const result = readConfig();
  if (!result.ok) {
    // No auth and no API calls without a valid configuration.
    reactRoot.render(
      <StartupErrorScreen
        title="The console cannot start"
        lines={result.missing.map((name) => `Configuration error: ${name} is not set.`)}
      />,
    );
    return;
  }

  try {
    await initAuth(result.config);
  } catch {
    reactRoot.render(
      <StartupErrorScreen
        title="Sign-in failed"
        lines={[
          `Sign-in did not complete with the sign-in service at ${result.config.keycloakUrl}.`,
        ]}
      />,
    );
    return;
  }

  reactRoot.render(
    <StrictMode>
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={createBrowserRouter(routes)} />
      </QueryClientProvider>
    </StrictMode>,
  );
}
