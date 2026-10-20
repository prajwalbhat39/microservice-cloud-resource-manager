package com.crm.resource.api.dto;

import java.util.List;

/** R4/R6 envelope — not a bare array, so adding paging later is not a breaking change. */
public record ResourceListResponse(List<ResourceResponse> items, int count) {

    public static ResourceListResponse of(List<ResourceResponse> items) {
        return new ResourceListResponse(items, items.size());
    }
}
