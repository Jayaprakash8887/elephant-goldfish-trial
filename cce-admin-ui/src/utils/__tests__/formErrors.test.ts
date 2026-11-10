import type { Role } from '../../api/contract';
import { facilityAdminRole, superAdminRole } from '../../test/mocks/fixtures';
import type { DescribedError } from '../describeError';
import { applyServerErrors, roleErrorMessages, toFormPath } from '../formErrors';

function described(
  partial: Partial<DescribedError> & Pick<DescribedError, 'kind'>,
): DescribedError {
  return { code: 'X', status: 400, message: 'Server message.', ...partial };
}

describe('toFormPath', () => {
  it('converts the JSON path form to the React Hook Form form', () => {
    expect(toFormPath('permissions[2].uriPattern')).toBe('permissions.2.uriPattern');
    expect(toFormPath('roleIds[10]')).toBe('roleIds.10');
    expect(toFormPath('email')).toBe('email');
  });
});

describe('applyServerErrors', () => {
  it('sets exact and prefix field matches and returns the other messages', () => {
    const setError = vi.fn();
    const error = described({
      kind: 'validation',
      code: 'VALIDATION_ERROR',
      details: [
        { field: 'email', message: 'Invalid email.' },
        { field: 'permissions[2].uriPattern', message: 'Invalid pattern.' },
        { field: 'body', message: 'Malformed JSON.' },
        { field: 'emailAddress', message: 'Not a prefix match.' },
      ],
    });
    const rest = applyServerErrors(setError, error, ['email', 'permissions'], {});
    expect(setError).toHaveBeenCalledTimes(2);
    expect(setError).toHaveBeenCalledWith('email', { type: 'server', message: 'Invalid email.' });
    expect(setError).toHaveBeenCalledWith('permissions.2.uriPattern', {
      type: 'server',
      message: 'Invalid pattern.',
    });
    expect(rest).toEqual(['Malformed JSON.', 'Not a prefix match.']);
  });

  it('returns the message of a validation error without details', () => {
    const setError = vi.fn();
    const rest = applyServerErrors(setError, described({ kind: 'validation' }), ['email'], {});
    expect(rest).toEqual(['Server message.']);
    expect(setError).not.toHaveBeenCalled();
  });

  it('maps a conflict code to its field with the server message', () => {
    const setError = vi.fn();
    const error = described({ kind: 'conflict', code: 'EMAIL_IN_USE', status: 409 });
    const rest = applyServerErrors(setError, error, ['email'], {
      EMAIL_IN_USE: 'email',
      USERNAME_IN_USE: 'username',
    });
    expect(rest).toEqual([]);
    expect(setError).toHaveBeenCalledWith('email', { type: 'server', message: 'Server message.' });
  });

  it('returns the message of a conflict code that has no field', () => {
    const setError = vi.fn();
    const error = described({ kind: 'conflict', code: 'LAST_SYSTEM_ADMIN', status: 409 });
    expect(applyServerErrors(setError, error, ['email'], { EMAIL_IN_USE: 'email' })).toEqual([
      'Server message.',
    ]);
    expect(setError).not.toHaveBeenCalled();
  });

  it('returns nothing for kinds shown elsewhere', () => {
    const setError = vi.fn();
    const error = described({ kind: 'forbidden', code: 'FORBIDDEN', status: 403 });
    expect(applyServerErrors(setError, error, ['email'], {})).toEqual([]);
    expect(setError).not.toHaveBeenCalled();
  });
});

describe('roleErrorMessages', () => {
  const roles: Role[] = [superAdminRole, facilityAdminRole];
  const sent = [superAdminRole.id, facilityAdminRole.id, 'unknown-id'];

  it('names each role of ROLE_NOT_SYNCED by its index in the ids sent', () => {
    const error = described({
      kind: 'conflict',
      code: 'ROLE_NOT_SYNCED',
      details: [{ field: 'roleIds[1]', message: 'Not synced.' }],
    });
    expect(roleErrorMessages(error, sent, roles)).toEqual([
      'Not synced yet: Facility administrator (FACILITY_ADMIN). Open the role and use Re-sync.',
    ]);
  });

  it('names each role of ROLE_NOT_ASSIGNABLE, with the "Role {id}" fallback', () => {
    const error = described({
      kind: 'conflict',
      code: 'ROLE_NOT_ASSIGNABLE',
      details: [
        { field: 'roleIds[0]', message: 'Not assignable.' },
        { field: 'roleIds[2]', message: 'Not assignable.' },
      ],
    });
    expect(roleErrorMessages(error, sent, roles)).toEqual([
      'Cannot be assigned: Super administrator (SUPER_ADMIN).',
      'Cannot be assigned: Role unknown-id.',
    ]);
  });

  it('shows the detail message when the field names no id that was sent', () => {
    const error = described({
      kind: 'conflict',
      code: 'ROLE_NOT_SYNCED',
      details: [
        { field: 'roleIds[9]', message: 'Index out of range.' },
        { field: 'other', message: 'Other field.' },
      ],
    });
    expect(roleErrorMessages(error, sent, roles)).toEqual(['Index out of range.', 'Other field.']);
  });

  it('returns nothing for other codes or without details', () => {
    expect(
      roleErrorMessages(described({ kind: 'conflict', code: 'EMAIL_IN_USE' }), sent, roles),
    ).toEqual([]);
    expect(
      roleErrorMessages(described({ kind: 'conflict', code: 'ROLE_NOT_SYNCED' }), sent, roles),
    ).toEqual([]);
  });
});
