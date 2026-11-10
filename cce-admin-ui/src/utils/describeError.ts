// Classifies any error into one of the kinds of the design's "Error presentation" table. Screens
// choose how to show an error by its `kind` and `code`, never by parsing `message`.
import { ApiError, CLIENT_NETWORK_ERROR, CLIENT_UNEXPECTED_RESPONSE } from '../api/ApiError';
import type { FieldError, PartialState } from '../api/contract';

export type ErrorKind =
  | 'forbidden'
  | 'syncFailed'
  | 'validation'
  | 'conflict'
  | 'notFound'
  | 'unauthenticated'
  | 'network'
  | 'unexpected';

export interface DescribedError {
  kind: ErrorKind;
  code: string;
  status: number;
  /**
   * The server's message; empty when the response did not carry the service's envelope.
   * DESIGN-GAP: the design shows "the server message when there is one" but does not say how a
   * DescribedError marks that there is none; an empty string is used.
   */
  message: string;
  details?: FieldError[];
  partialState?: PartialState;
}

const CONFLICT_CODES: ReadonlySet<string> = new Set([
  'EMAIL_IN_USE',
  'USERNAME_IN_USE',
  'DUPLICATE_ROLE_NAME',
  'DUPLICATE_MENU_KEY',
  'ROLE_IN_USE',
  'SYSTEM_ROLE_IMMUTABLE',
  'LAST_SYSTEM_ADMIN',
  'ROLE_NOT_ASSIGNABLE',
  'ROLE_NOT_SYNCED',
  'MENU_HAS_CHILDREN',
]);

const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'USER_NOT_FOUND',
  'ROLE_NOT_FOUND',
  'MENU_NOT_FOUND',
]);

function kindOf(error: ApiError, detailFetch: boolean): ErrorKind {
  const { code, status } = error;
  if (code === 'FORBIDDEN' || (status === 403 && !error.hasEnvelope)) return 'forbidden';
  if (code === 'KEYCLOAK_SYNC_FAILED') return 'syncFailed';
  if (code === 'VALIDATION_ERROR') {
    // A detail page's malformed path id means the same as "not found" (admin-service §4.1).
    const details = error.details ?? [];
    if (detailFetch && details.length === 1 && details[0]?.field === 'id') return 'notFound';
    return 'validation';
  }
  if (CONFLICT_CODES.has(code)) return 'conflict';
  if (NOT_FOUND_CODES.has(code)) return 'notFound';
  if (status === 401) return 'unauthenticated';
  if (code === CLIENT_NETWORK_ERROR) return 'network';
  return 'unexpected';
}

export function describeError(
  error: unknown,
  options: { detailFetch?: boolean } = {},
): DescribedError {
  if (!(error instanceof ApiError)) {
    // DESIGN-GAP: the design says non-ApiError values become `unexpected` but gives no status or
    // code for them. Status 0 and CLIENT_UNEXPECTED_RESPONSE are used.
    return { kind: 'unexpected', code: CLIENT_UNEXPECTED_RESPONSE, status: 0, message: '' };
  }
  const described: DescribedError = {
    kind: kindOf(error, options.detailFetch === true),
    code: error.code,
    status: error.status,
    message: error.hasEnvelope ? error.message : '',
  };
  if (error.details) described.details = error.details;
  if (error.partialState) described.partialState = error.partialState;
  return described;
}
