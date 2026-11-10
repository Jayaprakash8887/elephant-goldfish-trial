import { logout } from './keycloak';

/**
 * Sign-out for the shell (UI-DEV-1): AppLayout passes it to the header, so the header never
 * imports auth/.
 */
export function useAuth(): { logout: () => Promise<void> } {
  return { logout };
}
