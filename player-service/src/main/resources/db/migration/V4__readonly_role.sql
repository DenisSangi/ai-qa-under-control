-- Read-only access for tests: they may inspect the database (e.g. the transactions ledger,
-- which has no REST endpoint) but cannot change it. Credentials: arcade_reader / arcade_reader.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'arcade_reader') THEN
        CREATE ROLE arcade_reader LOGIN PASSWORD 'arcade_reader';
    END IF;
END
$$;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO arcade_reader;
-- Tables created by later migrations become readable too.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO arcade_reader;
