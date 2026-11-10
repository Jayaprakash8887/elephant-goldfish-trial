CREATE TABLE api_permissions (
    id BIGSERIAL PRIMARY KEY,
    permission_name VARCHAR(100) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    uri_pattern VARCHAR(255) NOT NULL,
    description TEXT,
    resource_category VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_permission_method_uri UNIQUE (permission_name, http_method, uri_pattern)
);
CREATE INDEX idx_permission_name ON api_permissions (permission_name);
CREATE INDEX idx_resource_category ON api_permissions (resource_category);
CREATE FUNCTION update_api_permissions_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_api_permissions_updated_at BEFORE UPDATE ON api_permissions
    FOR EACH ROW EXECUTE FUNCTION update_api_permissions_updated_at();
INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category) VALUES
    ('USER_READ', 'GET', '/admin/users/*', 'View specific user details', 'Users'),
    ('USER_READ', 'GET', '/admin/users', 'List all users', 'Users'),
    ('USER_CREATE_UPDATE', 'POST', '/admin/users', 'Create new user', 'Users'),
    ('USER_CREATE_UPDATE', 'PUT', '/admin/users/*', 'Update user details', 'Users'),
    ('USER_CREATE_UPDATE', 'PATCH', '/admin/users/*', 'Partially update user', 'Users');
