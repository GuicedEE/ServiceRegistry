package com.guicedee.service.registry.test;

import com.guicedee.service.registry.*;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for blue/green deployment support:
 * 1. Multi-instance registration with revisions
 * 2. Health status tracking per instance
 * 3. Best instance selection (latest healthy)
 * 4. Overlap period handling (both v1 and v2 UP)
 * 5. Switchover (v1 DOWN, v2 UP)
 * 6. expectedStatusCode / expectedContentType metadata
 * 7. WARNING status for non-JSON responses
 */
public class BlueGreenDeploymentTest
{
    @BeforeEach
    void setup()
    {
        ServiceRegistry.clear();
    }

    // ── Multi-Instance Registration ──────────────────────────────────────────

    @Test
    @DisplayName("Register single instance with revision")
    void registerSingleInstanceWithRevision()
    {
        ServiceEntry v1 = new ServiceEntry(
                "my-api", "https://my-api-rev1.cloud.io",
                List.of(), "", "/health/ready",
                ServiceStatus.UP, Instant.now(),
                Map.of("revision", "rev1"));

        ServiceRegistry.register(v1);

        // Should appear in primary services
        assertTrue(ServiceRegistry.contains("my-api"));
        assertEquals("https://my-api-rev1.cloud.io", ServiceRegistry.url("my-api"));

        // Should appear in instances
        List<ServiceEntry> instances = ServiceRegistry.instances("my-api");
        assertEquals(1, instances.size());
        assertEquals("rev1", instances.getFirst().revision());
    }

    @Test
    @DisplayName("Register two instances (blue/green) — both tracked")
    void registerBlueGreenInstances()
    {
        ServiceEntry blue = new ServiceEntry(
                "my-api", "https://my-api-blue.cloud.io",
                List.of(), "", "/health/ready",
                ServiceStatus.UP, Instant.now(),
                Map.of("revision", "blue-001"));

        ServiceEntry green = new ServiceEntry(
                "my-api", "https://my-api-green.cloud.io",
                List.of(), "", "/health/ready",
                ServiceStatus.UP, Instant.now(),
                Map.of("revision", "green-002"));

        ServiceRegistry.register(blue);
        ServiceRegistry.register(green);

        // Both instances tracked
        List<ServiceEntry> instances = ServiceRegistry.instances("my-api");
        assertEquals(2, instances.size());

        // Primary map should select latest revision (green-002 > blue-001 lexicographically)
        assertEquals("https://my-api-green.cloud.io", ServiceRegistry.url("my-api"));
    }

    @Test
    @DisplayName("Service without revision is NOT tracked as instance")
    void serviceWithoutRevisionNotTrackedAsInstance()
    {
        ServiceEntry simple = new ServiceEntry(
                "static-site", "https://example.com",
                "/", ServiceStatus.UP, Instant.now(),
                Map.of("expectedContentType", "text/html"));

        ServiceRegistry.register(simple);

        assertTrue(ServiceRegistry.contains("static-site"));
        List<ServiceEntry> instances = ServiceRegistry.instances("static-site");
        assertTrue(instances.isEmpty());
    }

    // ── Blue/Green Overlap Period ────────────────────────────────────────────

