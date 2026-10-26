CREATE TABLE resource_work (
    resource_id uuid PRIMARY KEY,
    kind varchar(20) NOT NULL CHECK (kind IN ('QUOTA_RELEASE', 'PROVISION', 'COMPENSATE', 'TERMINATE')),
    payload text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Recover unfinished work from installations that predate the durable queue.
INSERT INTO resource_work(resource_id, kind, payload)
SELECT r.resource_id,
       CASE WHEN r.status = 'FAILED' THEN 'COMPENSATE' ELSE 'PROVISION' END,
       CASE WHEN r.status = 'FAILED'
            THEN json_build_object('task', task.body, 'reason', coalesce(r.failure_reason, 'Recovery of failed provisioning'))::text
            ELSE task.body::text END
FROM resources r JOIN reservations v ON v.resource_id = r.resource_id
CROSS JOIN LATERAL (SELECT json_build_object(
    'resourceId', r.resource_id, 'reservationId', v.reservation_id,
    'tenantId', r.tenant_id, 'type', r.type, 'cpu', r.cpu, 'ramGb', r.ram_gb,
    'simulate', json_build_object('raw', v.simulate_failure), 'correlationId', NULL) AS body) task
WHERE r.status = 'FAILED' OR (v.status = 'PENDING' AND r.status IN ('REQUESTED', 'PROVISIONING'));

INSERT INTO resource_work(resource_id, kind, payload)
SELECT resource_id, 'TERMINATE', json_build_object(
    'resourceId', resource_id, 'tenantId', tenant_id,
    'providerInstanceId', provider_instance_id, 'correlationId', NULL)::text
FROM resources WHERE status = 'TERMINATED'
ON CONFLICT DO NOTHING;
