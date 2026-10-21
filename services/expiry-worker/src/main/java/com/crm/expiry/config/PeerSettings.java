package com.crm.expiry.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("peers")
public record PeerSettings(@Valid @NotNull Peer resource) {
    public record Peer(@NotNull URI url, @Min(1) int connectTimeoutMs, @Min(1) int readTimeoutMs) { }
}
