\set ON_ERROR_STOP on
-- Run once after the application database setup. Never point tests at application data.
CREATE DATABASE quota_test OWNER quota_user;
REVOKE ALL ON DATABASE quota_test FROM PUBLIC;
CREATE DATABASE resource_test OWNER resource_user;
REVOKE ALL ON DATABASE resource_test FROM PUBLIC;
CREATE DATABASE billing_test OWNER billing_user;
REVOKE ALL ON DATABASE billing_test FROM PUBLIC;
\include_relative ../u4/setup-test-db.sql
