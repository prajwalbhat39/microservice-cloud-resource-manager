\set ON_ERROR_STOP on
-- This database is exclusively for NotificationHttpIT; tests delete its notification rows.
CREATE USER notification_test_user WITH PASSWORD 'changeme';
CREATE DATABASE notification_u4_test OWNER notification_test_user;
REVOKE ALL ON DATABASE notification_u4_test FROM PUBLIC;
