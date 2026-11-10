// The Users list (F-10). Filters and paging live in the URL search parameters `status`, `roleId`,
// `facilityScope`, `q`, `page` and `pageSize`, which map one-to-one to the `GET /users` query.
// The two text filters wait 300 ms after the last keystroke; every filter change sets `page` to
// 1. Row actions run behind a ConfirmDialog, and their result is shown in a message area above
// the table that names the user.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { User, UserListQuery, UserStatus } from '../api/contract';
import { EmptyState } from '../components/shared/EmptyState';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { PageHeader } from '../components/shared/PageHeader';
import { Pager } from '../components/shared/Pager';
import { SuccessNotice } from '../components/shared/SuccessNotice';
import { UserFilters, type UserFilterName } from '../components/users/UserFilters';
import { PasswordConfirmDialog, passwordSentText } from '../components/users/UserPasswordAction';
import {
  StatusConfirmDialog,
  statusChangedText,
  UserActionError,
} from '../components/users/UserStatusActions';
import { UserTable, type UserRowAction } from '../components/users/UserTable';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { clampPageToTotal, usePagingParams } from '../hooks/usePagingParams';
import { useAllRoles } from '../hooks/useRoles';
import { useChangeUserStatus, useResetUserPassword, useUserList } from '../hooks/useUsers';
import { USER_STATUSES } from '../schemas/rules';
import { describeError, type DescribedError } from '../utils/describeError';

const DEBOUNCE_MS = 300;

type RowResult = { username: string; success?: string; error?: DescribedError };

function statusParam(value: string | null): UserStatus | undefined {
  // DESIGN-GAP: the design corrects invalid paging in the URL but says nothing about an invalid
  // `status`; a value outside the three statuses is not sent (the filter shows All).
  return USER_STATUSES.find((status) => status === value);
}

/** Writes one debounced text draft to the URL once it changes, with `page` set to 1. */
function useDebouncedFilter(
  draft: string,
  write: (name: UserFilterName, value: string) => void,
  name: UserFilterName,
) {
  const debounced = useDebouncedValue(draft, DEBOUNCE_MS);
  const written = useRef(debounced);
  useEffect(() => {
    if (debounced === written.current) return;
    written.current = debounced;
    write(name, debounced.trim());
  }, [debounced, write, name]);
}

