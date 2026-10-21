CREATE TABLE instances (
    instance_id   text PRIMARY KEY,
    client_token  text NOT NULL UNIQUE,
    type          text NOT NULL CHECK (type IN ('VM', 'CONTAINER', 'CLUSTER')),
    cpu           integer NOT NULL CHECK (cpu > 0),
    ram_gb        integer NOT NULL CHECK (ram_gb > 0),
    status        text NOT NULL CHECK (status IN ('ACTIVE', 'TERMINATED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    terminated_at timestamptz,
    CONSTRAINT termination_timestamp CHECK (
        (status = 'ACTIVE' AND terminated_at IS NULL)
        OR (status = 'TERMINATED' AND terminated_at IS NOT NULL)
    )
);
