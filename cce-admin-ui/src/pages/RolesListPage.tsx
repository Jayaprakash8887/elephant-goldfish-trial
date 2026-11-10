// The Roles list (F-12): server-side paging in the URL, an inline create form, and Delete
// behind a ConfirmDialog. Results of a delete are shown in the message area above the table.
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import type { CreateRoleRequest, Role } from '../api/contract';
import { RoleCreateForm } from '../components/roles/RoleCreateForm';
import { RoleTable } from '../components/roles/RoleTable';
import { ConfirmDialog } from '../components/shared/ConfirmDialog';
import { EmptyState } from '../components/shared/EmptyState';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { PageHeader } from '../components/shared/PageHeader';
import { Pager } from '../components/shared/Pager';
import { clampPageToTotal, usePagingParams } from '../hooks/usePagingParams';
import { useCreateRole, useDeleteRole, useRoleList } from '../hooks/useRoles';
import { describeError } from '../utils/describeError';

const DELETE_BODY =
  'The role, its menu grants and its permission rules are deleted. The name cannot be reused until a later gateway sync removes its realm role.';

export function RolesListPage() {
  const navigate = useNavigate();
  const { page, pageSize, setPage, setPageSize } = usePagingParams();
  const list = useRoleList({ page, pageSize });
  const createRole = useCreateRole();
  const deleteRole = useDeleteRole();
  const [deleting, setDeleting] = useState<Role>();

  const createError = useMemo(
    () => (createRole.error ? describeError(createRole.error) : undefined),
    [createRole.error],
  );
  const deleteError = useMemo(
    () => (deleteRole.error ? describeError(deleteRole.error) : undefined),
    [deleteRole.error],
  );

  // Past the last page (for example after deleting the last role on it): go to the last page.
  // DESIGN-GAP: the design gives this correction for the Users list; the Roles list has "paging
  // like the Users list", so the same correction is applied here.
  const totalPages = list.data?.pagination.totalPages;
  useEffect(() => {
    if (totalPages === undefined) return;
    const clamped = clampPageToTotal(page, totalPages);
    if (clamped !== page) setPage(clamped);
  }, [page, totalPages, setPage]);

  async function create(body: CreateRoleRequest) {
    try {
      const { data, warnings } = await createRole.mutateAsync(body);
      if (warnings.some((warning) => warning.code === 'ROLE_SYNC_FAILED')) {
        await navigate(`/roles/${data.id}?tab=permissions`, {
          state: { createdWithSyncWarning: true },
        });
      } else {
        await navigate(`/roles/${data.id}`);
      }
    } catch {
      // Shown through `serverError` on the form.
    }
  }

  function confirmDelete(role: Role) {
    deleteRole.mutate(role.id, { onSettled: () => setDeleting(undefined) });
  }

  return (
    <div>
      <PageHeader title="Roles" />
      <RoleCreateForm onSubmit={create} pending={createRole.isPending} serverError={createError} />
      <div className="mb-3">{deleteError && <InlineError error={deleteError} mutation />}</div>
      {list.isPending ? (
        <LoadingSpinner />
      ) : list.isError ? (
        <InlineError error={describeError(list.error)} onRetry={() => void list.refetch()} />
      ) : (
        <div className="space-y-3">
          {list.data.items.length === 0 ? (
            // DESIGN-GAP: the design creates EmptyState but gives no text for an empty roles
            // page; "No roles on this page." is used.
            <EmptyState message="No roles on this page." />
          ) : (
            <RoleTable
              roles={list.data.items}
              pagination={list.data.pagination}
              onDelete={setDeleting}
              pendingRoleId={deleteRole.isPending ? deleteRole.variables : undefined}
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
      {deleting && (
        <ConfirmDialog
          title={`Delete ${deleting.name}?`}
          body={DELETE_BODY}
          confirmLabel="Delete"
          pending={deleteRole.isPending}
          onConfirm={() => confirmDelete(deleting)}
          onCancel={() => setDeleting(undefined)}
        />
      )}
    </div>
  );
}
