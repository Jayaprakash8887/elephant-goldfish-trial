-- V2: check that api_permissions has the shape gateway-service reads (design section 6, AD-02),
-- before V3 writes any seed row. Raises an exception, which stops the migration, on any mismatch:
--   * each column's name, data type, length, nullability and default,
--   * a unique constraint on exactly (permission_name, http_method, uri_pattern), whatever its name,
--   * a non-unique index whose only column is permission_name, and one on resource_category.
DO $do$
DECLARE
    problems text[] := ARRAY[]::text[];
    r record;
BEGIN
    IF to_regclass('public.api_permissions') IS NULL THEN
        RAISE EXCEPTION 'api_permissions check failed: table public.api_permissions does not exist';
    END IF;

    -- Columns: every expected column must exist exactly as described, and no other column may exist.
    FOR r IN
        WITH expected (description) AS (
            VALUES
                ('id bigint length=- nullable=NO default=nextval(''api_permissions_id_seq''::regclass)'),
                ('permission_name character varying length=100 nullable=NO default=-'),
                ('http_method character varying length=10 nullable=NO default=-'),
                ('uri_pattern character varying length=255 nullable=NO default=-'),
                ('description text length=- nullable=YES default=-'),
                ('resource_category character varying length=50 nullable=YES default=-'),
                ('created_at timestamp without time zone length=- nullable=YES default=CURRENT_TIMESTAMP'),
                ('updated_at timestamp without time zone length=- nullable=YES default=CURRENT_TIMESTAMP')
        ),
        actual (description) AS (
            SELECT c.column_name || ' ' || c.data_type
                   || ' length=' || coalesce(c.character_maximum_length::text, '-')
                   || ' nullable=' || c.is_nullable
                   || ' default=' || coalesce(c.column_default, '-')
            FROM information_schema.columns c
            WHERE c.table_schema = 'public' AND c.table_name = 'api_permissions'
        )
        SELECT 'expected column [' || description || '] not found' AS problem
        FROM (SELECT description FROM expected EXCEPT SELECT description FROM actual) missing
        UNION ALL
        SELECT 'unexpected column [' || description || ']'
        FROM (SELECT description FROM actual EXCEPT SELECT description FROM expected) extra
    LOOP
        problems := problems || r.problem;
    END LOOP;

    -- Unique constraint on exactly (permission_name, http_method, uri_pattern), whatever its name.
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint con
        WHERE con.conrelid = 'public.api_permissions'::regclass
          AND con.contype = 'u'
          AND pg_get_constraintdef(con.oid) = 'UNIQUE (permission_name, http_method, uri_pattern)'
    ) THEN
        problems := problems
            || 'no unique constraint on exactly (permission_name, http_method, uri_pattern)'::text;
    END IF;

    -- A non-unique, non-partial index whose only column is permission_name.
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes i
        WHERE i.schemaname = 'public' AND i.tablename = 'api_permissions'
          AND i.indexdef ~ '^CREATE INDEX \S+ ON \S+ USING \w+ \(permission_name\)$'
    ) THEN
        problems := problems || 'no non-unique index whose only column is permission_name'::text;
    END IF;

    -- A non-unique, non-partial index whose only column is resource_category.
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes i
        WHERE i.schemaname = 'public' AND i.tablename = 'api_permissions'
          AND i.indexdef ~ '^CREATE INDEX \S+ ON \S+ USING \w+ \(resource_category\)$'
    ) THEN
        problems := problems || 'no non-unique index whose only column is resource_category'::text;
    END IF;

    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION 'api_permissions check failed: the table does not have the shape gateway-service reads: %',
            array_to_string(problems, '; ');
    END IF;
END
$do$;
