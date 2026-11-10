// The one component for a KEYCLOAK_SYNC_FAILED (502) response (F-05, Goal 3). It is never a
// generic toast: it says what the service recorded and what to do next.
import { Link } from 'react-router';
import type { PartialState, PartialStateOperation } from '../../api/contract';

const NO_CHANGE_TEXT = 'The service recorded no change. You can try again.';

const ROLE_SYNC_TEXT = 'The sync failed. The saved permissions are unchanged. Use Re-sync.';

/** The text shown when `completedSteps` is not empty (design, `SyncFailedNotice` table). */
const STEPS_COMPLETED_TEXT: Readonly<Partial<Record<PartialStateOperation, string>>> = {
  USER_CREATE:
    'The sign-in account may exist in Keycloak without a user here. Submit the form again with the same values: the service links the account and finishes. If it fails again, pass the ids below to an operator.',
  USER_PROFILE_UPDATE:
    'Keycloak may have the new values while this page shows the old ones. Save the profile again with the same values. If it fails again, pass the ids below to an operator.',
  USER_STATUS_CHANGE:
    'Keycloak may already have the new status, but it was not saved here. Choose the same status again. If it fails again, pass the ids below to an operator.',
  USER_ROLES_UPDATE:
    'Some Keycloak role mappings may have changed, but the roles were not saved here. Save the same roles again. If it fails again, pass the ids below to an operator.',
  // USER_PASSWORD_RESET has no text: its steps are always empty, so the empty-steps rule applies.
  ROLE_PERMISSIONS_SAVE:
    'The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds.',
};

function nextStepText(partialState: PartialState): string | undefined {
  if (partialState.operation === 'ROLE_SYNC') return ROLE_SYNC_TEXT;
  if (partialState.completedSteps.length === 0) return NO_CHANGE_TEXT;
  return STEPS_COMPLETED_TEXT[partialState.operation];
}

export function SyncFailedNotice({
  partialState,
  message,
  onResync,
  resyncPending,
  usersLink,
}: {
  partialState: PartialState;
  message: string;
  onResync?: () => void;
  resyncPending?: boolean;
  usersLink?: boolean;
}) {
  const nextStep = nextStepText(partialState);
  const { keycloakUserId, auditLogId } = partialState;
  const hasOperatorIds = keycloakUserId !== null || auditLogId !== null;
  return (
    <div
      role="alert"
      className="space-y-2 rounded border border-amber-400 bg-amber-50 p-3 text-sm text-amber-900"
    >
      <p>{message}</p>
      {nextStep !== undefined && <p>{nextStep}</p>}
      {hasOperatorIds && (
        <div>
          {/* DESIGN-GAP: the design gives the label "For the operator:" but not the labels of
              the two ids; "Keycloak user id:" and "Audit log id:" are used. */}
          <p>For the operator:</p>
          <ul className="list-inside list-disc font-mono text-xs">
            {keycloakUserId !== null && <li>Keycloak user id: {keycloakUserId}</li>}
            {auditLogId !== null && <li>Audit log id: {auditLogId}</li>}
          </ul>
        </div>
      )}
      {(onResync || usersLink) && (
        <div className="flex gap-3">
          {onResync && (
            <button
              type="button"
              onClick={onResync}
              disabled={resyncPending}
              className="rounded border border-amber-500 px-2 py-1 hover:bg-amber-100 disabled:opacity-50"
            >
              Re-sync
            </button>
          )}
          {usersLink && (
            // DESIGN-GAP: the table names a "Users list link" for USER_CREATE without its text;
            // "Back to users" from the fixed texts is used.
            <Link to="/users" className="underline">
              Back to users
            </Link>
          )}
        </div>
      )}
    </div>
  );
}
