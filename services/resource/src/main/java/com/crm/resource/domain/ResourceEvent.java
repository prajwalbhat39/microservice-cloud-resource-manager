package com.crm.resource.domain;

public enum ResourceEvent {
    /** REQUESTED -> PROVISIONING. Quota already reserved; row inserted. */
    PROVISION_STARTED,
    /** PROVISIONING -> PROVISIONING (self-transition). Sets provider_instance_id (AC-3.1). */
    INSTANCE_CREATED,
    /** PROVISIONING -> ACTIVE. Sets activated_at. Only path to ACTIVE (BR4). */
    ACTIVATED,
    /** REQUESTED or PROVISIONING -> FAILED. Sets failure_reason; compensation has already run. */
    SAGA_FAILED,
    /** ACTIVE -> TERMINATED. Sets terminated_at. Guarded by a conditional UPDATE (BR7 gate 1). */
    TERMINATE_REQUESTED
}
