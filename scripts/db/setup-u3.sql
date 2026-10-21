-- Run once as a PostgreSQL superuser: psql -U postgres -h localhost -f scripts\db\setup-u3.sql
-- Local-development placeholder only; set DB_PASSWORD outside developer laptops.
CREATE DATABASE provider_db;
CREATE USER provider_user WITH PASSWORD 'changeme';
REVOKE ALL ON DATABASE provider_db FROM PUBLIC;
GRANT CONNECT ON DATABASE provider_db TO provider_user;

\connect provider_db
ALTER SCHEMA public OWNER TO provider_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO provider_user;
