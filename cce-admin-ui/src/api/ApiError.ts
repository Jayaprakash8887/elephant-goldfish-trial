import type { FieldError, PartialState } from './contract';

/** `fetch` itself failed (status 0). The service never sends a code starting with `CLIENT_`. */
export const CLIENT_NETWORK_ERROR = 'CLIENT_NETWORK_ERROR';

/** The response was not the service's envelope (empty body, HTML from a proxy, and so on). */
export const CLIENT_UNEXPECTED_RESPONSE = 'CLIENT_UNEXPECTED_RESPONSE';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: FieldError[];
  readonly partialState?: PartialState;
  /** True when the error body was the service's error envelope. */
  readonly hasEnvelope: boolean;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    details?: FieldError[];
    partialState?: PartialState;
    hasEnvelope: boolean;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.details = init.details;
    this.partialState = init.partialState;
    this.hasEnvelope = init.hasEnvelope;
  }
}
