import { useBlocker } from 'react-router';

/**
 * Blocks leaving the route, or switching the Role detail tab (which changes the URL), while
 * `isDirty` is true (UI-AD-13). The page shows a ConfirmDialog while `blocked` is true.
 */
export function useUnsavedChangesGuard(isDirty: boolean): {
  blocked: boolean;
  proceed: () => void;
  reset: () => void;
} {
  const blocker = useBlocker(isDirty);
  return {
    blocked: blocker.state === 'blocked',
    proceed: () => blocker.proceed?.(),
    reset: () => blocker.reset?.(),
  };
}
