package com.crm.resource.domain;

/** VM | CONTAINER | CLUSTER. Every type counts as one instance toward a tenant's limit (FR-6). */
public enum ResourceType {
    VM,
    CONTAINER,
    CLUSTER
}
