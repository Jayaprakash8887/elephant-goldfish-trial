// One pass and one fail case for each rule in rules.ts and each console-only rule, added with
// each schema (steps 3 to 6).
import {
  PERMISSION_METHODS as CONTRACT_METHODS,
  USER_STATUSES as CONTRACT_STATUSES,
} from '../../api/contract';
import { createRoleSchema, roleDisplaySchema } from '../roleSchemas';
import {
  PAGE_SIZES,
  PERMISSION_METHODS,
  ROLE_DESCRIPTION_MAX,
  ROLE_DISPLAY_NAME_MAX,
  ROLE_NAME_MAX,
  ROLE_NAME_MIN,
  USER_STATUSES,
} from '../rules';

const validRole = {
  name: 'FACILITY_ADMIN',
  displayName: 'Facility administrator',
  description: '',
};

function roleErrors(values: Partial<typeof validRole>) {
  const result = createRoleSchema.safeParse({ ...validRole, ...values });
  return result.success ? {} : result.error.flatten().fieldErrors;
}

describe('rules.ts re-exports', () => {
  it('re-exports the contract constants for components', () => {
    expect(PERMISSION_METHODS).toBe(CONTRACT_METHODS);
    expect(USER_STATUSES).toBe(CONTRACT_STATUSES);
  });
});

describe('page sizes (console-only rule)', () => {
  it('allows 20, 50 and 100 only', () => {
    expect(PAGE_SIZES).toEqual([20, 50, 100]);
    expect((PAGE_SIZES as readonly number[]).includes(25)).toBe(false);
  });
});

describe('createRoleSchema', () => {
  it('accepts a valid role and trims every string', () => {
    const result = createRoleSchema.parse({
      name: '  FACILITY_ADMIN ',
      displayName: ' Facility administrator ',
      description: '  Manages users. ',
    });
    expect(result).toEqual({
      name: 'FACILITY_ADMIN',
      displayName: 'Facility administrator',
      description: 'Manages users.',
    });
  });

  it.each(['AB', 'A1', 'ROLE_2', 'A_B_C', 'E2E_CUJ3_1234'])('accepts the name %s', (name) => {
    expect(roleErrors({ name })).toEqual({});
  });

  it.each([
    ['lower case', 'facility_admin'],
    ['a leading digit', '1ROLE'],
    ['a leading underscore', '_ROLE'],
    ['a trailing underscore', 'ROLE_'],
    ['a double underscore', 'ROLE__ADMIN'],
    ['a space', 'ROLE ADMIN'],
    ['a hyphen', 'ROLE-ADMIN'],
  ])('rejects a name with %s', (_case, name) => {
    expect(roleErrors({ name }).name).toBeDefined();
  });

  it(`rejects a name shorter than ${ROLE_NAME_MIN} characters`, () => {
    expect(roleErrors({ name: 'A' }).name).toBeDefined();
    expect(roleErrors({ name: ' A ' }).name).toBeDefined();
  });

  it(`accepts ${ROLE_NAME_MAX} characters and rejects ${ROLE_NAME_MAX + 1}`, () => {
    expect(roleErrors({ name: 'A'.repeat(ROLE_NAME_MAX) })).toEqual({});
    expect(roleErrors({ name: 'A'.repeat(ROLE_NAME_MAX + 1) }).name).toBeDefined();
  });

  it('requires a display name of at most 100 characters', () => {
    expect(roleErrors({ displayName: '   ' }).displayName).toBeDefined();
    expect(roleErrors({ displayName: 'x'.repeat(ROLE_DISPLAY_NAME_MAX) })).toEqual({});
    expect(
      roleErrors({ displayName: 'x'.repeat(ROLE_DISPLAY_NAME_MAX + 1) }).displayName,
    ).toBeDefined();
  });

  it('allows an empty description of at most 500 characters', () => {
    expect(roleErrors({ description: '' })).toEqual({});
    expect(roleErrors({ description: 'x'.repeat(ROLE_DESCRIPTION_MAX) })).toEqual({});
    expect(
      roleErrors({ description: 'x'.repeat(ROLE_DESCRIPTION_MAX + 1) }).description,
    ).toBeDefined();
  });
});

describe('roleDisplaySchema', () => {
  it('accepts a display name and an empty description', () => {
    expect(roleDisplaySchema.safeParse({ displayName: ' Name ', description: '' })).toEqual({
      success: true,
      data: { displayName: 'Name', description: '' },
    });
  });

  it('rejects an empty display name and a long description', () => {
    const result = roleDisplaySchema.safeParse({
      displayName: '',
      description: 'x'.repeat(ROLE_DESCRIPTION_MAX + 1),
    });
    expect(result.success).toBe(false);
    expect(Object.keys(result.error?.flatten().fieldErrors ?? {}).sort()).toEqual([
      'description',
      'displayName',
    ]);
  });
});
