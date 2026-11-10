// One pass and one fail case for each rule in rules.ts and each console-only rule, added with
// each schema (steps 3 to 6).
import {
  PERMISSION_METHODS as CONTRACT_METHODS,
  USER_STATUSES as CONTRACT_STATUSES,
} from '../../api/contract';
import {
  DUPLICATE_RULE_MESSAGE,
  IMPLICIT_EQUAL_MESSAGE,
  permissionRowSchema,
  permissionsFormSchema,
} from '../permissionSchemas';
import { createRoleSchema, roleDisplaySchema } from '../roleSchemas';
import {
  PAGE_SIZES,
  PERMISSION_CATEGORY_MAX,
  PERMISSION_DESCRIPTION_MAX,
  PERMISSION_METHODS,
  PERMISSION_ROWS_MAX,
  PERMISSION_URI_MAX,
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

const validRule = {
  httpMethod: 'GET',
  uriPattern: '/admin/v1/users/**',
  description: '',
  resourceCategory: '',
};

function ruleErrors(values: Partial<typeof validRule>) {
  const result = permissionRowSchema.safeParse({ ...validRule, ...values });
  return result.success ? {} : result.error.flatten().fieldErrors;
}

const implicitRows = [
  { httpMethod: 'GET', uriPattern: '/admin/v1/me' },
  { httpMethod: 'GET', uriPattern: '/admin/v1/me/menus' },
];

/** The issues of the rules form, as `path: message`. */
function formIssues(permissions: Partial<typeof validRule>[]) {
  const result = permissionsFormSchema(implicitRows).safeParse({
    permissions: permissions.map((row) => ({ ...validRule, ...row })),
  });
  return result.success
    ? []
    : result.error.issues.map((issue) => `${issue.path.join('.')}: ${issue.message}`);
}

describe('permissionRowSchema', () => {
  it('accepts a valid rule, trims every string and upper-cases the method', () => {
    expect(
      permissionRowSchema.parse({
        httpMethod: ' patch ',
        uriPattern: ' /admin/v1/users/*/status ',
        description: ' Change status ',
        resourceCategory: ' Users ',
      }),
    ).toEqual({
      httpMethod: 'PATCH',
      uriPattern: '/admin/v1/users/*/status',
      description: 'Change status',
      resourceCategory: 'Users',
    });
  });

  it.each(PERMISSION_METHODS)('accepts the method %s', (httpMethod) => {
    expect(ruleErrors({ httpMethod })).toEqual({});
  });

  it.each(['HEAD', 'OPTIONS', '*', ''])('rejects the method "%s"', (httpMethod) => {
    expect(ruleErrors({ httpMethod }).httpMethod).toBeDefined();
  });

  it.each([
    '/',
    '/v1/insights/**',
    '/admin/v1/users/*',
    '/admin/v1/users/*/roles',
    '/a-b.c_d~e/F9',
    '/admin/v1/users/',
  ])('accepts the URI pattern %s', (uriPattern) => {
    expect(ruleErrors({ uriPattern })).toEqual({});
  });

  it.each([
    ['empty', '', 'Enter a URI pattern.'],
    ['blank', '   ', 'Enter a URI pattern.'],
    ['not starting with /', 'admin/v1/users', 'Start with /.'],
    ['a space', '/admin/v1/a b', 'Use only letters, digits and - . _ ~ / *.'],
    ['a query', '/admin/v1/users?q=1', 'Use only letters, digits and - . _ ~ / *.'],
    ['a path variable', '/admin/v1/users/{id}', 'Use only letters, digits and - . _ ~ / *.'],
    ['an empty segment', '/admin//users', 'No empty segment (//).'],
    ['** before the last segment', '/admin/**/users', '** may only be the whole last segment.'],
    ['** inside a segment', '/admin/v1/users**', '** may only be the whole last segment.'],
    ['***', '/admin/***', '** may only be the whole last segment.'],
  ])('rejects a URI pattern that is %s', (_case, uriPattern, message) => {
    expect(ruleErrors({ uriPattern }).uriPattern).toContain(message);
  });

  it(`accepts ${PERMISSION_URI_MAX} characters and rejects ${PERMISSION_URI_MAX + 1}`, () => {
    const pattern = (length: number) => `/${'a'.repeat(length - 1)}`;
    expect(ruleErrors({ uriPattern: pattern(PERMISSION_URI_MAX) })).toEqual({});
    expect(ruleErrors({ uriPattern: pattern(PERMISSION_URI_MAX + 1) }).uriPattern).toContain(
      `At most ${PERMISSION_URI_MAX} characters.`,
    );
  });

  it(`allows an empty description of at most ${PERMISSION_DESCRIPTION_MAX} characters`, () => {
    expect(ruleErrors({ description: '' })).toEqual({});
    expect(ruleErrors({ description: 'x'.repeat(PERMISSION_DESCRIPTION_MAX) })).toEqual({});
    expect(
      ruleErrors({ description: 'x'.repeat(PERMISSION_DESCRIPTION_MAX + 1) }).description,
    ).toBeDefined();
  });

  it(`allows an empty category of at most ${PERMISSION_CATEGORY_MAX} characters`, () => {
    expect(ruleErrors({ resourceCategory: '' })).toEqual({});
    expect(ruleErrors({ resourceCategory: 'x'.repeat(PERMISSION_CATEGORY_MAX) })).toEqual({});
    expect(
      ruleErrors({ resourceCategory: 'x'.repeat(PERMISSION_CATEGORY_MAX + 1) }).resourceCategory,
    ).toBeDefined();
  });
});

describe('permissionsFormSchema (console-only rules)', () => {
  it('accepts no rules and distinct rules', () => {
    expect(formIssues([])).toEqual([]);
    expect(
      formIssues([
        { uriPattern: '/admin/v1/users/**' },
        { httpMethod: 'POST', uriPattern: '/admin/v1/users/**' },
        { uriPattern: '/admin/v1/roles/**' },
      ]),
    ).toEqual([]);
  });

  it('reports a duplicate method and pattern on the later row', () => {
    expect(
      formIssues([
        { uriPattern: '/admin/v1/users/**', description: 'first' },
        { uriPattern: '/admin/v1/roles' },
        { httpMethod: 'get', uriPattern: ' /admin/v1/users/** ', description: 'second' },
      ]),
    ).toEqual([`permissions.2.uriPattern: ${DUPLICATE_RULE_MESSAGE}`]);
  });

  it('reports a row equal to an implicit row', () => {
    expect(
      formIssues([{ uriPattern: '/admin/v1/users/**' }, { uriPattern: '/admin/v1/me/menus' }]),
    ).toEqual([`permissions.1.uriPattern: ${IMPLICIT_EQUAL_MESSAGE}`]);
    // Another method on the same pattern is not an implicit row.
    expect(formIssues([{ httpMethod: 'POST', uriPattern: '/admin/v1/me' }])).toEqual([]);
  });

  it('reports nothing implicit for a role without implicit rows', () => {
    const result = permissionsFormSchema([]).safeParse({
      permissions: [{ ...validRule, uriPattern: '/admin/v1/me' }],
    });
    expect(result.success).toBe(true);
  });

  it(`accepts ${PERMISSION_ROWS_MAX} rules and rejects ${PERMISSION_ROWS_MAX + 1}`, () => {
    const rows = (count: number) =>
      Array.from({ length: count }, (_, i) => ({ uriPattern: `/v1/r${i}` }));
    expect(formIssues(rows(PERMISSION_ROWS_MAX))).toEqual([]);
    expect(formIssues(rows(PERMISSION_ROWS_MAX + 1))).toEqual([
      `permissions: At most ${PERMISSION_ROWS_MAX} rules.`,
    ]);
  });
});
