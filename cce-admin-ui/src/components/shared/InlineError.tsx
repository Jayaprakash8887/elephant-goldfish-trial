import type { DescribedError } from '../../utils/describeError';

const API_DISAGREEMENT_CODES: ReadonlySet<string> = new Set(['NOT_FOUND', 'METHOD_NOT_ALLOWED']);

/** The fixed text of the design's kinds table for one error. */
function fixedText(error: DescribedError): string | undefined {
  switch (error.kind) {
    case 'forbidden':
      return 'You do not have permission to do this.';
    case 'unauthenticated':
      return 'The admin service did not accept your sign-in. Sign out and sign in again.';
    case 'network':
      return 'Cannot reach the admin service.';
    case 'unexpected':
      return API_DISAGREEMENT_CODES.has(error.code)
        ? `The console and the admin service disagree about the API (HTTP ${error.status}, ${error.code}).`
        : `Something went wrong (HTTP ${error.status}, ${error.code}).`;
    default:
      // DESIGN-GAP: the kinds table shows syncFailed, validation, conflict and notFound with
      // other components (SyncFailedNotice, field messages, NotFoundPanel) or "inline next to the
      // action" with the server message. When one reaches InlineError, only the server message
      // is shown.
      return undefined;
  }
}

export function InlineError({
  error,
  onRetry,
  mutation,
}: {
  error: DescribedError;
  onRetry?: () => void;
  mutation?: boolean;
}) {
  const text = fixedText(error);
  // The server message follows the fixed text for forbidden (F-04) and stands alone for the
  // kinds without a fixed text.
  const showServerMessage =
    error.message !== '' && (error.kind === 'forbidden' || text === undefined);
  // DESIGN-GAP: item 59 says the "result is unknown" text shows when `mutation` is true; the
  // error-presentation section limits it to network and unexpected errors, the kinds after
  // which the refetch rule runs. Both conditions are applied.
  const resultUnknown =
    mutation === true && (error.kind === 'network' || error.kind === 'unexpected');
  return (
    <div role="alert" className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-800">
      {text !== undefined && <p>{text}</p>}
      {showServerMessage && <p>{error.message}</p>}
      {resultUnknown && <p>The result is unknown; the page was refreshed.</p>}
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 rounded border border-red-400 px-2 py-1 hover:bg-red-100"
        >
          Retry
        </button>
      )}
    </div>
  );
}
