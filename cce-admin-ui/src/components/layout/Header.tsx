import type { DescribedError } from '../../utils/describeError';
import { InlineError } from '../shared/InlineError';

/** Username and role names from `GET /me`, or the error's text in their place, and Sign out. */
export function Header({
  username,
  roles,
  error,
  onSignOut,
}: {
  username?: string;
  roles?: string[];
  error?: DescribedError;
  onSignOut: () => void;
}) {
  return (
    <header className="flex flex-1 items-center justify-end gap-4">
      {error ? (
        <InlineError error={error} />
      ) : (
        username !== undefined && (
          <div className="text-right text-sm">
            <p className="font-medium">{username}</p>
            {roles && roles.length > 0 && <p className="text-gray-600">{roles.join(', ')}</p>}
          </div>
        )
      )}
      <button
        type="button"
        onClick={onSignOut}
        className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100"
      >
        Sign out
      </button>
    </header>
  );
}
