// The Roles list table (F-12). Paging is server-side: TanStack Table runs in manual-pagination
// mode with the service's `totalPages`.
import { LockClosedIcon } from '@heroicons/react/24/outline';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { Link } from 'react-router';
import type { PageInfo, Role } from '../../api/contract';
import { SyncStatusBadge } from '../shared/SyncStatusBadge';

const column = createColumnHelper<Role>();

export function RoleTable({
  roles,
  pagination,
  onDelete,
  pendingRoleId,
}: {
  roles: Role[];
  pagination: PageInfo;
  onDelete: (role: Role) => void;
  pendingRoleId?: string;
}) {
  const columns = [
    column.accessor('name', {
      header: 'Name',
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-1 font-mono">
          {row.original.name}
          {row.original.isSystem && (
            <span role="img" aria-label="System role">
              <LockClosedIcon aria-hidden="true" className="h-4 w-4" />
            </span>
          )}
          {!row.original.isAssignable && (
            <span className="rounded bg-gray-100 px-2 py-0.5 font-sans text-xs text-gray-700">
              Service account, cannot be assigned
            </span>
          )}
        </span>
      ),
    }),
    column.accessor('displayName', { header: 'Display name' }),
    column.accessor('description', { header: 'Description', cell: (info) => info.getValue() }),
    column.accessor('userCount', { header: 'Users' }),
    column.display({
      id: 'sync',
      header: 'Sync',
      cell: ({ row }) => (
        <SyncStatusBadge
          syncStatus={row.original.syncStatus}
          lastSyncAt={row.original.lastSyncAt}
        />
      ),
    }),
    column.display({
      id: 'actions',
      header: 'Actions',
      cell: ({ row }) => (
        <RoleActions role={row.original} onDelete={onDelete} pendingRoleId={pendingRoleId} />
      ),
    }),
  ];

  const table = useReactTable({
    data: roles,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (role) => role.id,
    manualPagination: true,
    pageCount: pagination.totalPages,
    state: { pagination: { pageIndex: pagination.page - 1, pageSize: pagination.pageSize } },
  });

  return (
    <table className="w-full border-collapse bg-white text-left text-sm">
      <thead>
        {table.getHeaderGroups().map((group) => (
          <tr key={group.id} className="border-b border-gray-200">
            {group.headers.map((header) => (
              <th key={header.id} className="px-3 py-2 font-medium">
                {flexRender(header.column.columnDef.header, header.getContext())}
              </th>
            ))}
          </tr>
        ))}
      </thead>
      <tbody>
        {table.getRowModel().rows.map((row) => (
          <tr key={row.id} className="border-b border-gray-100">
            {row.getVisibleCells().map((cell) => (
              <td key={cell.id} className="px-3 py-2 align-top">
                {flexRender(cell.column.columnDef.cell, cell.getContext())}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** "Open", and "Delete" for non-system roles, disabled while local users hold the role. */
function RoleActions({
  role,
  onDelete,
  pendingRoleId,
}: {
  role: Role;
  onDelete: (role: Role) => void;
  pendingRoleId?: string;
}) {
  const assigned = role.userCount > 0;
  return (
    <span className="flex flex-wrap items-center gap-2">
      <Link to={`/roles/${role.id}`} className="text-blue-700 underline">
        Open
      </Link>
      {!role.isSystem && (
        <>
          <button
            type="button"
            onClick={() => onDelete(role)}
            disabled={assigned || pendingRoleId === role.id}
            className="rounded border border-red-300 px-2 py-0.5 text-red-700 hover:bg-red-50 disabled:opacity-50"
          >
            Delete
          </button>
          {assigned && (
            <span className="text-xs text-gray-600">Assigned to {role.userCount} users</span>
          )}
        </>
      )}
    </span>
  );
}
