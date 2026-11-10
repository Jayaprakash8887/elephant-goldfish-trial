// The User edit Status section (F-10, F-11): one button for each status the user does not have,
// each behind its ConfirmDialog. The confirmation texts and the result display are also used by
// the Users list row actions, so they are exported from here.
import { useState } from 'react';
import type { User, UserStatus } from '../../api/contract';
import { USER_STATUSES } from '../../schemas/rules';
import type { DescribedError } from '../../utils/describeError';
import { ConfirmDialog } from '../shared/ConfirmDialog';
import { InlineError } from '../shared/InlineError';
import { STATUS_TEXT } from '../shared/StatusBadge';
import { SyncFailedNotice } from '../shared/SyncFailedNotice';

/** The button label and the confirmation body of each target status (Fixed UI texts). */
export const STATUS_ACTIONS: Readonly<Record<UserStatus, { label: string; body: string }>> = {
  ACTIVE: {
    label: 'Activate',
    body: 'Their Keycloak account is enabled again, with the roles they hold.',
  },
  INACTIVE: {
    label: 'Deactivate',
    body: 'Their Keycloak account is disabled and their sessions end. Their roles are kept.',
  },
  LOCKED: {
    label: 'Lock',
    body: 'Their Keycloak account is disabled and their sessions end until you activate it again. Their roles are kept.',
  },
};

/** The statuses the user does not have, in the contract's order. */
export function otherStatuses(user: User): UserStatus[] {
  return USER_STATUSES.filter((status) => status !== user.status);
}

/** "{username} is now Active." (or "Inactive.", "Locked."), from the user the service returned. */
export function statusChangedText(user: User): string {
  return `${user.username} is now ${STATUS_TEXT[user.status]}.`;
}

export function StatusConfirmDialog({
  user,
  status,
  pending,
  onConfirm,
  onCancel,
}: {
  user: User;
  status: UserStatus;
  pending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { label, body } = STATUS_ACTIONS[status];
  return (
    <ConfirmDialog
      title={`${label} ${user.username}?`}
      body={body}
      confirmLabel={label}
      pending={pending}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  );
}

/**
 * A user write's error: SyncFailedNotice for a KEYCLOAK_SYNC_FAILED with its partial state (F-05),
 * otherwise InlineError as a mutation error (conflicts such as LAST_SYSTEM_ADMIN show the server
 * message next to the action).
 */
export function UserActionError({ error }: { error: DescribedError }) {
  if (error.kind === 'syncFailed' && error.partialState) {
    return <SyncFailedNotice partialState={error.partialState} message={error.message} />;
  }
  return <InlineError error={error} mutation />;
}

export function UserStatusActions({
  user,
  onChange,
  pendingStatus,
  error,
}: {
  user: User;
  onChange: (status: UserStatus) => void;
  pendingStatus?: UserStatus;
  error?: DescribedError;
}) {
  const [confirming, setConfirming] = useState<UserStatus>();
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {otherStatuses(user).map((status) => (
          <button
            key={status}
            type="button"
            onClick={() => setConfirming(status)}
            disabled={pendingStatus !== undefined}
            className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100 disabled:opacity-50"
          >
            {STATUS_ACTIONS[status].label}
          </button>
        ))}
      </div>
      {error && <UserActionError error={error} />}
      {confirming && (
        <StatusConfirmDialog
          user={user}
          status={confirming}
          onConfirm={() => {
            setConfirming(undefined);
            onChange(confirming);
          }}
          onCancel={() => setConfirming(undefined)}
        />
      )}
    </div>
  );
}
