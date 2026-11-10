-- V1: schema of cce-admin-service (design section 6, B-01).
-- Database admin_service, schema public (AD-01).
--
-- DESIGN-GAP: section 6 names the unique constraints, the indexes, ck_menus_parent and the two
-- triggers, but not the primary key, foreign key and other CHECK constraint names, nor the
-- function behind trg_audit_log_append_only. Those names are chosen here (ck_<table>_<column>,
-- fk_<table>_<column>, PostgreSQL's default <table>_pkey, audit_log_append_only()).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- users ------------------------------------------------------------------------------------------
CREATE TABLE users (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    keycloak_id    uuid         NOT NULL,
    username       varchar(50)  NOT NULL,
    email          varchar(254) NOT NULL,
    first_name     varchar(100) NOT NULL,
    last_name      varchar(100) NOT NULL,
    status         varchar(10)  NOT NULL,
    facility_scope varchar(50),
    last_login_at  timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT ux_users_keycloak_id UNIQUE (keycloak_id),
    CONSTRAINT ux_users_username UNIQUE (username),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED'))
);
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));
CREATE INDEX ix_users_status ON users (status);
CREATE INDEX ix_users_facility_scope ON users (facility_scope);

-- roles ------------------------------------------------------------------------------------------
CREATE TABLE roles (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    name          varchar(100) NOT NULL,
    display_name  varchar(100) NOT NULL,
    description   varchar(500),
    is_system     boolean      NOT NULL DEFAULT false,
    is_assignable boolean      NOT NULL, -- no default (AD-14): every insert must set it
    sync_status   varchar(10)  NOT NULL DEFAULT 'NEVER',
    last_sync_at  timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT roles_pkey PRIMARY KEY (id),
    CONSTRAINT ux_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name CHECK (name ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'),
    CONSTRAINT ck_roles_sync_status CHECK (sync_status IN ('NEVER', 'SYNCED', 'FAILED'))
);

-- menus ------------------------------------------------------------------------------------------
CREATE TABLE menus (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    key        varchar(100) NOT NULL,
    label      varchar(100) NOT NULL,
    path       varchar(255) NOT NULL,
    icon       varchar(100),
    type       varchar(10)  NOT NULL,
    parent_id  uuid,
    sort_order integer      NOT NULL,
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT menus_pkey PRIMARY KEY (id),
    CONSTRAINT ux_menus_key UNIQUE (key),
    CONSTRAINT ck_menus_type CHECK (type IN ('NAV', 'DETAIL')),
    CONSTRAINT ck_menus_sort_order CHECK (sort_order BETWEEN 0 AND 10000),
    CONSTRAINT ck_menus_parent CHECK ((type = 'NAV' AND parent_id IS NULL)
                                      OR (type = 'DETAIL' AND parent_id IS NOT NULL)),
    CONSTRAINT fk_menus_parent_id FOREIGN KEY (parent_id) REFERENCES menus (id) ON DELETE RESTRICT
);
CREATE INDEX ix_menus_parent_id ON menus (parent_id);

-- role_menus -------------------------------------------------------------------------------------
CREATE TABLE role_menus (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    role_id    uuid        NOT NULL,
    menu_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT role_menus_pkey PRIMARY KEY (id),
    CONSTRAINT ux_role_menus_role_menu UNIQUE (role_id, menu_id),
    CONSTRAINT fk_role_menus_role_id FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_menus_menu_id FOREIGN KEY (menu_id) REFERENCES menus (id) ON DELETE CASCADE
);
CREATE INDEX ix_role_menus_menu_id ON role_menus (menu_id);

-- user_roles -------------------------------------------------------------------------------------
CREATE TABLE user_roles (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL,
    role_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_roles_pkey PRIMARY KEY (id),
    CONSTRAINT ux_user_roles_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
);
CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);

-- audit_log (append-only) ------------------------------------------------------------------------
CREATE TABLE audit_log (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    occurred_at       timestamptz  NOT NULL DEFAULT now(),
    actor_user_id     uuid,
    actor_keycloak_id varchar(64),
    actor_username    varchar(100) NOT NULL,
    action            varchar(50)  NOT NULL,
    entity_type       varchar(20)  NOT NULL,
    entity_id         uuid         NOT NULL,
    outcome           varchar(10)  NOT NULL,
    before_state      jsonb,
    after_state       jsonb,
    failure           jsonb,
    ip_address        varchar(45),
    CONSTRAINT audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_entity_type CHECK (entity_type IN ('USER', 'ROLE', 'MENU')),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'FAILED'))
);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_actor_user_id ON audit_log (actor_user_id);
CREATE INDEX ix_audit_log_outcome ON audit_log (outcome);

CREATE FUNCTION audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$fn$;

CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

-- api_permissions (gateway-service's table, AD-02) -----------------------------------------------
-- Created only when absent. An existing table is never altered. V2 checks its shape.
DO $do$
BEGIN
    IF to_regclass('public.api_permissions') IS NULL THEN
        CREATE TABLE public.api_permissions (
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
        CREATE INDEX idx_permission_name ON public.api_permissions (permission_name);
        CREATE INDEX idx_resource_category ON public.api_permissions (resource_category);
        CREATE FUNCTION public.update_api_permissions_updated_at() RETURNS trigger
            LANGUAGE plpgsql AS $fn$
        BEGIN
            NEW.updated_at := CURRENT_TIMESTAMP;
            RETURN NEW;
        END;
        $fn$;
        CREATE TRIGGER trg_api_permissions_updated_at BEFORE UPDATE ON public.api_permissions
            FOR EACH ROW EXECUTE FUNCTION public.update_api_permissions_updated_at();
    END IF;
END
$do$;
