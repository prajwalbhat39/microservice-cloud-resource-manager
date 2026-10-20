CREATE TABLE IF NOT EXISTS billing_meters (
    meter_id UUID PRIMARY KEY,
    resource_id TEXT NOT NULL UNIQUE,
    tenant_id TEXT NOT NULL,
    cpu INTEGER NOT NULL,
    rate_per_cpu_hour NUMERIC(10,4) NOT NULL,
    state TEXT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    stopped_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS billing_events (
    event_id BIGSERIAL PRIMARY KEY,
    resource_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL
);
