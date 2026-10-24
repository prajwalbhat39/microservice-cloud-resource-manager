-- =============================================================================
-- Cloud Resource Manager — databases for units U2, U3 and U4
--
-- setup-u1.sql created quota_db and resource_db. This script creates the three
-- remaining service databases, so all seven services can run together.
--
-- Run ONCE as the postgres superuser:
--     psql -U postgres -h localhost -f scripts/db/setup-u2-u4.sql
--
-- Same PostgreSQL 15+ caveat as setup-u1.sql: a database-level GRANT is not
-- enough for Flyway. Since PG15 the `public` schema no longer lets PUBLIC create
-- objects, and Flyway creates its history table plus every table as the service
-- user, so each user needs CREATE *inside* its schema. Schema grants are
-- per-database, hence the \connect before each block.
--
-- SECURITY: 'changeme' is a placeholder matching each service's local default.
-- Override via DB_PASSWORD at runtime for anything that is not a dev laptop.
-- =============================================================================

-- ---------- U2: Billing service (:8083) ----------
CREATE DATABASE billing_db;
CREATE USER billing_user WITH PASSWORD 'changeme';

REVOKE ALL     ON DATABASE billing_db FROM PUBLIC;
GRANT  CONNECT ON DATABASE billing_db TO billing_user;

\connect billing_db
ALTER SCHEMA public OWNER TO billing_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE, CREATE ON SCHEMA public TO billing_user;

-- ---------- U3: Provider mock service (:8085) ----------
\connect postgres
CREATE DATABASE provider_db;
CREATE USER provider_user WITH PASSWORD 'changeme';

REVOKE ALL     ON DATABASE provider_db FROM PUBLIC;
GRANT  CONNECT ON DATABASE provider_db TO provider_user;

\connect provider_db
ALTER SCHEMA public OWNER TO provider_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE, CREATE ON SCHEMA public TO provider_user;

-- ---------- U4: Notification service (:8084) ----------
-- The other two U4 services (usage-aggregator :8086, expiry-worker :8087) are
-- stateless by design and have no database.
\connect postgres
CREATE DATABASE notification_db;
CREATE USER notification_user WITH PASSWORD 'changeme';

REVOKE ALL     ON DATABASE notification_db FROM PUBLIC;
GRANT  CONNECT ON DATABASE notification_db TO notification_user;

\connect notification_db
ALTER SCHEMA public OWNER TO notification_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  USAGE, CREATE ON SCHEMA public TO notification_user;

-- =============================================================================
-- Verify:  \l   should now list quota_db, resource_db, billing_db,
--               provider_db and notification_db
--          \du  should list the five matching users
--
-- Each service runs Flyway on startup, so the TABLES are created automatically
-- the first time that service boots. This script only creates the databases,
-- the users and their schema privileges.
-- =============================================================================
