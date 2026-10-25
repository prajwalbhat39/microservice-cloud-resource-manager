-- A late reserve must not recreate an allocation after recovery released it.
CREATE TABLE quota_cancellations (
    resource_id varchar(64) PRIMARY KEY,
    cancelled_at timestamptz NOT NULL DEFAULT now()
);
