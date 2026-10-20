package com.crm.quota.domain;

/**
 * Lifecycle of a ledger row in quota_reservations (BR7). A row is created RESERVED and flips to
 * RELEASED exactly once; the flip is guarded by the row's state so a repeated release is a no-op.
 */
public enum ReservationState {
    RESERVED,
    RELEASED
}
