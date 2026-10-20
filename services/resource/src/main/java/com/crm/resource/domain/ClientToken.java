package com.crm.resource.domain;

/**
 * FR-19, NFR-27: a client token derived from resource identity, so it is stable across retries.
 * A retried provider call with the same token returns the existing instance instead of creating
 * a second one.
 */
public record ClientToken(String value) {

    public static ClientToken of(java.util.UUID resourceId) {
        return new ClientToken("ct-" + resourceId);
    }
}
