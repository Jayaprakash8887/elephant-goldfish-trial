import type { SyncStatus } from '../../api/contract';
import { formatInstant } from '../../utils/format';

const STYLES: Record<SyncStatus, string> = {
  SYNCED: 'bg-green-100 text-green-800',
  NEVER: 'bg-gray-100 text-gray-700',
  FAILED: 'bg-red-100 text-red-800',
};

/** The role's last gateway sync, as the server reports it; never set by the console. */
export function SyncStatusBadge({
  syncStatus,
  lastSyncAt,
}: {
  syncStatus: SyncStatus;
  lastSyncAt: string | null;
}) {
  const text =
    syncStatus === 'SYNCED'
      ? `Synced · ${formatInstant(lastSyncAt)}`
      : syncStatus === 'FAILED'
        ? `Sync failed · ${formatInstant(lastSyncAt)}`
        : 'Never synced';
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${STYLES[syncStatus]}`}>
      {text}
    </span>
  );
}
