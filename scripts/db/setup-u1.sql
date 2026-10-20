-- =============================================================================
-- Cloud Resource Manager — Unit U1 database setup
-- Services: Quota (quota_db) and Resource/Orchestrator (resource_db)
--
-- Run ONCE as the postgres superuser:
--     psql -U postgres -h localhost -f scripts/db/setup-u1.sql
--
-- PostgreSQL 15+ note (applies to your PG 18):
--   A plain `GRANT ALL ON DATABASE` is NOT enough for Flyway. At database level
--   PostgreSQL only has CONNECT / TEMPORARY / CREATE, where CREATE means "create
--   a new schema", not "create tables in an existing schema". Since PG15 the
--   `public` schema no longer lets PUBLIC create objects. Flyway must create its
--   history table plus every table/index as the service user, so each user needs
--   CREATE *inside* the schema it uses. Schema grants are per-database, so each
--   block reconnects with \connect before issuing them.
--
-- SECURITY: 'changeme' is a placeholder. For a shared repo keep it a placeholder
-- here and override via the DB_PASSWORD env var at runtime, OR change it below and
-- do not commit the changed copy. These passwords never appear in application code.
-- =============================================================================

-- ---------- Quota service ----------
CREATE DATABASE quota_db;
CREATE USER quota_user WITH PASSWORD 'changeme';

-- Database level: connect only. No CREATE (no new schemas needed),
-- no TEMPORARY (nothing uses temp tables).
REVOKE ALL      ON DATABASE quota_db FROM PUBLIC;   -- stop every other role connecting
GRANT  CONNECT  ON DATABASE quota_db TO quota_user;

-- Schema level: the part the simple grant pattern misses on PG15+.
\connect quota_db
ALTER SCHEMA public OWNER TO quota_user;             -- the service owns everything Flyway creates
REVOKE ALL ON SCHEMA public FROM PUBLIC;             -- redundant on PG15+, explicit for PG13/14
GRANT  USAGE, CREATE ON SCHEMA public TO quota_user;

-- ---------- Resource / Orchestrator service ----------
\connect postgres
CREATE DATABASE resource_db;
CREATE USER resource_user WITH PASSWORD 'changeme';

REVOKE ALL     ON DATABASE resource_db FROM PUBLIC;
GRANT  CONNECT ON DATABASE resource_db TO resource_user;

\connect resource_db
ALTER SCHEMA public OWNER TO resource_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE, CREATE ON SCHEMA public TO resource_user;

-- =============================================================================
-- Verify (optional): \l shows quota_db and resource_db; \du shows the two users.
-- =============================================================================
