package com.crm.resource.api.dto;

import java.util.List;

public record ExpiredResourceListResponse(List<ExpiredResourceResponse> items, int count) { }
