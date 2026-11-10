// The typed fetch wrapper (F-02). The only module that calls `fetch`.
import { getToken, login, refreshToken } from '../auth/keycloak';
import { getApiBaseUrl } from '../config';
import { ApiError, CLIENT_NETWORK_ERROR, CLIENT_UNEXPECTED_RESPONSE } from './ApiError';
import type { ErrorEnvelope, SuccessEnvelope } from './contract';

type Query = Record<string, string | number | null | undefined>;

interface RequestOptions {
  query?: Query;
  body?: unknown;
  signal?: AbortSignal;
}

function buildUrl(path: string, query?: Query): string {
  const url = `${getApiBaseUrl()}/admin/v1${path}`;
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    params.append(name, String(value));
  }
  const search = params.toString();
  return search ? `${url}?${search}` : url;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isErrorEnvelope(value: unknown): value is ErrorEnvelope {
  return (
    isObject(value) &&
    isObject(value.error) &&
    typeof value.error.code === 'string' &&
    typeof value.error.message === 'string'
  );
}

/** Reads a body as JSON; `undefined` when it is empty or not JSON. */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const body = await readJson(response);
  if (isErrorEnvelope(body)) {
    return new ApiError({
      status: response.status,
      code: body.error.code,
      message: body.error.message,
      details: body.error.details,
      partialState: body.error.partialState,
      hasEnvelope: true,
    });
  }
  return new ApiError({
    status: response.status,
    code: CLIENT_UNEXPECTED_RESPONSE,
    message: `The admin service answered HTTP ${response.status} without its error envelope.`,
    hasEnvelope: false,
  });
}

/** One attempt: token, headers, fetch. Network failures become CLIENT_NETWORK_ERROR. */
async function send(method: string, path: string, options: RequestOptions): Promise<Response> {
  const token = getToken();
  if (!token) {
    await login();
    // DESIGN-GAP: the design says "calls login() and throws a 401 ApiError" but names no code
    // for this client-made error. The contract's code for a missing token (§4.2) is used.
    throw new ApiError({
      status: 401,
      code: 'UNAUTHENTICATED',
      message: 'There is no access token; signing in again.',
      hasEnvelope: false,
    });
  }
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
  const hasBody = options.body !== undefined;
  if (hasBody) headers['Content-Type'] = 'application/json';
  try {
    return await fetch(buildUrl(path, options.query), {
      method,
      headers,
      body: hasBody ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch {
    throw new ApiError({
      status: 0,
      code: CLIENT_NETWORK_ERROR,
      message: 'The request did not reach the admin service.',
      hasEnvelope: false,
    });
  }
}

/**
 * Sends the request and returns a 2xx response. A 401 refreshes the token once and repeats the
 * request once; a second 401 is thrown without calling login(), so a session the service keeps
 * rejecting cannot cause a redirect loop. Any other non-2xx becomes an ApiError.
 */
async function execute(method: string, path: string, options: RequestOptions): Promise<Response> {
  let response = await send(method, path, options);
  if (response.status === 401) {
    // refreshToken() calls login() itself when it returns false.
    const refreshed = await refreshToken();
    if (!refreshed) throw await toApiError(response);
    response = await send(method, path, options);
  }
  if (!response.ok) throw await toApiError(response);
  return response;
}

/** A request whose success response has the envelope `{ data, pagination?, warnings? }`. */
export async function apiRequest<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<SuccessEnvelope<T>> {
  const response = await execute(method, path, options);
  const body = await readJson(response);
  if (!isObject(body) || !('data' in body)) {
    throw new ApiError({
      status: response.status,
      code: CLIENT_UNEXPECTED_RESPONSE,
      message: `The admin service answered HTTP ${response.status} without its envelope.`,
      hasEnvelope: false,
    });
  }
  return body as unknown as SuccessEnvelope<T>;
}

/** The one endpoint that answers 204 (`DELETE /roles/:id`): any 2xx is success. */
export async function apiDelete(path: string): Promise<void> {
  const response = await execute('DELETE', path, {});
  // The body is ignored; reading it releases the connection.
  await response.text();
}