    @Test
    @DisplayName("Both instances UP during overlap — healthyInstances returns both")
    void overlapPeriodBothUp()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        List<ServiceEntry> healthy = ServiceRegistry.healthyInstances("my-api");
        assertEquals(2, healthy.size());
    }

    @Test
    @DisplayName("Both UP — latestHealthyInstance prefers green (latest revision)")
    void overlapPeriodLatestPreferred()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        Optional<ServiceEntry> latest = ServiceRegistry.latestHealthyInstance("my-api");
        assertTrue(latest.isPresent());
        assertEquals("green-002", latest.get().revision());
        assertEquals("https://my-api-green.cloud.io", latest.get().url());
    }

    @Test
    @DisplayName("Primary URL resolves to latest healthy during overlap")
    void primaryUrlDuringOverlap()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        // Primary should be green (latest healthy)
        assertEquals("https://my-api-green.cloud.io", ServiceRegistry.url("my-api"));
    }

    // ── Switchover (v1 draining) ─────────────────────────────────────────────

    @Test
    @DisplayName("v1 goes DOWN during switchover — primary switches to v2")
    void switchoverV1Down()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        // v1 goes down
        ServiceRegistry.updateInstanceStatus("my-api", "blue-001", ServiceStatus.DOWN);

        // Primary should now be green
        assertEquals("https://my-api-green.cloud.io", ServiceRegistry.url("my-api"));

        // Only green is healthy
        List<ServiceEntry> healthy = ServiceRegistry.healthyInstances("my-api");
        assertEquals(1, healthy.size());
        assertEquals("green-002", healthy.getFirst().revision());
    }

    @Test
    @DisplayName("v2 not yet UP — primary stays with v1")
    void v2NotYetUp()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UNKNOWN);

        // Primary should be blue (only healthy one)
        assertEquals("https://my-api-blue.cloud.io", ServiceRegistry.url("my-api"));

        Optional<ServiceEntry> latest = ServiceRegistry.latestHealthyInstance("my-api");
        assertTrue(latest.isPresent());
        assertEquals("blue-001", latest.get().revision());
    }

    @Test
    @DisplayName("Both DOWN — healthyUrl returns empty")
    void bothDown()
    {
        registerBlueGreen("my-api", ServiceStatus.DOWN, ServiceStatus.DOWN);

        Optional<String> url = ServiceRegistry.healthyUrl("my-api");
        assertTrue(url.isEmpty());

        List<ServiceEntry> healthy = ServiceRegistry.healthyInstances("my-api");
        assertTrue(healthy.isEmpty());
    }

    // ── Instance Status Updates ──────────────────────────────────────────────

    @Test
    @DisplayName("updateInstanceStatus updates specific revision")
    void updateInstanceStatusByRevision()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        ServiceRegistry.updateInstanceStatus("my-api", "blue-001", ServiceStatus.DEGRADED);

        List<ServiceEntry> instances = ServiceRegistry.instances("my-api");
        ServiceEntry blue = instances.stream().filter(e -> e.revision().equals("blue-001")).findFirst().orElseThrow();
        ServiceEntry green = instances.stream().filter(e -> e.revision().equals("green-002")).findFirst().orElseThrow();

        assertEquals(ServiceStatus.DEGRADED, blue.status());
        assertEquals(ServiceStatus.UP, green.status());
    }

    @Test
    @DisplayName("updateInstanceStatus with health details")
    void updateInstanceStatusWithDetails()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        List<ServiceHealthDetail> details = List.of(
                new ServiceHealthDetail("database", ServiceStatus.UP, Map.of("pool", "5/10")),
                new ServiceHealthDetail("cache", ServiceStatus.DOWN, Map.of("reason", "timeout"))
        );

        ServiceRegistry.updateInstanceStatus("my-api", "green-002", ServiceStatus.DEGRADED, details);

        List<ServiceEntry> instances = ServiceRegistry.instances("my-api");
        ServiceEntry green = instances.stream().filter(e -> e.revision().equals("green-002")).findFirst().orElseThrow();
        assertEquals(ServiceStatus.DEGRADED, green.status());
        assertEquals(2, green.healthDetails().size());
    }

    // ── Unregister Instance ──────────────────────────────────────────────────

    @Test
    @DisplayName("Unregister specific instance — other instance remains")
    void unregisterInstance()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        ServiceRegistry.unregisterInstance("my-api", "blue-001");

        List<ServiceEntry> instances = ServiceRegistry.instances("my-api");
        assertEquals(1, instances.size());
        assertEquals("green-002", instances.getFirst().revision());

        // Primary still works
        assertEquals("https://my-api-green.cloud.io", ServiceRegistry.url("my-api"));
    }

    @Test
    @DisplayName("Unregister all instances — service removed entirely")
    void unregisterAllInstances()
    {
        registerBlueGreen("my-api", ServiceStatus.UP, ServiceStatus.UP);

        ServiceRegistry.unregisterInstance("my-api", "blue-001");
        ServiceRegistry.unregisterInstance("my-api", "green-002");

        assertFalse(ServiceRegistry.contains("my-api"));
        assertTrue(ServiceRegistry.instances("my-api").isEmpty());
    }

    // ── healthyExternalUrl (blue/green safe) ─────────────────────────────────

    @Test
    @DisplayName("healthyExternalUrl returns URL when UP")
    void healthyExternalUrlWhenUp()
    {
        ServiceEntry entry = new ServiceEntry(
                "web", "https://web-internal.cloud.io",
                List.of("https://mysite.com"), "", "/health/ready",
                ServiceStatus.UP, Instant.now(), Map.of());

        ServiceRegistry.register(entry);

        Optional<String> url = ServiceRegistry.healthyExternalUrl("web");
        assertTrue(url.isPresent());
        assertEquals("https://mysite.com", url.get());
    }

    @Test
    @DisplayName("healthyExternalUrl returns empty when DOWN (blue/green switch)")
    void healthyExternalUrlWhenDown()
    {
        ServiceEntry entry = new ServiceEntry(
                "web", "https://web-internal.cloud.io",
                List.of("https://mysite.com"), "", "/health/ready",
                ServiceStatus.DOWN, Instant.now(), Map.of());

        ServiceRegistry.register(entry);

        Optional<String> url = ServiceRegistry.healthyExternalUrl("web");
        assertTrue(url.isEmpty());
    }

    // ── expectedStatusCode / expectedContentType ─────────────────────────────

    @Test
    @DisplayName("expectedStatusCode stored in metadata")
    void expectedStatusCodeInMetadata()
    {
        ServiceEntry entry = new ServiceEntry(
                "custom-api", "https://api.example.com",
                "/status", ServiceStatus.UNKNOWN, Instant.now(),
                Map.of("expectedStatusCode", "204"));

        ServiceRegistry.register(entry);

        ServiceEntry stored = ServiceRegistry.get("custom-api").orElseThrow();
        assertEquals("204", stored.metadata().get("expectedStatusCode"));
    }

    @Test
    @DisplayName("expectedContentType text/html stored in metadata")
    void expectedContentTypeHtmlInMetadata()
    {
        ServiceEntry entry = new ServiceEntry(
                "static-site", "https://example.com",
                "/", ServiceStatus.UNKNOWN, Instant.now(),
                Map.of("expectedContentType", "text/html"));

        ServiceRegistry.register(entry);

        ServiceEntry stored = ServiceRegistry.get("static-site").orElseThrow();
        assertEquals("text/html", stored.metadata().get("expectedContentType"));
    }

    // ── WARNING Status ───────────────────────────────────────────────────────

    @Test
    @DisplayName("WARNING status is not considered healthy")
    void warningNotHealthy()
    {
        ServiceEntry entry = new ServiceEntry(
                "quirky-service", "https://quirky.example.com",
                "/health", ServiceStatus.WARNING, Instant.now(), Map.of());

        ServiceRegistry.register(entry);

        assertFalse(ServiceRegistry.get("quirky-service").orElseThrow().isHealthy());
        assertTrue(ServiceRegistry.healthyUrl("quirky-service").isEmpty());
    }

    @Test
    @DisplayName("DEGRADED status IS considered healthy")
    void degradedIsHealthy()
    {
        ServiceEntry entry = new ServiceEntry(
                "slow-service", "https://slow.example.com",
                "/health", ServiceStatus.DEGRADED, Instant.now(), Map.of());

        ServiceRegistry.register(entry);

        assertTrue(ServiceRegistry.get("slow-service").orElseThrow().isHealthy());
        assertTrue(ServiceRegistry.healthyUrl("slow-service").isPresent());
    }

    // ── ServiceEntry revision helpers ────────────────────────────────────────

    @Test
    @DisplayName("ServiceEntry.revision() reads from metadata")
    void serviceEntryRevision()
    {
        ServiceEntry entry = new ServiceEntry(
                "svc", "https://svc.io", "/health",
                ServiceStatus.UP, Instant.now(),
                Map.of("revision", "v2.3.1"));

        assertEquals("v2.3.1", entry.revision());
        assertTrue(entry.hasRevision());
        assertEquals("svc:v2.3.1", entry.instanceId());
    }

    @Test
    @DisplayName("ServiceEntry without revision — instanceId equals name")
    void serviceEntryNoRevision()
    {
        ServiceEntry entry = new ServiceEntry(
                "svc", "https://svc.io", "/health",
                ServiceStatus.UP, Instant.now(), Map.of());

        assertEquals("", entry.revision());
        assertFalse(entry.hasRevision());
        assertEquals("svc", entry.instanceId());
    }

    // ── healthyWithExternalUrls ──────────────────────────────────────────────

    @Test
    @DisplayName("healthyWithExternalUrls filters correctly")
    void healthyWithExternalUrlsFilter()
    {
        ServiceRegistry.register(new ServiceEntry("a", "https://a.io",
                List.of("https://a.com"), "", "/h", ServiceStatus.UP, Instant.now(), Map.of()));
        ServiceRegistry.register(new ServiceEntry("b", "https://b.io",
                List.of("https://b.com"), "", "/h", ServiceStatus.DOWN, Instant.now(), Map.of()));
        ServiceRegistry.register(new ServiceEntry("c", "https://c.io",
                List.of(), "", "/h", ServiceStatus.UP, Instant.now(), Map.of()));

        Map<String, ServiceEntry> result = ServiceRegistry.healthyWithExternalUrls();
        assertEquals(1, result.size());
        assertTrue(result.containsKey("a"));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private void registerBlueGreen(String name, ServiceStatus blueStatus, ServiceStatus greenStatus)
    {
        ServiceEntry blue = new ServiceEntry(
                name, "https://my-api-blue.cloud.io",
                List.of(), "", "/health/ready",
                blueStatus, Instant.now(),
                Map.of("revision", "blue-001"));

        ServiceEntry green = new ServiceEntry(
                name, "https://my-api-green.cloud.io",
                List.of(), "", "/health/ready",
                greenStatus, Instant.now(),
                Map.of("revision", "green-002"));

        ServiceRegistry.register(blue);
        ServiceRegistry.register(green);
    }
}

