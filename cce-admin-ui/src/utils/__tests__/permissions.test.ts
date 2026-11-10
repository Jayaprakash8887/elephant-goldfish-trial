import type { PermissionRow } from '../../api/contract';
import { facilityAdminRole, rolePermissions } from '../../test/mocks/fixtures';
import { explicitRows, normalizeRow, sameRuleSet, toRequestRows } from '../permissions';

const facilityRows: PermissionRow[] = rolePermissions[facilityAdminRole.id]!.permissions;

describe('normalizeRow', () => {
  it('upper-cases the method and trims every string', () => {
    expect(
      normalizeRow({
        httpMethod: ' get ',
        uriPattern: '  /admin/v1/users/** ',
        description: ' Read users ',
        resourceCategory: ' Users ',
      }),
    ).toEqual({
      httpMethod: 'GET',
      uriPattern: '/admin/v1/users/**',
      description: 'Read users',
      resourceCategory: 'Users',
    });
  });

  it('treats an empty, blank or null description and category as absent', () => {
    expect(
      normalizeRow({
        httpMethod: 'GET',
        uriPattern: '/a',
        description: '',
        resourceCategory: '  ',
      }),
    ).toEqual({ httpMethod: 'GET', uriPattern: '/a' });
    expect(
      normalizeRow({
        httpMethod: 'GET',
        uriPattern: '/a',
        description: null,
        resourceCategory: null,
      }),
    ).toEqual({ httpMethod: 'GET', uriPattern: '/a' });
    expect(normalizeRow({ httpMethod: 'GET', uriPattern: '/a' })).toEqual({
      httpMethod: 'GET',
      uriPattern: '/a',
    });
  });
});

describe('explicitRows', () => {
  it('leaves out the implicit rows', () => {
    expect(explicitRows(facilityRows).map((row) => row.uriPattern)).toEqual([
      '/admin/v1/users/**',
      '/v1/insights/**',
    ]);
  });
});

describe('sameRuleSet', () => {
  const explicit = explicitRows(facilityRows);

  it('ignores order and method case', () => {
    const reordered = [
      { httpMethod: 'get', uriPattern: '/v1/insights/**', description: '', resourceCategory: '' },
      {
        httpMethod: 'Get',
        uriPattern: ' /admin/v1/users/** ',
        description: 'Read users ',
        resourceCategory: 'Users',
      },
    ];
    expect(sameRuleSet(reordered, explicit)).toBe(true);
    expect(sameRuleSet([], [])).toBe(true);
  });

  it('treats an empty description or category like a null one', () => {
    expect(
      sameRuleSet(
        [{ httpMethod: 'GET', uriPattern: '/a', description: '', resourceCategory: '' }],
        [{ httpMethod: 'GET', uriPattern: '/a', description: null, resourceCategory: null }],
      ),
    ).toBe(true);
  });

  it('sees a changed method, pattern, description or category, and an added or removed row', () => {
    const base = { httpMethod: 'GET', uriPattern: '/a', description: 'd', resourceCategory: 'c' };
    expect(sameRuleSet([{ ...base, httpMethod: 'POST' }], [base])).toBe(false);
    expect(sameRuleSet([{ ...base, uriPattern: '/b' }], [base])).toBe(false);
    expect(sameRuleSet([{ ...base, description: 'D' }], [base])).toBe(false);
    expect(sameRuleSet([{ ...base, resourceCategory: '' }], [base])).toBe(false);
    expect(sameRuleSet([base, { ...base, uriPattern: '/b' }], [base])).toBe(false);
    expect(sameRuleSet([], [base])).toBe(false);
  });
});

describe('toRequestRows', () => {
  it('keeps every row in order and drops empty optional fields', () => {
    expect(
      toRequestRows([
        { httpMethod: 'GET', uriPattern: '/v1/insights/**', description: '', resourceCategory: '' },
        {
          httpMethod: 'post',
          uriPattern: '/admin/v1/users',
          description: 'Create users',
          resourceCategory: '',
        },
      ]),
    ).toEqual([
      { httpMethod: 'GET', uriPattern: '/v1/insights/**' },
      { httpMethod: 'POST', uriPattern: '/admin/v1/users', description: 'Create users' },
    ]);
  });
});
