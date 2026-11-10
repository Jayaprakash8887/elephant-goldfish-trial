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
import {
  menuFormSchema,
  PARENT_NOT_ALLOWED_MESSAGE,
  PARENT_REQUIRED_MESSAGE,
} from '../menuSchemas';
import { createRoleSchema, roleDisplaySchema } from '../roleSchemas';
import { createUserSchema, profileSchema, userRolesSchema } from '../userSchemas';
import {
  EMAIL_MAX,
  FACILITY_SCOPE_MAX,
  MENU_KEY_MAX,
  MENU_LABEL_MAX,
  MENU_PATH_MAX,
  MENU_SORT_ORDER_MAX,
  PAGE_SIZES,
  PERSON_NAME_MAX,
  PERMISSION_CATEGORY_MAX,
  PERMISSION_DESCRIPTION_MAX,
  PERMISSION_METHODS,
  PERMISSION_ROWS_MAX,
  PERMISSION_URI_MAX,
  ROLE_DESCRIPTION_MAX,
  ROLE_DISPLAY_NAME_MAX,
  ROLE_NAME_MAX,
  ROLE_NAME_MIN,
  USER_ROLES_MAX,
  USER_SEARCH_MAX,
  USER_STATUSES,
  USERNAME_MAX,
  USERNAME_MIN,
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

const ROLE_A = '20000000-0000-4000-8000-000000000003';

const validUser = {
  username: 'jane.doe',
  email: 'jane.doe@cce.local',
  firstName: 'Jane',
  lastName: 'Doe',
  facilityScope: '',
  roleIds: [ROLE_A],
};

function userErrors(values: Partial<typeof validUser>) {
  const result = createUserSchema.safeParse({ ...validUser, ...values });
  return result.success ? {} : result.error.flatten().fieldErrors;
}

const ids = (count: number) =>
  Array.from({ length: count }, (_, i) => `20000000-0000-4000-8000-${String(i).padStart(12, '0')}`);

describe('createUserSchema', () => {
  it('accepts a valid user and trims every string', () => {
    expect(
      createUserSchema.parse({
        username: ' jane.doe ',
        email: ' jane.doe@cce.local ',
        firstName: ' Jane ',
        lastName: ' Doe ',
        facilityScope: ' FAC-01 ',
        roleIds: [ROLE_A],
      }),
    ).toEqual({
      username: 'jane.doe',
      email: 'jane.doe@cce.local',
      firstName: 'Jane',
      lastName: 'Doe',
      facilityScope: 'FAC-01',
      roleIds: [ROLE_A],
    });
  });

  it.each(['abc', 'jane.doe', 'j_doe-2', '0user', 'e2e-cuj2a-1731234567890123'])(
    'accepts the username %s',
    (username) => {
      expect(userErrors({ username })).toEqual({});
    },
  );

  it.each([
    ['upper case', 'Jane.Doe'],
    ['a leading dot', '.jane'],
    ['a leading hyphen', '-jane'],
    ['a space', 'jane doe'],
    ['an @', 'jane@doe'],
  ])('rejects a username with %s', (_case, username) => {
    expect(userErrors({ username }).username).toBeDefined();
  });

  it(`accepts ${USERNAME_MIN} to ${USERNAME_MAX} characters only`, () => {
    expect(userErrors({ username: 'ab' }).username).toBeDefined();
    expect(userErrors({ username: 'a'.repeat(USERNAME_MIN) })).toEqual({});
    expect(userErrors({ username: 'a'.repeat(USERNAME_MAX) })).toEqual({});
    expect(userErrors({ username: 'a'.repeat(USERNAME_MAX + 1) }).username).toBeDefined();
  });

  it('requires a valid email of at most 254 characters', () => {
    expect(userErrors({ email: '' }).email).toContain('Enter an email address.');
    expect(userErrors({ email: 'jane.doe' }).email).toContain('Enter a valid email address.');
    // 64 + 1 + 189 = 254 characters, with domain labels of at most 63.
    const domain = ['b'.repeat(60), 'c'.repeat(60), 'd'.repeat(60), 'ee', 'com'].join('.');
    const at254 = `${'a'.repeat(64)}@${domain}`;
    expect(at254).toHaveLength(EMAIL_MAX);
    expect(userErrors({ email: at254 })).toEqual({});
    expect(userErrors({ email: `a${at254}` }).email).toContain(`At most ${EMAIL_MAX} characters.`);
  });

  it('requires a first and last name of at most 100 characters', () => {
    expect(userErrors({ firstName: ' ' }).firstName).toContain('Enter a first name.');
    expect(userErrors({ lastName: '' }).lastName).toContain('Enter a last name.');
    expect(userErrors({ firstName: 'x'.repeat(PERSON_NAME_MAX) })).toEqual({});
    expect(userErrors({ lastName: 'x'.repeat(PERSON_NAME_MAX + 1) }).lastName).toBeDefined();
  });

  it(`allows an empty facility scope of at most ${FACILITY_SCOPE_MAX} characters`, () => {
    expect(userErrors({ facilityScope: '' })).toEqual({});
    expect(userErrors({ facilityScope: 'x'.repeat(FACILITY_SCOPE_MAX) })).toEqual({});
    expect(
      userErrors({ facilityScope: 'x'.repeat(FACILITY_SCOPE_MAX + 1) }).facilityScope,
    ).toBeDefined();
  });

  it(`requires 1 to ${USER_ROLES_MAX} roles on create (console-only rule)`, () => {
    expect(userErrors({ roleIds: [] }).roleIds).toContain('Choose at least one role.');
    expect(userErrors({ roleIds: ids(USER_ROLES_MAX) })).toEqual({});
    expect(userErrors({ roleIds: ids(USER_ROLES_MAX + 1) }).roleIds).toContain(
      `At most ${USER_ROLES_MAX} roles.`,
    );
  });
});

describe('profileSchema', () => {
  it('accepts a profile with an empty facility scope', () => {
    expect(
      profileSchema.parse({ email: ' a@b.de ', firstName: 'A', lastName: 'B', facilityScope: '' }),
    ).toEqual({ email: 'a@b.de', firstName: 'A', lastName: 'B', facilityScope: '' });
  });

  it('rejects an invalid email, empty names and a long facility scope', () => {
    const result = profileSchema.safeParse({
      email: 'not-an-address',
      firstName: '',
      lastName: '',
      facilityScope: 'x'.repeat(FACILITY_SCOPE_MAX + 1),
    });
    expect(Object.keys(result.error?.flatten().fieldErrors ?? {}).sort()).toEqual([
      'email',
      'facilityScope',
      'firstName',
      'lastName',
    ]);
  });
});

describe('userRolesSchema (console-only rule)', () => {
  it(`allows zero to ${USER_ROLES_MAX} roles on edit`, () => {
    expect(userRolesSchema.safeParse({ roleIds: [] }).success).toBe(true);
    expect(userRolesSchema.safeParse({ roleIds: ids(USER_ROLES_MAX) }).success).toBe(true);
    expect(userRolesSchema.safeParse({ roleIds: ids(USER_ROLES_MAX + 1) }).success).toBe(false);
  });
});

describe('user search length', () => {
  it(`is at most ${USER_SEARCH_MAX} characters`, () => {
    expect(USER_SEARCH_MAX).toBe(100);
  });
});

const NAV_ID = '10000000-0000-4000-8000-000000000005';
const OTHER_ID = '10000000-0000-4000-8000-000000000012';
const validNav = {
  key: 'reports',
  label: 'Reports',
  path: '/reports',
  icon: '',
  type: 'NAV' as const,
  parentId: '',
  sortOrder: 110,
  isActive: true,
};
const menuSchema = menuFormSchema([NAV_ID]);

function menuErrors(values: Record<string, unknown>) {
  const result = menuSchema.safeParse({ ...validNav, ...values });
  return result.success ? {} : result.error.flatten().fieldErrors;
}

describe('menuFormSchema', () => {
  it('accepts a NAV entry and trims every string', () => {
    expect(
      menuSchema.parse({
        ...validNav,
        key: ' reports ',
        label: ' Reports ',
        path: ' /reports ',
        icon: ' ChartBarIcon ',
      }),
    ).toEqual({ ...validNav, icon: 'ChartBarIcon' });
  });

  it.each(['dashboard', 'patients.patient-detail', 'a1-b.c2'])('accepts the key %s', (key) => {
    expect(menuErrors({ key })).toEqual({});
  });

  it.each([
    ['empty', ''],
    ['upper case', 'Reports'],
    ['a leading digit', '1reports'],
    ['an empty word', 'patients..detail'],
    ['a word starting with a hyphen', 'patients.-detail'],
    ['a space', 'my reports'],
  ])('rejects a key that is %s', (_case, key) => {
    expect(menuErrors({ key }).key).toBeDefined();
  });

  it(`accepts a key of at most ${MENU_KEY_MAX} characters`, () => {
    expect(menuErrors({ key: 'a'.repeat(MENU_KEY_MAX) })).toEqual({});
    expect(menuErrors({ key: 'a'.repeat(MENU_KEY_MAX + 1) }).key).toContain(
      `At most ${MENU_KEY_MAX} characters.`,
    );
  });

  it(`requires a label of 1 to ${MENU_LABEL_MAX} characters`, () => {
    expect(menuErrors({ label: ' ' }).label).toContain('Enter a label.');
    expect(menuErrors({ label: 'x'.repeat(MENU_LABEL_MAX) })).toEqual({});
    expect(menuErrors({ label: 'x'.repeat(MENU_LABEL_MAX + 1) }).label).toBeDefined();
  });

  it.each(['/', '/compliance/patients/:id', '/a_b-c.d'])('accepts the path %s', (path) => {
    expect(menuErrors({ path })).toEqual({});
  });

  it('rejects an empty path, one without a leading /, and other characters', () => {
    expect(menuErrors({ path: '' }).path).toContain('Enter a path.');
    expect(menuErrors({ path: 'reports' }).path).toContain('Start with /.');
    expect(menuErrors({ path: '/reports?x=1' }).path).toContain(
      'Use only letters, digits and / _ - . :.',
    );
  });

  it(`accepts a path of at most ${MENU_PATH_MAX} characters`, () => {
    expect(menuErrors({ path: `/${'a'.repeat(MENU_PATH_MAX - 1)}` })).toEqual({});
    expect(menuErrors({ path: `/${'a'.repeat(MENU_PATH_MAX)}` }).path).toBeDefined();
  });

  it('accepts no icon or an icon component name, and rejects other names', () => {
    expect(menuErrors({ icon: '' })).toEqual({});
    expect(menuErrors({ icon: 'MysteryIcon' })).toEqual({});
    expect(menuErrors({ icon: 'chartBarIcon' }).icon).toBeDefined();
    expect(menuErrors({ icon: 'ChartBar' }).icon).toBeDefined();
  });

  it(`requires a whole sort order from 0 to ${MENU_SORT_ORDER_MAX}`, () => {
    expect(menuErrors({ sortOrder: 0 })).toEqual({});
    expect(menuErrors({ sortOrder: MENU_SORT_ORDER_MAX })).toEqual({});
    const message = `A whole number from 0 to ${MENU_SORT_ORDER_MAX}.`;
    expect(menuErrors({ sortOrder: -1 }).sortOrder).toContain(message);
    expect(menuErrors({ sortOrder: MENU_SORT_ORDER_MAX + 1 }).sortOrder).toContain(message);
    expect(menuErrors({ sortOrder: 1.5 }).sortOrder).toContain(message);
    // An empty number input gives NaN with valueAsNumber.
    expect(menuErrors({ sortOrder: Number.NaN }).sortOrder).toContain(message);
  });

  it('accepts only the two menu types', () => {
    expect(menuErrors({ type: 'ROUTE' }).type).toBeDefined();
  });
});

describe('menuFormSchema parent rule (console-only rule)', () => {
  it('requires one of the NAV entries as the parent of a DETAIL entry', () => {
    expect(menuErrors({ type: 'DETAIL', parentId: NAV_ID })).toEqual({});
    expect(menuErrors({ type: 'DETAIL', parentId: '' }).parentId).toEqual([
      PARENT_REQUIRED_MESSAGE,
    ]);
    expect(menuErrors({ type: 'DETAIL', parentId: OTHER_ID }).parentId).toEqual([
      PARENT_REQUIRED_MESSAGE,
    ]);
  });

  it('allows no parent for a NAV entry', () => {
    expect(menuErrors({ type: 'NAV', parentId: '' })).toEqual({});
    expect(menuErrors({ type: 'NAV', parentId: NAV_ID }).parentId).toEqual([
      PARENT_NOT_ALLOWED_MESSAGE,
    ]);
  });
});
