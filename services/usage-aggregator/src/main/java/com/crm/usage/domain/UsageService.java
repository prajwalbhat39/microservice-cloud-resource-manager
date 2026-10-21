package com.crm.usage.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class UsageService {
    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private final RestClient quota;
    private final RestClient resource;
    private final RestClient billing;
    private final Clock clock;

    public UsageService(@Qualifier("quotaClient") RestClient quota,
            @Qualifier("resourceClient") RestClient resource,
            @Qualifier("billingClient") RestClient billing, Clock clock) {
        this.quota = quota;
        this.resource = resource;
        this.billing = billing;
        this.clock = clock;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(JsonNode quota, JsonNode resources, JsonNode cost, Availability availability) { }
    public record Availability(boolean quota, boolean resources, boolean billing) { }

    public Usage read(String tenantId) {
        JsonNode quotaData = readObject(quota, "quota", "/api/v1/tenants/{id}/quota", tenantId);
        JsonNode resourceData = readObject(resource, "resource",
                "/api/v1/resources?tenantId={id}&status=ACTIVE", tenantId);
        JsonNode items = resourceData == null ? null : resourceData.get("items");
        if (items != null && !items.isArray()) items = null;
        JsonNode cost = readObject(billing, "billing",
                "/api/v1/billing/tenants/{id}/cost?period={period}", tenantId, YearMonth.now(clock).toString());
        return new Usage(quotaData, items, cost, new Availability(quotaData != null, items != null, cost != null));
    }

    private JsonNode readObject(RestClient client, String peer, String path, Object... variables) {
        try {
            JsonNode data = client.get().uri(path, variables).retrieve().body(JsonNode.class);
            if (data != null && data.isObject()) return data;
            log.warn("usage.peer.invalid-response peer={}", peer);
        } catch (RestClientException failure) {
            log.warn("usage.peer.unavailable peer={} cause={}", peer, failure.getClass().getSimpleName());
        }
        return null;
    }
}
