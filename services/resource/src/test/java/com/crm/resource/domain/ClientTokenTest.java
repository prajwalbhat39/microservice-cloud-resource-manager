package com.crm.resource.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-19 unit test: the client token is stable for a given resourceId across calls. */
class ClientTokenTest {

    @Test
    void sameResourceIdAlwaysProducesSameToken() {
        UUID resourceId = UUID.fromString("e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f");

        // Called twice -> equal tokens, so a retried provider call is recognised by the provider
        // as the same request and returns the existing instance instead of creating a second
        // one (NFR-27). Nothing random, nothing time-based: that is the whole point.
        ClientToken first = ClientToken.of(resourceId);
        ClientToken second = ClientToken.of(resourceId);

        assertThat(first).isEqualTo(second);
        assertThat(first.value()).isEqualTo(second.value());
    }

    @Test
    void differentResourcesProduceDifferentTokens() {
        // Two resources must never share a token, or one provision would satisfy both.
        ClientToken a = ClientToken.of(UUID.randomUUID());
        ClientToken b = ClientToken.of(UUID.randomUUID());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void tokenCarriesTheResourceId() {
        UUID resourceId = UUID.randomUUID();

        // Readable in the provider's logs, which is how a leaked instance would be traced back.
        assertThat(ClientToken.of(resourceId).value()).contains(resourceId.toString());
    }
}
