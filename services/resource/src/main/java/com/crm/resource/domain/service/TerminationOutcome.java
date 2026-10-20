package com.crm.resource.domain.service;

import com.crm.resource.persistence.entity.Resource;

/** What TerminationService.terminate hands back to the controller for R5's 202 body. */
public record TerminationOutcome(Resource resource, boolean alreadyTerminated) {

    public static TerminationOutcome terminated(Resource resource) {
        return new TerminationOutcome(resource, false);
    }

    public static TerminationOutcome alreadyTerminated(Resource resource) {
        return new TerminationOutcome(resource, true);
    }
}
