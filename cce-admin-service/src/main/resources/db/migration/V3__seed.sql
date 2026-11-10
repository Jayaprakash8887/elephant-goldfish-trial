-- V3: seed rows (design section 6, B-02, AD-03, AD-14). Runs after V2 has checked api_permissions.

-- The 12 cce-insights-ui menus. Every row is active. The menu in row NN has id
-- 10000000-0000-4000-8000-0000000000NN. Parents come before their DETAIL children.
INSERT INTO menus (id, key, label, path, icon, type, parent_id, sort_order, is_active) VALUES
    ('10000000-0000-4000-8000-000000000001', 'dashboard',     'Dashboard',     '/',                    'ChartBarIcon',               'NAV', NULL, 10,  true),
    ('10000000-0000-4000-8000-000000000002', 'facilities',    'Facilities',    '/facilities',          'BuildingOffice2Icon',        'NAV', NULL, 20,  true),
    ('10000000-0000-4000-8000-000000000003', 'compliance',    'Compliance',    '/compliance',          'ClipboardDocumentCheckIcon', 'NAV', NULL, 30,  true),
    ('10000000-0000-4000-8000-000000000004', 'deviations',    'Deviations',    '/deviations',          'ExclamationTriangleIcon',    'NAV', NULL, 40,  true),
    ('10000000-0000-4000-8000-000000000005', 'patients',      'Patients',      '/compliance/patients', 'UserGroupIcon',              'NAV', NULL, 50,  true),
    ('10000000-0000-4000-8000-000000000006', 'events',        'Events',        '/events',              'SignalIcon',                 'NAV', NULL, 60,  true),
    ('10000000-0000-4000-8000-000000000007', 'ingestion',     'Ingestion',     '/ingestion',           'CogIcon',                    'NAV', NULL, 70,  true),
    ('10000000-0000-4000-8000-000000000008', 'practitioners', 'Practitioners', '/practitioners',       'UserIcon',                   'NAV', NULL, 80,  true),
    ('10000000-0000-4000-8000-000000000009', 'intelligence',  'Intelligence',  '/intelligence',        'LightBulbIcon',              'NAV', NULL, 90,  true),
    ('10000000-0000-4000-8000-000000000010', 'exports',       'Exports',       '/exports',             'ArrowDownTrayIcon',          'NAV', NULL, 100, true),
    ('10000000-0000-4000-8000-000000000011', 'compliance.protocol-detail', 'Protocol analytics', '/compliance/protocols/:id', NULL, 'DETAIL',
        '10000000-0000-4000-8000-000000000003', 10, true),
    ('10000000-0000-4000-8000-000000000012', 'patients.patient-detail',    'Patient detail',     '/compliance/patients/:id',  NULL, 'DETAIL',
        '10000000-0000-4000-8000-000000000005', 10, true);

-- The two system roles, with explicit is_assignable (the column has no default, AD-14).
-- Both start with sync_status NEVER and are granted no menus.
INSERT INTO roles (id, name, display_name, description, is_system, is_assignable, sync_status) VALUES
    ('20000000-0000-4000-8000-000000000001', 'SUPER_ADMIN', 'Super administrator',
        'Platform administrator with full access to the admin API.', true, true, 'NEVER'),
    ('20000000-0000-4000-8000-000000000002', 'CCE_ADMIN_SERVICE', 'Admin service account',
        'Service account of cce-admin-service. Calls the gateway sync.', true, false, 'NEVER');

-- The six api_permissions rows. ON CONFLICT on the columns works whatever the existing
-- constraint is called (V2 has checked that it exists).
INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category) VALUES
    ('SUPER_ADMIN',       'GET',    '/admin/v1/**',                      'Administer the CCE admin module',       'Admin'),
    ('SUPER_ADMIN',       'POST',   '/admin/v1/**',                      'Administer the CCE admin module',       'Admin'),
    ('SUPER_ADMIN',       'PUT',    '/admin/v1/**',                      'Administer the CCE admin module',       'Admin'),
    ('SUPER_ADMIN',       'PATCH',  '/admin/v1/**',                      'Administer the CCE admin module',       'Admin'),
    ('SUPER_ADMIN',       'DELETE', '/admin/v1/**',                      'Administer the CCE admin module',       'Admin'),
    ('CCE_ADMIN_SERVICE', 'POST',   '/api/permissions/sync-to-keycloak', 'Trigger the gateway''s realm role sync', 'Gateway')
ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING;