export function UsersListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { page, pageSize, setPage, setPageSize } = usePagingParams();
  const status = statusParam(searchParams.get('status'));
  const roleId = searchParams.get('roleId') ?? '';
  const facilityScope = searchParams.get('facilityScope') ?? '';
  const q = searchParams.get('q') ?? '';
  const [drafts, setDrafts] = useState({ facilityScope, q });

  // DESIGN-GAP: item 83 says each filter is written "with resetPage()". React Router's
  // setSearchParams does not queue updates, so a second call in the same tick would overwrite the
  // first; the filter and `page=1` are written in one update instead.
  const writeFilter = useCallback(
    (name: UserFilterName, value: string) => {
      void setSearchParams((current) => {
        const next = new URLSearchParams(current);
        if (value === '') next.delete(name);
        else next.set(name, value);
        next.set('page', '1');
        return next;
      });
    },
    [setSearchParams],
  );
  useDebouncedFilter(drafts.q, writeFilter, 'q');
  useDebouncedFilter(drafts.facilityScope, writeFilter, 'facilityScope');

  const query: UserListQuery = {
    status,
    roleId: roleId || undefined,
    facilityScope: facilityScope || undefined,
    q: q || undefined,
    page,
    pageSize,
  };
  const list = useUserList(query);
  const allRoles = useAllRoles();
  const changeStatus = useChangeUserStatus();
  const resetPassword = useResetUserPassword();
  const [confirming, setConfirming] = useState<{ user: User; action: UserRowAction }>();
  const [result, setResult] = useState<RowResult>();

  // Past the last page: a response with `data: []` while `page > totalPages >= 1` sets `page` to
  // `totalPages`, which refetches.
  const response = list.isPlaceholderData ? undefined : list.data;
  useEffect(() => {
    if (!response || response.items.length > 0) return;
    const clamped = clampPageToTotal(page, response.pagination.totalPages);
    if (clamped !== page) setPage(clamped);
  }, [response, page, setPage]);

  function onFilterChange(name: UserFilterName, value: string) {
    if (name === 'q' || name === 'facilityScope') {
      setDrafts((current) => ({ ...current, [name]: value }));
    } else {
      writeFilter(name, value);
    }
  }

  function confirmAction(user: User, action: UserRowAction) {
    setResult(undefined);
    const onError = (error: unknown) =>
      setResult({ username: user.username, error: describeError(error) });
    const onSettled = () => setConfirming(undefined);
    if (action === 'SEND_PASSWORD') {
      resetPassword.mutate(user.id, {
        onSuccess: ({ email }) =>
          setResult({ username: user.username, success: passwordSentText(email) }),
        onError,
        onSettled,
      });
    } else {
      changeStatus.mutate(
        { id: user.id, body: { status: action } },
        {
          onSuccess: (updated) =>
            setResult({ username: user.username, success: statusChangedText(updated) }),
          onError,
          onSettled,
        },
      );
    }
  }

  const pendingUserId = changeStatus.isPending
    ? changeStatus.variables.id
    : resetPassword.isPending
      ? resetPassword.variables
      : undefined;
  const actionPending = changeStatus.isPending || resetPassword.isPending;

  const filterValues = useMemo(
    () => ({
      status: status ?? ('' as const),
      roleId,
      facilityScope: drafts.facilityScope,
      q: drafts.q,
    }),
    [status, roleId, drafts],
  );

  return (
    <div>
      <PageHeader
        title="Users"
        actions={
          // DESIGN-GAP: the design gives User create its own route but no way to reach it from
          // the list; a "New user" link to /users/new is shown in the page header.
          <Link
            to="/users/new"
            className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800"
          >
            New user
          </Link>
        }
      />
      <UserFilters
        values={filterValues}
        roles={allRoles.data?.items ?? []}
        truncated={allRoles.data?.truncated ?? false}
        onChange={onFilterChange}
      />
      {result && (
        // DESIGN-GAP: the design says the message area "names the user" without a text; the
        // username is shown above the result.
        <section aria-label="Row action result" className="mb-3 space-y-1">
          <p className="text-sm font-medium">{result.username}</p>
          {result.success && <SuccessNotice message={result.success} />}
          {result.error && <UserActionError error={result.error} />}
        </section>
      )}
      {list.isPending ? (
        <LoadingSpinner />
      ) : list.isError ? (
        <InlineError error={describeError(list.error)} onRetry={() => void list.refetch()} />
      ) : (
        <div className="space-y-3">
          {list.data.items.length === 0 ? (
            // DESIGN-GAP: the design gives no text for an empty users page; this one is used.
            <EmptyState message="No users on this page." />
          ) : (
            <UserTable
              users={list.data.items}
              pagination={list.data.pagination}
              onAction={(user, action) => setConfirming({ user, action })}
              pendingUserId={pendingUserId}
            />
          )}
          <Pager
            page={page}
            totalPages={list.data.pagination.totalPages}
            totalItems={list.data.pagination.totalItems}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </div>
      )}
      {confirming &&
        (confirming.action === 'SEND_PASSWORD' ? (
          <PasswordConfirmDialog
            user={confirming.user}
            pending={actionPending}
            onConfirm={() => confirmAction(confirming.user, confirming.action)}
            onCancel={() => setConfirming(undefined)}
          />
        ) : (
          <StatusConfirmDialog
            user={confirming.user}
            status={confirming.action}
            pending={actionPending}
            onConfirm={() => confirmAction(confirming.user, confirming.action)}
            onCancel={() => setConfirming(undefined)}
          />
        ))}
    </div>
  );
}
