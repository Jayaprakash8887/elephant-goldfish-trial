// User create (F-11, CUJ 2). One submit sends one `POST /users`; there is no separate sync step.
// On 201 the console opens the new user and passes the result in navigation state. On 502 the
// form stays on screen with SyncFailedNotice (USER_CREATE) and keeps its input and its submit
// button, so the same request can be sent again, which links the Keycloak account the first
// attempt created (admin-service §5.1).
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import type { CreateUserRequest, PartialState } from '../api/contract';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { PageHeader } from '../components/shared/PageHeader';
import { SyncFailedNotice } from '../components/shared/SyncFailedNotice';
import { UserCreateForm } from '../components/users/UserCreateForm';
import { useAllRoles } from '../hooks/useRoles';
import { useCreateUser } from '../hooks/useUsers';
import { describeError } from '../utils/describeError';

type Notice = { partialState: PartialState; message: string };

export function UserCreatePage() {
  const navigate = useNavigate();
  const allRoles = useAllRoles();
  const createUser = useCreateUser();
  const [notice, setNotice] = useState<Notice>();
  const serverError = useMemo(
    () => (createUser.error ? describeError(createUser.error) : undefined),
    [createUser.error],
  );

  async function submit(body: CreateUserRequest) {
    setNotice(undefined);
    try {
      const { data, warnings } = await createUser.mutateAsync(body);
      void navigate(`/users/${data.id}`, { state: { created: true, email: data.email, warnings } });
    } catch (error) {
      const described = describeError(error);
      if (described.kind === 'syncFailed' && described.partialState) {
        setNotice({ partialState: described.partialState, message: described.message });
      }
      // Every other error is shown by the form through `serverError`.
    }
  }

  const rolesNotice = allRoles.isPending ? (
    <LoadingSpinner />
  ) : allRoles.isError ? (
    <InlineError error={describeError(allRoles.error)} onRetry={() => void allRoles.refetch()} />
  ) : undefined;

  return (
    <div className="space-y-4">
      {/* DESIGN-GAP: the design gives no page title for User create; "New user" is used. */}
      <PageHeader title="New user" />
      {notice && (
        <SyncFailedNotice partialState={notice.partialState} message={notice.message} usersLink />
      )}
      <UserCreateForm
        roles={allRoles.data?.items ?? []}
        truncated={allRoles.data?.truncated ?? false}
        onSubmit={submit}
        pending={createUser.isPending}
        serverError={serverError}
        rolesNotice={rolesNotice}
      />
    </div>
  );
}
