package com.guicedee.service.registry.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.service.registry.*;
import com.guicedee.service.registry.implementations.ServiceRegistryPostStartup;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the service registry captures full health component details
 * from each service's MicroProfile Health response (not just UP/DOWN).
 * <p>
 * Verifies:
 * <ul>
 *   <li>Health details are stored on {@link ServiceEntry#healthDetails()}</li>
 *   <li>Individual component statuses are accessible</li>
 *   <li>{@link ServiceRegistry#healthDetails(String)} provides convenience access</li>
 *   <li>Health details update on each health check cycle</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceHealthDetailsTest
{
    @BeforeAll
    void setUp()
    {
        System.setProperty("CONTAINER_APP_NAME", "guicedee-website");
        System.setProperty("CONTAINER_APP_ENV_DNS_SUFFIX", "whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("CONTAINER_APP_PORT", "80");
        System.setProperty("CONTAINER_APP_REVISION", "guicedee-website--0000004");
        System.setProperty("CONTAINER_APP_HOSTNAME", "guicedee-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("AZURE_REGION", "ukwest");

        IGuiceContext.instance().inject();
    }

    @AfterAll
    void tearDown()
    {
        ServiceRegistryPostStartup.stop();
        IGuiceContext.instance().destroy();
        ServiceRegistry.clear();
        System.clearProperty("CONTAINER_APP_NAME");
        System.clearProperty("CONTAINER_APP_ENV_DNS_SUFFIX");
        System.clearProperty("CONTAINER_APP_PORT");
        System.clearProperty("CONTAINER_APP_REVISION");
        System.clearProperty("CONTAINER_APP_HOSTNAME");
        System.clearProperty("AZURE_REGION");
    }

    @Test
    @Order(1)
    void testHealthDetailsStoredOnServiceEntry()
    {
        // Programmatically update a service with health details
        ServiceRegistry.register(new ServiceEntry(
                "test-service",
                "http://localhost:9999",
                "/health",
                ServiceStatus.UNKNOWN,
                Instant.now(),
                Map.of()
        ));

        List<ServiceHealthDetail> details = List.of(
                new ServiceHealthDetail("database", ServiceStatus.UP, Map.of("pool", "10/20")),
                new ServiceHealthDetail("disk-space", ServiceStatus.UP, Map.of("free", "50GB")),
                new ServiceHealthDetail("external-api", ServiceStatus.DOWN, Map.of("error", "timeout"))
        );

        ServiceRegistry.updateStatus("test-service", ServiceStatus.DEGRADED, details);

        Optional<ServiceEntry> entry = ServiceRegistry.get("test-service");
        assertTrue(entry.isPresent());
        assertEquals(ServiceStatus.DEGRADED, entry.get().status());
        assertEquals(3, entry.get().healthDetails().size());

        // Verify individual details
        ServiceHealthDetail db = entry.get().healthDetails().get(0);
        assertEquals("database", db.name());
        assertEquals(ServiceStatus.UP, db.status());
        assertTrue(db.isUp());
        assertEquals("10/20", db.data().get("pool"));

        ServiceHealthDetail extApi = entry.get().healthDetails().get(2);
        assertEquals("external-api", extApi.name());
        assertEquals(ServiceStatus.DOWN, extApi.status());
        assertFalse(extApi.isUp());
        assertEquals("timeout", extApi.data().get("error"));

        System.out.println("✅ Health details stored on ServiceEntry:");
        for (ServiceHealthDetail d : entry.get().healthDetails())
        {
            String icon = d.isUp() ? "🟢" : "🔴";
            System.out.println("   " + icon + " " + d.name() + " → " + d.status() + " " + d.data());
        }
    }

    @Test
    @Order(2)
    void testHealthDetailsConvenienceMethod()
    {
        // Uses ServiceRegistry.healthDetails(name)
        List<ServiceHealthDetail> details = ServiceRegistry.healthDetails("test-service");
        assertFalse(details.isEmpty());
        assertEquals(3, details.size());

        // Non-existent service returns empty
        List<ServiceHealthDetail> none = ServiceRegistry.healthDetails("non-existent");
        assertTrue(none.isEmpty());
    }

    @Test
    @Order(3)
    void testHealthDetailsPreservedOnStatusUpdate()
    {
        // Update status without details — details should be preserved
        List<ServiceHealthDetail> before = ServiceRegistry.healthDetails("test-service");
        assertEquals(3, before.size());

        ServiceRegistry.updateStatus("test-service", ServiceStatus.UP);

        // After simple status update, details are preserved from previous withStatus call
        Optional<ServiceEntry> entry = ServiceRegistry.get("test-service");
        assertTrue(entry.isPresent());
        assertEquals(ServiceStatus.UP, entry.get().status());
        // withStatus preserves existing healthDetails
        assertEquals(3, entry.get().healthDetails().size());
    }

    @Test
    @Order(4)
    void testHealthDetailsOverwrittenOnNewCheck()
    {
        // Simulate a new health check cycle with updated details
        List<ServiceHealthDetail> newDetails = List.of(
                new ServiceHealthDetail("database", ServiceStatus.UP, Map.of("pool", "15/20")),
                new ServiceHealthDetail("disk-space", ServiceStatus.UP, Map.of("free", "45GB"))
        );

        ServiceRegistry.updateStatus("test-service", ServiceStatus.UP, newDetails);

        List<ServiceHealthDetail> current = ServiceRegistry.healthDetails("test-service");
        assertEquals(2, current.size());
        assertEquals("15/20", current.get(0).data().get("pool"));
    }

    @Test
    @Order(5)
    void testEmptyHealthDetailsForSimpleEndpoints()
    {
        // Services that return a simple 200 without JSON body have empty details
        ServiceRegistry.register(new ServiceEntry(
                "simple-service",
                "http://localhost:8080",
                "/",
                ServiceStatus.UP,
                Instant.now(),
                Map.of()
        ));

        List<ServiceHealthDetail> details = ServiceRegistry.healthDetails("simple-service");
        assertTrue(details.isEmpty());
    }

    @Test
    @Order(6)
    void testLiveHealthDetailsFromAzureServices() throws Exception
    {
        // Wait for the health check cycle to complete against real Azure services
        System.out.println("⏳ Waiting for health check cycle (6s)...");
        Thread.sleep(6000);

        System.out.println("\n✅ Health details from live services:");
        for (var entry : ServiceRegistry.all().entrySet())
        {
            ServiceEntry svc = entry.getValue();
            String icon = svc.isHealthy() ? "🟢" : svc.status() == ServiceStatus.UNKNOWN ? "⚪" : "🔴";
            System.out.println("   " + icon + " " + svc.name() + " → " + svc.status());
            if (!svc.healthDetails().isEmpty())
            {
                for (ServiceHealthDetail d : svc.healthDetails())
                {
                    String dIcon = d.isUp() ? "  🟢" : "  🔴";
                    System.out.println("      " + dIcon + " " + d.name() + " → " + d.status() + " " + d.data());
                }
            }
            else
            {
                System.out.println("      (no component details — simple health endpoint)");
            }
        }

        // At least one Azure service should have responded
        boolean anyHealthy = ServiceRegistry.all().values().stream().anyMatch(ServiceEntry::isHealthy);
        assertTrue(anyHealthy, "At least one service should be healthy");
    }
}

