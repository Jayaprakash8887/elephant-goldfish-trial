import type { UserStatus } from '../../api/contract';

/** The badge text of each status, also used in "{username} is now Active." */
export const STATUS_TEXT: Readonly<Record<UserStatus, string>> = {
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
  LOCKED: 'Locked',
};

const STYLES: Readonly<Record<UserStatus, string>> = {
  ACTIVE: 'bg-green-100 text-green-800',
  INACTIVE: 'bg-gray-100 text-gray-700',
  LOCKED: 'bg-amber-100 text-amber-800',
};

/** A user's status: "Active" (green), "Inactive" (gray), "Locked" (amber). */
export function StatusBadge({ status }: { status: UserStatus }) {
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${STYLES[status]}`}>
      {STATUS_TEXT[status]}
    </span>
  );
}
