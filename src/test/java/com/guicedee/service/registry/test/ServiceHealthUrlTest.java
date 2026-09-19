package com.guicedee.service.registry.test;

import com.guicedee.service.registry.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServiceHealthUrlTest {
    private ServiceEntry entry(Map<String, String> metadata) {
        return new ServiceEntry("core", "https://application.example/", "health/ready",
                ServiceStatus.UNKNOWN, Instant.now(), metadata);
    }

    @Test void absentOverridePreservesExistingPathResolution() {
        assertEquals("https://application.example/health/ready", entry(Map.of()).healthUrl());
    }

    @Test void explicitAddressSurvivesUpdatesWithoutChangingApplicationRouting() {
        var metadata = new HashMap<>(Map.of("healthUrl", "https://management.internal:9443/health/ready"));
        var original = entry(metadata);
        metadata.put("healthUrl", "http://changed.example/");
        var updated = original.withStatusAndDetails(ServiceStatus.UP, List.of()).withStatus(ServiceStatus.UP);
        assertEquals("https://management.internal:9443/health/ready", updated.healthUrl());
        assertEquals("https://application.example/", updated.url());
        try {
            ServiceRegistry.register(updated);
            assertEquals(updated.url(), ServiceRegistry.healthyUrl("core").orElseThrow());
        } finally { ServiceRegistry.clear(); }
    }

    @Test void malformedConfiguredAddressCannotFallBackOrEchoSecrets() {
        for (String value : List.of("", " ", "${MISSING}", "/health/ready", "file:///tmp/health",
                "https://user:secret@example.com/health", "https://example.com?token=secret",
                "https://example.com#fragment", "https:///health", "https://example.com:0",
                "https://example.com:65536", "not a url")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> entry(Map.of("healthUrl", value)));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("secret"));
        }
    }

    @RegisteredService(name = "legacy")
    static class LegacyDeclaration { }

    @Test void existingAnnotationDeclarationsHaveNoOverride() {
        assertEquals("", LegacyDeclaration.class.getAnnotation(RegisteredService.class).healthUrl());
    }
}
