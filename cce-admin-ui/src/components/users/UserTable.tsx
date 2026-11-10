// The Users list table (F-10). Paging is server-side: TanStack Table runs in manual-pagination
// mode with the service's `totalPages`. Each row action opens a ConfirmDialog in the page.
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { Link } from 'react-router';
import type { PageInfo, User, UserStatus } from '../../api/contract';
import { formatInstant } from '../../utils/format';
import { StatusBadge } from '../shared/StatusBadge';
import { SEND_PASSWORD_LABEL } from './UserPasswordAction';
import { otherStatuses, STATUS_ACTIONS } from './UserStatusActions';

export type UserRowAction = UserStatus | 'SEND_PASSWORD';

const column = createColumnHelper<User>();

const BUTTON =
  'rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-50 whitespace-nowrap';

export function UserTable({
  users,
  pagination,
  onAction,
  pendingUserId,
}: {
  users: User[];
  pagination: PageInfo;
  onAction: (user: User, action: UserRowAction) => void;
  pendingUserId?: string;
}) {
  const columns = [
    column.accessor('username', {
      header: 'Username',
      cell: (info) => <span className="font-mono">{info.getValue()}</span>,
    }),
    column.accessor('email', { header: 'Email' }),
    column.accessor('status', {
      header: 'Status',
      cell: (info) => <StatusBadge status={info.getValue()} />,
    }),
    column.accessor((user) => user.roles.map((role) => role.displayName).join(', '), {
      id: 'roles',
      header: 'Roles',
    }),
    column.accessor('facilityScope', {
      header: 'Facility scope',
      cell: (info) => info.getValue() ?? '—',
    }),
    column.accessor('lastLoginAt', {
      header: 'Last login',
      cell: (info) => formatInstant(info.getValue()),
    }),
    column.display({
      id: 'actions',
      header: 'Actions',
      cell: ({ row }) => {
        const user = row.original;
        const pending = pendingUserId === user.id;
        return (
          <span className="flex flex-wrap items-center gap-2">
            <Link to={`/users/${user.id}`} className="text-blue-700 underline">
              View / edit
            </Link>
            {otherStatuses(user).map((status) => (
              <button
                key={status}
                type="button"
                onClick={() => onAction(user, status)}
                disabled={pending}
                className={BUTTON}
              >
                {STATUS_ACTIONS[status].label}
              </button>
            ))}
            <button
              type="button"
              onClick={() => onAction(user, 'SEND_PASSWORD')}
              disabled={pending}
              className={BUTTON}
            >
              {SEND_PASSWORD_LABEL}
            </button>
          </span>
        );
      },
    }),
  ];

  const table = useReactTable({
    data: users,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (user) => user.id,
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
