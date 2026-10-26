CREATE TABLE cancelled_requests (client_token varchar(256) PRIMARY KEY, cancelled_at timestamptz NOT NULL DEFAULT now());
