CREATE TABLE billing_stops (resource_id text PRIMARY KEY, stopped_at timestamptz NOT NULL DEFAULT now());
