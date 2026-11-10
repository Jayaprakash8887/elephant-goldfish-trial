import { ApiError, CLIENT_NETWORK_ERROR, CLIENT_UNEXPECTED_RESPONSE } from '../../api/ApiError';
import type { FieldError, PartialState } from '../../api/contract';
import { describeError } from '../describeError';

function envelopeError(
  status: number,
  code: string,
  extra: { details?: FieldError[]; partialState?: PartialState } = {},
): ApiError {
  return new ApiError({
    status,
    code,
    message: `Server says ${code}.`,
    hasEnvelope: true,
    ...extra,
  });
}

function bareError(status: number, code = CLIENT_UNEXPECTED_RESPONSE): ApiError {
  return new ApiError({ status, code, message: 'client text', hasEnvelope: false });
}

describe('describeError', () => {
  it('classifies FORBIDDEN as forbidden, with the server message', () => {
    expect(describeError(envelopeError(403, 'FORBIDDEN'))).toEqual({
      kind: 'forbidden',
      code: 'FORBIDDEN',
      status: 403,
      message: 'Server says FORBIDDEN.',
    });
  });

  it('classifies a 403 without the envelope as forbidden, without a server message', () => {
    expect(describeError(bareError(403))).toMatchObject({
      kind: 'forbidden',
      status: 403,
      message: '',
    });
  });

  it('classifies KEYCLOAK_SYNC_FAILED as syncFailed and keeps partialState', () => {
    const partialState: PartialState = {
      operation: 'ROLE_SYNC',
      entityId: 'r1',
      failedStep: 'GATEWAY_SYNC',
      completedSteps: [],
      keycloakUserId: null,
      auditLogId: 'a1',
    };
    expect(describeError(envelopeError(502, 'KEYCLOAK_SYNC_FAILED', { partialState }))).toEqual({
      kind: 'syncFailed',
      code: 'KEYCLOAK_SYNC_FAILED',
      status: 502,
      message: 'Server says KEYCLOAK_SYNC_FAILED.',
      partialState,
    });
  });

  it('classifies VALIDATION_ERROR as validation and keeps details', () => {
    const details = [{ field: 'email', message: 'Invalid.' }];
    expect(describeError(envelopeError(400, 'VALIDATION_ERROR', { details }))).toMatchObject({
      kind: 'validation',
      details,
    });
  });

  it.each([
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
  ])('classifies %s as conflict', (code) => {
    expect(describeError(envelopeError(409, code)).kind).toBe('conflict');
  });

  it.each(['USER_NOT_FOUND', 'ROLE_NOT_FOUND', 'MENU_NOT_FOUND'])(
    'classifies %s as notFound',
    (code) => {
      expect(describeError(envelopeError(404, code)).kind).toBe('notFound');
    },
  );

  it('classifies a VALIDATION_ERROR on the id as notFound only for a detail fetch', () => {
    const error = envelopeError(400, 'VALIDATION_ERROR', {
      details: [{ field: 'id', message: 'Not a UUID.' }],
    });
    expect(describeError(error).kind).toBe('validation');
    expect(describeError(error, { detailFetch: true }).kind).toBe('notFound');
  });

  it('keeps validation for a detail fetch when the details are not only the id', () => {
    const error = envelopeError(400, 'VALIDATION_ERROR', {
      details: [
        { field: 'id', message: 'Not a UUID.' },
        { field: 'pageSize', message: 'Too big.' },
      ],
    });
    expect(describeError(error, { detailFetch: true }).kind).toBe('validation');
  });

  it('classifies a 401 as unauthenticated, with or without the envelope', () => {
    expect(describeError(envelopeError(401, 'UNAUTHENTICATED')).kind).toBe('unauthenticated');
    expect(describeError(bareError(401)).kind).toBe('unauthenticated');
  });

  it('classifies CLIENT_NETWORK_ERROR as network', () => {
    expect(describeError(bareError(0, CLIENT_NETWORK_ERROR))).toMatchObject({
      kind: 'network',
      status: 0,
      code: CLIENT_NETWORK_ERROR,
    });
  });

  it.each([
    ['INTERNAL_ERROR', envelopeError(500, 'INTERNAL_ERROR')],
    ['NOT_FOUND', envelopeError(404, 'NOT_FOUND')],
    ['METHOD_NOT_ALLOWED', envelopeError(405, 'METHOD_NOT_ALLOWED')],
    ['an unknown code', envelopeError(418, 'SOMETHING_NEW')],
    ['a non-envelope 500', bareError(500)],
    ['a non-envelope 2xx', bareError(200)],
  ])('classifies %s as unexpected', (_name, error) => {
    expect(describeError(error).kind).toBe('unexpected');
  });

  it('classifies a value that is not an ApiError as unexpected', () => {
    expect(describeError(new Error('bug'))).toEqual({
      kind: 'unexpected',
      code: CLIENT_UNEXPECTED_RESPONSE,
      status: 0,
      message: '',
    });
    expect(describeError('text').kind).toBe('unexpected');
  });
});
