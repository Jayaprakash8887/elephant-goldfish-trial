// The Permissions tab's sync panel (F-14): the badge, the help text (UI-AD-06) and Re-sync. The
// status is the server's; Re-sync stays enabled while the rules editor has unsaved edits, and for
// system roles (admin-service AD-07).
import type { SyncStatus } from '../../api/contract';
import { SyncStatusBadge } from '../shared/SyncStatusBadge';

export function SyncStatusPanel({
  syncStatus,
  lastSyncAt,
  onResync,
  pending,
}: {
  syncStatus: SyncStatus;
  lastSyncAt: string | null;
  onResync: () => void;
  pending: boolean;
}) {
  return (
    <section
      aria-label="Gateway sync"
      className="flex flex-wrap items-center gap-3 rounded border border-gray-200 bg-white p-3 text-sm"
    >
      <SyncStatusBadge syncStatus={syncStatus} lastSyncAt={lastSyncAt} />
      <p className="text-gray-700">Synced means the gateway&apos;s sync call succeeded.</p>
      <button
        type="button"
        onClick={onResync}
        disabled={pending}
        className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-100 disabled:opacity-50"
      >
        Re-sync
      </button>
    </section>
  );
}
