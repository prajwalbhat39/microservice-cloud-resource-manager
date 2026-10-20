package com.crm.resource.domain;

import com.crm.resource.common.error.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR4 unit test (no Spring context, no database): the transition GRAPH, exercised through
 * canTransition/nextStatus. The repository and clock are only needed by {@code apply}, which
 * writes a row, so they are left null here (functional-design.md 6).
 */
class ResourceLifecycleTest {

    private final ResourceLifecycle lifecycle = new ResourceLifecycle(null, Clock.systemUTC());

    @Test
    void activeOnlyReachableFromProvisioning() {
        // BR4: the ONLY transition landing on ACTIVE is PROVISIONING --ACTIVATED--> ACTIVE.
        assertThat(lifecycle.nextStatus(ResourceStatus.PROVISIONING, ResourceEvent.ACTIVATED))
                .isEqualTo(ResourceStatus.ACTIVE);

        // Exhaustive sweep of the whole graph: no other (from, event) pair may yield ACTIVE.
        for (ResourceStatus from : ResourceStatus.values()) {
            for (ResourceEvent event : ResourceEvent.values()) {
                boolean isTheOnePath = from == ResourceStatus.PROVISIONING && event == ResourceEvent.ACTIVATED;
                if (isTheOnePath || !lifecycle.canTransition(from, event)) {
                    continue;
                }
                assertThat(lifecycle.nextStatus(from, event))
                        .as("%s --%s--> must not reach ACTIVE", from, event)
                        .isNotEqualTo(ResourceStatus.ACTIVE);
            }
        }
    }

    @Test
    void illegalTransitionIsRejected() {
        // REQUESTED --ACTIVATED--> is not on the graph: provisioning cannot be skipped.
        assertThat(lifecycle.canTransition(ResourceStatus.REQUESTED, ResourceEvent.ACTIVATED)).isFalse();
        assertThatThrownBy(() -> lifecycle.nextStatus(ResourceStatus.REQUESTED, ResourceEvent.ACTIVATED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("REQUESTED")
                .hasMessageContaining("ACTIVATED");

        // FAILED is terminal: nothing leaves it.
        for (ResourceEvent event : ResourceEvent.values()) {
            assertThat(lifecycle.canTransition(ResourceStatus.FAILED, event))
                    .as("FAILED must be terminal, but %s was allowed", event)
                    .isFalse();
        }
    }

    @Test
    void activeCannotBeUnwoundBySagaFailure() {
        // ACTIVE --SAGA_FAILED--> is deliberately absent (FR-33): once ACTIVE the saga has
        // committed, and a later failure (e.g. a notification failure) must not unwind a
        // provisioned, paid-for resource.
        assertThat(lifecycle.canTransition(ResourceStatus.ACTIVE, ResourceEvent.SAGA_FAILED)).isFalse();
    }

    @Test
    void terminatedIsAbsorbing() {
        // TERMINATED --TERMINATE_REQUESTED--> TERMINATED: legal, and a no-op (FR-21). A repeat
        // must not be a 409, because the expiry worker legitimately re-sends terminations.
        assertThat(lifecycle.canTransition(ResourceStatus.TERMINATED, ResourceEvent.TERMINATE_REQUESTED)).isTrue();
        assertThat(lifecycle.nextStatus(ResourceStatus.TERMINATED, ResourceEvent.TERMINATE_REQUESTED))
                .isEqualTo(ResourceStatus.TERMINATED);
    }

    @Test
    void theHappyPathIsWalkable() {
        // REQUESTED -> PROVISIONING -(self)-> PROVISIONING -> ACTIVE -> TERMINATED.
        assertThat(lifecycle.nextStatus(ResourceStatus.REQUESTED, ResourceEvent.PROVISION_STARTED))
                .isEqualTo(ResourceStatus.PROVISIONING);
        assertThat(lifecycle.nextStatus(ResourceStatus.PROVISIONING, ResourceEvent.INSTANCE_CREATED))
                .isEqualTo(ResourceStatus.PROVISIONING);
        assertThat(lifecycle.nextStatus(ResourceStatus.PROVISIONING, ResourceEvent.ACTIVATED))
                .isEqualTo(ResourceStatus.ACTIVE);
        assertThat(lifecycle.nextStatus(ResourceStatus.ACTIVE, ResourceEvent.TERMINATE_REQUESTED))
                .isEqualTo(ResourceStatus.TERMINATED);
    }

    @Test
    void failedIsReachableFromBothPreActiveStates() {
        // Compensation can land on FAILED from either side of the provider call.
        assertThat(lifecycle.nextStatus(ResourceStatus.REQUESTED, ResourceEvent.SAGA_FAILED))
                .isEqualTo(ResourceStatus.FAILED);
        assertThat(lifecycle.nextStatus(ResourceStatus.PROVISIONING, ResourceEvent.SAGA_FAILED))
                .isEqualTo(ResourceStatus.FAILED);
    }

    @Test
    void thereIsNoStoppedState() {
        // Assumption A1: the lifecycle is linear, with no stop/start operation.
        assertThat(Arrays.stream(ResourceStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("REQUESTED", "PROVISIONING", "ACTIVE", "FAILED", "TERMINATED");
    }
}
