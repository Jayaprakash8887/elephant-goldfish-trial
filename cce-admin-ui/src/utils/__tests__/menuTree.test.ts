import type { Menu } from '../../api/contract';
import { menuIds, menuTree } from '../../test/mocks/fixtures';
import { detailsWithoutParent, flattenMenus, menuById, navMenus, nextSortOrder } from '../menuTree';

const withSort = (sortOrder: number): Menu => ({ ...menuTree[0]!, sortOrder });

describe('flattenMenus', () => {
  it('lists each NAV followed by its DETAIL children', () => {
    const flat = flattenMenus(menuTree);
    expect(flat).toHaveLength(12);
    expect(flat.slice(2, 6).map((menu) => menu.label)).toEqual([
      'Compliance',
      'Protocol analytics',
      'Deviations',
      'Patients',
    ]);
  });

  it('indexes every menu by id', () => {
    const byId = menuById(menuTree);
    expect(byId.size).toBe(12);
    expect(byId.get(menuIds.patientDetail)?.label).toBe('Patient detail');
  });

  it('returns the NAV menus', () => {
    expect(navMenus(menuTree)).toHaveLength(10);
    expect(navMenus(menuTree).every((menu) => menu.type === 'NAV')).toBe(true);
  });
});

describe('nextSortOrder', () => {
  it('is 10 without siblings', () => {
    expect(nextSortOrder([])).toBe(10);
  });

  it('is the highest sibling sort order plus 10', () => {
    expect(nextSortOrder([withSort(30), withSort(70), withSort(20)])).toBe(80);
  });

  it('is at most 10000', () => {
    expect(nextSortOrder([withSort(9995)])).toBe(10000);
  });
});

describe('detailsWithoutParent', () => {
  it('returns a selected DETAIL whose parent is not selected', () => {
    const pairs = detailsWithoutParent(
      menuTree,
      new Set([menuIds.patientDetail, menuIds.dashboard]),
    );
    expect(pairs.map(({ menu, parent }) => [menu.label, parent.label])).toEqual([
      ['Patient detail', 'Patients'],
    ]);
  });

  it('returns nothing when the parent is selected too', () => {
    expect(
      detailsWithoutParent(menuTree, new Set([menuIds.patientDetail, menuIds.patients])),
    ).toEqual([]);
  });

  it('returns nothing when no DETAIL is selected', () => {
    expect(detailsWithoutParent(menuTree, new Set([menuIds.compliance]))).toEqual([]);
  });
});
