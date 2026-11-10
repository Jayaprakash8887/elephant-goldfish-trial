// Typed canned data with fixed UUIDs (UI-AD-19). Menus and system roles follow the
// admin-service seed (admin-service §6); the rest is test data: the non-system role
// FACILITY_ADMIN, the test administrator and enough users for two pages of 20.
import type {
  Me,
  Menu,
  PermissionRow,
  Role,
  RoleMenus,
  RolePermissions,
  User,
  UserStatus,
} from '../../api/contract';

export const CREATED_AT = '2026-11-01T08:00:00.000Z';
export const UPDATED_AT = '2026-11-10T09:15:00.123Z';
export const LAST_SYNC_AT = '2026-11-10T09:20:00Z';

const menuId = (n: number) => `10000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;

// ---------------------------------------------------------------------------------------------
// Menus: the twelve seed entries of admin-service §6, every one active.
// ---------------------------------------------------------------------------------------------

function nav(
  n: number,
  key: string,
  label: string,
  path: string,
  icon: string,
  sortOrder: number,
): Menu {
  return {
    id: menuId(n),
    key,
    label,
    path,
    icon,
    type: 'NAV',
    parentId: null,
    sortOrder,
    isActive: true,
    children: [],
  };
}

function detail(n: number, parent: number, key: string, label: string, path: string): Menu {
  return {
    id: menuId(n),
    key,
    label,
    path,
    icon: null,
    type: 'DETAIL',
    parentId: menuId(parent),
    sortOrder: 10,
    isActive: true,
    children: [],
  };
}

const protocolDetail = detail(
  11,
  3,
  'compliance.protocol-detail',
  'Protocol analytics',
  '/compliance/protocols/:id',
);
const patientDetail = detail(
  12,
  5,
  'patients.patient-detail',
  'Patient detail',
  '/compliance/patients/:id',
);

export const menuTree: Menu[] = [
  nav(1, 'dashboard', 'Dashboard', '/', 'ChartBarIcon', 10),
  nav(2, 'facilities', 'Facilities', '/facilities', 'BuildingOffice2Icon', 20),
  {
    ...nav(3, 'compliance', 'Compliance', '/compliance', 'ClipboardDocumentCheckIcon', 30),
    children: [protocolDetail],
  },
  nav(4, 'deviations', 'Deviations', '/deviations', 'ExclamationTriangleIcon', 40),
  {
    ...nav(5, 'patients', 'Patients', '/compliance/patients', 'UserGroupIcon', 50),
    children: [patientDetail],
  },
  nav(6, 'events', 'Events', '/events', 'SignalIcon', 60),
  nav(7, 'ingestion', 'Ingestion', '/ingestion', 'CogIcon', 70),
  nav(8, 'practitioners', 'Practitioners', '/practitioners', 'UserIcon', 80),
  nav(9, 'intelligence', 'Intelligence', '/intelligence', 'LightBulbIcon', 90),
  nav(10, 'exports', 'Exports', '/exports', 'ArrowDownTrayIcon', 100),
];

export const menuIds = {
  dashboard: menuId(1),
  facilities: menuId(2),
  compliance: menuId(3),
  patients: menuId(5),
  protocolDetail: menuId(11),
  patientDetail: menuId(12),
};

/** The body a default `POST /menus` returns. */
export const createdMenu: Menu = {
  id: '10000000-0000-4000-8000-000000000099',
  key: 'reports',
  label: 'Reports',
  path: '/reports',
  icon: null,
  type: 'NAV',
  parentId: null,
  sortOrder: 110,
  isActive: true,
  children: [],
};

// ---------------------------------------------------------------------------------------------
// Roles: the two seed system roles and the test role FACILITY_ADMIN.
// ---------------------------------------------------------------------------------------------

export const superAdminRole: Role = {
  id: '20000000-0000-4000-8000-000000000001',
  name: 'SUPER_ADMIN',
  displayName: 'Super administrator',
  description: 'Platform administrator with full access to the admin API.',
  isSystem: true,
  isAssignable: true,
  syncStatus: 'NEVER',
  lastSyncAt: null,
  userCount: 1,
  createdAt: CREATED_AT,
  updatedAt: CREATED_AT,
};

export const adminServiceRole: Role = {
  id: '20000000-0000-4000-8000-000000000002',
  name: 'CCE_ADMIN_SERVICE',
  displayName: 'Admin service account',
  description: 'Service account of cce-admin-service. Calls the gateway sync.',
  isSystem: true,
  isAssignable: false,
  syncStatus: 'NEVER',
  lastSyncAt: null,
  userCount: 0,
  createdAt: CREATED_AT,
  updatedAt: CREATED_AT,
};

export const facilityAdminRole: Role = {
  id: '20000000-0000-4000-8000-000000000003',
  name: 'FACILITY_ADMIN',
  displayName: 'Facility administrator',
  description: 'Manages users of one facility.',
  isSystem: false,
  isAssignable: true,
  syncStatus: 'SYNCED',
  lastSyncAt: LAST_SYNC_AT,
  userCount: 24,
  createdAt: CREATED_AT,
  updatedAt: UPDATED_AT,
};

/** Ordered by name, as `GET /roles` returns them. */
export const roles: Role[] = [adminServiceRole, facilityAdminRole, superAdminRole];

/** The body a default `POST /roles` returns. */
export const createdRole: Role = {
  id: '20000000-0000-4000-8000-000000000099',
  name: 'NEW_ROLE',
  displayName: 'New role',
  description: null,
  isSystem: false,
  isAssignable: true,
  syncStatus: 'SYNCED',
  lastSyncAt: LAST_SYNC_AT,
  userCount: 0,
  createdAt: UPDATED_AT,
  updatedAt: UPDATED_AT,
};

export const roleMenus: Record<string, RoleMenus> = {
  [superAdminRole.id]: { roleId: superAdminRole.id, menuIds: [] },
  [adminServiceRole.id]: { roleId: adminServiceRole.id, menuIds: [] },
  [facilityAdminRole.id]: {
    roleId: facilityAdminRole.id,
    menuIds: [menuIds.facilities, menuIds.compliance],
  },
};

function row(
  id: number,
  httpMethod: string,
  uriPattern: string,
  description: string | null,
  resourceCategory: string | null,
  implicit = false,
): PermissionRow {
  return { id, httpMethod, uriPattern, description, resourceCategory, implicit };
}

const ADMIN = 'Administer the CCE admin module';

/** Implicit rows first, then explicit rows ordered by pattern and method (admin-service §4.5). */
export const rolePermissions: Record<string, RolePermissions> = {
  [superAdminRole.id]: {
    roleId: superAdminRole.id,
    syncStatus: 'NEVER',
    lastSyncAt: null,
    permissions: [
      row(1, 'DELETE', '/admin/v1/**', ADMIN, 'Admin'),
      row(2, 'GET', '/admin/v1/**', ADMIN, 'Admin'),
      row(3, 'PATCH', '/admin/v1/**', ADMIN, 'Admin'),
      row(4, 'POST', '/admin/v1/**', ADMIN, 'Admin'),
      row(5, 'PUT', '/admin/v1/**', ADMIN, 'Admin'),
    ],
  },
  [adminServiceRole.id]: {
    roleId: adminServiceRole.id,
    syncStatus: 'NEVER',
    lastSyncAt: null,
    permissions: [
      row(
        6,
        'POST',
        '/api/permissions/sync-to-keycloak',
        "Trigger the gateway's realm role sync",
        'Gateway',
      ),
    ],
  },
  [facilityAdminRole.id]: {
    roleId: facilityAdminRole.id,
    syncStatus: 'SYNCED',
    lastSyncAt: LAST_SYNC_AT,
    permissions: [
      row(7, 'GET', '/admin/v1/me', 'Read own profile', 'Self', true),
      row(8, 'GET', '/admin/v1/me/menus', 'Read own menu tree', 'Self', true),
      row(9, 'GET', '/admin/v1/users/**', 'Read users', 'Users'),
      row(10, 'GET', '/v1/insights/**', null, null),
    ],
  },
};

// ---------------------------------------------------------------------------------------------
// Users: the test administrator and 24 users holding FACILITY_ADMIN (25 in all, two pages).
// ---------------------------------------------------------------------------------------------

const superAdminRef = {
  id: superAdminRole.id,
  name: superAdminRole.name,
  displayName: superAdminRole.displayName,
};
const facilityAdminRef = {
  id: facilityAdminRole.id,
  name: facilityAdminRole.name,
  displayName: facilityAdminRole.displayName,
};

export const testAdmin: User = {
  id: '30000000-0000-4000-8000-000000000001',
  keycloakId: '40000000-0000-4000-8000-000000000001',
  username: 'cce-admin',
  email: 'cce-admin@cce.local',
  firstName: 'Test',
  lastName: 'Administrator',
  status: 'ACTIVE',
  facilityScope: null,
  roles: [superAdminRef],
  lastLoginAt: UPDATED_AT,
  createdAt: CREATED_AT,
  updatedAt: CREATED_AT,
};

const STATUS_CYCLE: readonly UserStatus[] = ['ACTIVE', 'ACTIVE', 'INACTIVE', 'LOCKED'];

function testUser(n: number): User {
  const nn = String(n + 1).padStart(2, '0');
  const label = String(n).padStart(2, '0');
  return {
    id: `30000000-0000-4000-8000-0000000000${nn}`,
    keycloakId: `40000000-0000-4000-8000-0000000000${nn}`,
    username: `user${label}`,
    email: `user${label}@cce.local`,
    firstName: 'User',
    lastName: label,
    status: STATUS_CYCLE[n % STATUS_CYCLE.length] ?? 'ACTIVE',
    facilityScope: n % 2 === 0 ? 'FAC-01' : null,
    roles: [facilityAdminRef],
    lastLoginAt: n % 3 === 0 ? null : UPDATED_AT,
    createdAt: CREATED_AT,
    updatedAt: CREATED_AT,
  };
}

/** Ordered by username, as `GET /users` returns them. */
export const users: User[] = [testAdmin, ...Array.from({ length: 24 }, (_, i) => testUser(i + 1))];

/** The body a default `POST /users` returns. */
export const createdUser: User = {
  id: '30000000-0000-4000-8000-000000000099',
  keycloakId: '40000000-0000-4000-8000-000000000099',
  username: 'new.user',
  email: 'new.user@cce.local',
  firstName: 'New',
  lastName: 'User',
  status: 'ACTIVE',
  facilityScope: null,
  roles: [facilityAdminRef],
  lastLoginAt: null,
  createdAt: UPDATED_AT,
  updatedAt: UPDATED_AT,
};

// ---------------------------------------------------------------------------------------------
// Me: the test administrator.
// ---------------------------------------------------------------------------------------------

export const me: Me = {
  id: testAdmin.id,
  username: testAdmin.username,
  roles: ['SUPER_ADMIN'],
  facilityScope: null,
};
