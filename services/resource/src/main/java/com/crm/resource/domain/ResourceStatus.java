package com.crm.resource.domain;

/**
 * The five-state linear lifecycle (BR4, FR-47 to FR-50). No STOPPING/STOPPED (assumption A1):
 * REQUESTED -> PROVISIONING -> ACTIVE -> TERMINATED, with FAILED reachable from REQUESTED or
 * PROVISIONING via saga compensation.
 */
public enum ResourceStatus {
    REQUESTED,
    PROVISIONING,
    ACTIVE,
    FAILED,
    TERMINATED
}
