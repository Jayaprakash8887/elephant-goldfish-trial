// The User edit Password section (F-11): "Send set-password email" behind its ConfirmDialog. No
// password passes through the console or the service (B-36). The dialog is also used by the
// Users list row action, so it is exported from here.
import { useState } from 'react';
import type { User } from '../../api/contract';
import type { DescribedError } from '../../utils/describeError';
import { ConfirmDialog } from '../shared/ConfirmDialog';
import { SuccessNotice } from '../shared/SuccessNotice';
import { UserActionError } from './UserStatusActions';

export const SEND_PASSWORD_LABEL = 'Send set-password email';

/** "Set-password email sent to {email}.", with the address from the response. */
export function passwordSentText(email: string): string {
  return `Set-password email sent to ${email}.`;
}

export function PasswordConfirmDialog({
  user,
  pending,
  onConfirm,
  onCancel,
}: {
  user: User;
  pending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <ConfirmDialog
      title={`Send a set-password email to ${user.username}?`}
      body={`Keycloak sends an email to ${user.email} with a link to set a new password.`}
      confirmLabel="Send"
      pending={pending}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  );
}

export function UserPasswordAction({
  user,
  onSend,
  pending,
  sentTo,
  error,
}: {
  user: User;
  onSend: () => void;
  pending: boolean;
  sentTo?: string;
  error?: DescribedError;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={() => setConfirming(true)}
        disabled={pending}
        className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100 disabled:opacity-50"
      >
        {SEND_PASSWORD_LABEL}
      </button>
      {sentTo !== undefined && <SuccessNotice message={passwordSentText(sentTo)} />}
      {error && <UserActionError error={error} />}
      {confirming && (
        <PasswordConfirmDialog
          user={user}
          onConfirm={() => {
            setConfirming(false);
            onSend();
          }}
          onCancel={() => setConfirming(false)}
        />
      )}
    </div>
  );
}
