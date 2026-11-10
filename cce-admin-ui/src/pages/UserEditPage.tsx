// User edit (F-11, CUJ 4, CUJ 5). A header with username, status badge and last login, then four
// independent sections (Profile, Roles, Status, Password), each with its own mutation, pending
// state and error area. The two forms read their values once, when they mount, so a refetch
// after a 502 never replaces the values the administrator sent (design, `SyncFailedNotice`).
import { useMemo, useState, type ReactNode } from 'react';
import { useLocation, useParams } from 'react-router';
import type { User, Warning } from '../api/contract';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { NotFoundPanel } from '../components/shared/NotFoundPanel';
import { StatusBadge } from '../components/shared/StatusBadge';
import { SuccessNotice } from '../components/shared/SuccessNotice';
import { WarningList } from '../components/shared/WarningList';
import { UserPasswordAction } from '../components/users/UserPasswordAction';
import { UserProfileForm } from '../components/users/UserProfileForm';
import { UserRolesForm } from '../components/users/UserRolesForm';
import { statusChangedText, UserStatusActions } from '../components/users/UserStatusActions';
import { useAllRoles } from '../hooks/useRoles';
import {
  useChangeUserStatus,
  useReplaceUserRoles,
  useResetUserPassword,
  useUpdateUser,
  useUser,
} from '../hooks/useUsers';
import { describeError } from '../utils/describeError';
import { formatInstant } from '../utils/format';

/** The navigation state UserCreatePage sets after a 201. */
type CreatedState = { created: true; email: string; warnings: Warning[] };

function createdState(state: unknown): CreatedState | undefined {
  if (typeof state !== 'object' || state === null) return undefined;
  const candidate = state as Partial<CreatedState>;
  return candidate.created === true && typeof candidate.email === 'string'
    ? { created: true, email: candidate.email, warnings: candidate.warnings ?? [] }
    : undefined;
}

function described(error: unknown) {
  return error ? describeError(error) : undefined;
}

export function UserEditPage() {
  const { id = '' } = useParams();
  const user = useUser(id);

  if (user.isPending) return <LoadingSpinner />;
  if (user.data === undefined) {
    const error = describeError(user.error, { detailFetch: true });
    if (error.kind === 'notFound') {
      return (
        <NotFoundPanel
          message="This user does not exist."
          backTo="/users"
          backLabel="Back to users"
        />
      );
    }
    return <InlineError error={error} onRetry={() => void user.refetch()} />;
  }
  return (
    <div className="space-y-4">
      {/* A failed refetch keeps the page and its forms; only the error is added. */}
      {user.isRefetchError && (
        <InlineError error={describeError(user.error)} onRetry={() => void user.refetch()} />
      )}
      <UserEdit key={user.data.id} user={user.data} />
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-3 rounded border border-gray-200 bg-white p-4">
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  );
}

function UserEdit({ user }: { user: User }) {
  const location = useLocation();
  const [created] = useState(() => createdState(location.state));
  const allRoles = useAllRoles();
  const updateUser = useUpdateUser();
  const replaceRoles = useReplaceUserRoles();
  const changeStatus = useChangeUserStatus();
  const resetPassword = useResetUserPassword();
  const [statusMessage, setStatusMessage] = useState<string>();

  const profileError = useMemo(() => described(updateUser.error), [updateUser.error]);
  const rolesError = useMemo(() => described(replaceRoles.error), [replaceRoles.error]);
  const statusError = useMemo(() => described(changeStatus.error), [changeStatus.error]);
  const passwordError = useMemo(() => described(resetPassword.error), [resetPassword.error]);

  const rolesNotice = allRoles.isPending ? (
    <LoadingSpinner />
  ) : allRoles.isError ? (
    <InlineError error={describeError(allRoles.error)} onRetry={() => void allRoles.refetch()} />
  ) : undefined;

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <h1 className="font-mono text-xl font-semibold">{user.username}</h1>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <StatusBadge status={user.status} />
          {/* DESIGN-GAP: the design shows "last login" in the header without its label;
              "Last login: {time}" is used, "Never" when there is none. */}
          <span>Last login: {formatInstant(user.lastLoginAt)}</span>
        </div>
        {created &&
          (created.warnings.some((warning) => warning.code === 'ACTION_EMAIL_NOT_SENT') ? (
            <WarningList
              messages={[
                'User created, but the set-password email could not be sent. Use Send set-password email on this page.',
              ]}
            />
          ) : (
            <SuccessNotice
              message={`User created. A set-password email was sent to ${created.email}.`}
            />
          ))}
      </div>
      <Section title="Profile">
        <UserProfileForm
          user={user}
          onSubmit={(body) => updateUser.mutateAsync({ id: user.id, body })}
          pending={updateUser.isPending}
          error={profileError}
        />
      </Section>
      <Section title="Roles">
        <UserRolesForm
          user={user}
          roles={allRoles.data?.items ?? []}
          truncated={allRoles.data?.truncated ?? false}
          onSubmit={(body) => replaceRoles.mutateAsync({ id: user.id, body })}
          pending={replaceRoles.isPending}
          error={rolesError}
          rolesNotice={rolesNotice}
        />
      </Section>
      <Section title="Status">
        <UserStatusActions
          user={user}
          onChange={(status) => {
            setStatusMessage(undefined);
            changeStatus.mutate(
              { id: user.id, body: { status } },
              { onSuccess: (updated) => setStatusMessage(statusChangedText(updated)) },
            );
          }}
          pendingStatus={changeStatus.isPending ? changeStatus.variables.body.status : undefined}
          error={statusError}
        />
        {statusMessage && <SuccessNotice message={statusMessage} />}
      </Section>
      <Section title="Password">
        <UserPasswordAction
          user={user}
          onSend={() => resetPassword.mutate(user.id)}
          pending={resetPassword.isPending}
          sentTo={resetPassword.isSuccess ? resetPassword.data.email : undefined}
          error={passwordError}
        />
      </Section>
    </div>
  );
}
