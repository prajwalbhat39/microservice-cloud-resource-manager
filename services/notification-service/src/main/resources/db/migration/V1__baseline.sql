CREATE TABLE notifications (
    notification_id uuid PRIMARY KEY,
    tenant_id varchar(64) NOT NULL CHECK (length(btrim(tenant_id)) > 0),
    resource_id varchar(64) NOT NULL CHECK (length(btrim(resource_id)) > 0),
    event_type varchar(20) NOT NULL CHECK (event_type IN ('ACTIVE', 'FAILED', 'TERMINATED')),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    message varchar(2000) NOT NULL CHECK (length(btrim(message)) > 0),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL,
    sent_at timestamptz,
    CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_notifications_tenant_created ON notifications (tenant_id, created_at);
CREATE INDEX ix_notifications_pending ON notifications (created_at) WHERE status = 'PENDING';
