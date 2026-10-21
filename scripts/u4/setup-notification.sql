\set ON_ERROR_STOP on
-- Run once as an administrator on a local development server.
CREATE USER notification_user WITH PASSWORD 'changeme';
CREATE DATABASE notification_db;
REVOKE ALL ON DATABASE notification_db FROM PUBLIC;
GRANT CONNECT ON DATABASE notification_db TO notification_user;
\connect notification_db
ALTER SCHEMA public OWNER TO notification_user;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO notification_user;
