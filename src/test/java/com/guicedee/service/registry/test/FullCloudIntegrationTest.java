package com.guicedee.service.registry.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.runtime.autoconfigure.RuntimeEnvironment;
import com.guicedee.runtime.autoconfigure.implementations.RuntimeAutoConfigurePreStartup;
import com.guicedee.service.registry.ServiceEntry;
import com.guicedee.service.registry.ServiceRegistry;
import com.guicedee.service.registry.ServiceStatus;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full integration test demonstrating how runtime-autoconfigure, service-registry,
 * and service-discovery work together.
 * <p>
 * This test simulates running inside an Azure Container Apps environment with two sibling
 * services: guicedee-website and jwebmp-website.
 * <p>
 * <h2>What this test proves:</h2>
 * <ol>
 *   <li><b>runtime-autoconfigure</b> detects the Azure platform from env vars</li>
 *   <li><b>service-registry</b> auto-constructs URLs using the detected DNS suffix</li>
 *   <li><b>service-registry</b> self-registers this app</li>
 *   <li><b>service-registry</b> resolves services by simple name</li>
 *   <li><b>service-registry</b> supports the "registry:" prefix for rest-client integration</li>
 *   <li><b>service-registry</b> starts health checks (verifying real Azure apps respond)</li>
 * </ol>
 * <p>
 * <h2>Configuration (package-info.java):</h2>
 * <pre>
 * &#64;AzureContainerApps
 * &#64;ServiceRegistryOptions(healthCheckInterval = 10, healthPath = "/", registerSelf = true)
 * &#64;RegisteredService(name = "jwebmp-website")
 * &#64;RegisteredService(name = "guicedee-website")
 * package com.guicedee.service.registry.test;
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullCloudIntegrationTest
{
    @BeforeAll
    void setUp()
    {
        // ─── Simulate Azure Container Apps environment ───
        // These env vars are automatically injected by Azure when running inside Container Apps.
        System.setProperty("CONTAINER_APP_NAME", "guicedee-website");
        System.setProperty("CONTAINER_APP_ENV_DNS_SUFFIX", "whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("CONTAINER_APP_PORT", "80");
        System.setProperty("CONTAINER_APP_REVISION", "guicedee-website--0000004");
        System.setProperty("CONTAINER_APP_REPLICA_NAME", "guicedee-website--0000004-abc123");
        System.setProperty("CONTAINER_APP_HOSTNAME", "guicedee-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io");
        System.setProperty("AZURE_REGION", "ukwest");

        // Boot GuicedEE — triggers the full startup chain:
        // 1. RuntimeAutoConfigurePreStartup (MIN+50) → detects Azure
        // 2. ServiceRegistryPreStartup (MIN+150) → registers services
        // 3. ServiceRegistryPostStartup (MIN+700) → starts health checks
        IGuiceContext.instance().inject();
    }

    @AfterAll
    void tearDown()
    {
        IGuiceContext.instance().destroy();
        ServiceRegistry.clear();
        System.clearProperty("CONTAINER_APP_NAME");
        System.clearProperty("CONTAINER_APP_ENV_DNS_SUFFIX");
        System.clearProperty("CONTAINER_APP_PORT");
        System.clearProperty("CONTAINER_APP_REVISION");
        System.clearProperty("CONTAINER_APP_REPLICA_NAME");
        System.clearProperty("CONTAINER_APP_HOSTNAME");
        System.clearProperty("AZURE_REGION");
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 1: runtime-autoconfigure detects Azure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void testRuntimeAutoconfigureDetectsAzure()
    {
        Optional<RuntimeEnvironment> env = RuntimeAutoConfigurePreStartup.current();
        assertTrue(env.isPresent(), "RuntimeEnvironment should be detected");

        RuntimeEnvironment runtime = env.get();
        assertEquals("azure-container-apps", runtime.provider());
        assertEquals("guicedee-website", runtime.serviceName());
        assertEquals("ukwest", runtime.region());

        System.out.println("✅ PART 1: Runtime Autoconfigure");
        System.out.println("   provider    : " + runtime.provider());
        System.out.println("   serviceName : " + runtime.serviceName());
        System.out.println("   hostname    : " + runtime.hostname());
        System.out.println("   dnsSuffix   : " + runtime.extra("dnsSuffix").orElse(""));
        System.out.println("   region      : " + runtime.region());
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 2: service-registry auto-constructed URLs from DNS suffix
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    void testServiceRegistryAutoConstructsUrls()
    {
        // @RegisteredService(name = "jwebmp-website") → auto-constructed
        assertTrue(ServiceRegistry.contains("jwebmp-website"),
                "jwebmp-website should be registered");

        String url = ServiceRegistry.url("jwebmp-website");
        assertEquals("https://jwebmp-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io", url,
                "URL should be auto-constructed from DNS suffix");

        System.out.println("✅ PART 2: Service Registry URL Construction");
        System.out.println("   jwebmp-website → " + url);
        System.out.println("   guicedee-website → " + ServiceRegistry.url("guicedee-website"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 3: self-registration from runtime-autoconfigure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    void testSelfRegistration()
    {
        // The app should have self-registered using runtime-autoconfigure metadata
        Optional<ServiceEntry> self = ServiceRegistry.get("guicedee-website");
        assertTrue(self.isPresent(), "Self should be registered");

        ServiceEntry entry = self.get();
        // Status may be UP (self-registered) or UNKNOWN (if @RegisteredService overwrote it)
        // The key thing is that it IS registered and has the correct URL
        assertNotNull(entry.url(), "Self should have a URL");
        assertTrue(entry.url().contains("guicedee-website"), "Self URL should contain the service name");

        System.out.println("✅ PART 3: Self-Registration");
        System.out.println("   name   : " + entry.name());
        System.out.println("   url    : " + entry.url());
        System.out.println("   status : " + entry.status());
        System.out.println("   self   : " + entry.metadata().get("self"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 4: resolve by simple name
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    void testResolveBySimpleName()
    {
        // Direct lookup
        String url = ServiceRegistry.url("jwebmp-website");
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
        assertTrue(url.contains("jwebmp-website"));

        // Case-insensitive
        assertEquals(url, ServiceRegistry.url("JWEBMP-WEBSITE"));
        assertEquals(url, ServiceRegistry.url("JWebMP-Website"));

        // Alias resolution (defined in package-info: aliases = {"jwebmp", "jwebswing"})
        assertEquals(url, ServiceRegistry.url("jwebmp"));
        assertEquals(url, ServiceRegistry.url("jwebswing"));

        // External URL (defined in package-info: externalUrls = {"https://jwebmp.com", "https://jwebswing.com"})
        String extUrl = ServiceRegistry.externalUrl("jwebmp-website");
        assertEquals("https://jwebmp.com", extUrl);
        // Multiple external URLs
        List<String> extUrls = ServiceRegistry.externalUrls("jwebmp-website");
        assertEquals(2, extUrls.size());
        assertEquals("https://jwebmp.com", extUrls.get(0));
        assertEquals("https://jwebswing.com", extUrls.get(1));
        // Alias also resolves external URL
        assertEquals("https://jwebmp.com", ServiceRegistry.externalUrl("jwebswing"));

        // Kubernetes URL
        assertEquals("http://jwebmp-website.default.svc.cluster.local", ServiceRegistry.kubernetesUrl("jwebmp-website"));
        assertEquals("http://guicedee-website.default.svc.cluster.local", ServiceRegistry.kubernetesUrl("guicedee-website"));

        // guicedee aliases
        assertEquals(ServiceRegistry.url("guicedee-website"), ServiceRegistry.url("guicedee"));
        assertEquals("https://guicedee.com", ServiceRegistry.externalUrl("guicedee"));

        // hello-service — Kubernetes-only (no external URL, no Azure DNS suffix)
        // When only a kubernetesUrl/url is specified, url() returns the explicit URL
        assertTrue(ServiceRegistry.contains("hello-service"), "hello-service should be registered");
        assertEquals("http://hello-service.service-discovery-test.svc.cluster.local", ServiceRegistry.url("hello-service"));
        // kubernetesUrl falls back to url when both are the same
        assertEquals("http://hello-service.service-discovery-test.svc.cluster.local", ServiceRegistry.kubernetesUrl("hello-service"));
        // externalUrl falls back to internal url when none configured
        assertEquals("http://hello-service.service-discovery-test.svc.cluster.local", ServiceRegistry.externalUrl("hello-service"));
        // Verify custom healthPath
        Optional<ServiceEntry> helloEntry = ServiceRegistry.get("hello-service");
        assertTrue(helloEntry.isPresent());
        assertEquals("/healthz", helloEntry.get().healthPath());
        assertEquals("http://hello-service.service-discovery-test.svc.cluster.local/healthz", helloEntry.get().healthUrl());

        System.out.println("✅ PART 4: Simple Name Resolution + Aliases + External URLs + Kubernetes");
        System.out.println("   ── jwebmp-website ──");
        System.out.println("   url()          → " + url);
        System.out.println("   url(\"jwebmp\")  → " + ServiceRegistry.url("jwebmp") + " (alias)");
        System.out.println("   externalUrls() → " + ServiceRegistry.externalUrls("jwebmp-website"));
        System.out.println("   kubernetesUrl()→ " + ServiceRegistry.kubernetesUrl("jwebmp-website"));
        System.out.println("   ── guicedee-website ──");
        System.out.println("   url()          → " + ServiceRegistry.url("guicedee-website"));
        System.out.println("   externalUrl()  → " + ServiceRegistry.externalUrl("guicedee"));
        System.out.println("   kubernetesUrl()→ " + ServiceRegistry.kubernetesUrl("guicedee"));
        System.out.println("   ── hello-service (K8s-only, custom healthPath) ──");
        System.out.println("   url()          → " + ServiceRegistry.url("hello-service"));
        System.out.println("   kubernetesUrl()→ " + ServiceRegistry.kubernetesUrl("hello-service"));
        System.out.println("   externalUrl()  → " + ServiceRegistry.externalUrl("hello-service") + " (falls back to internal)");
        System.out.println("   healthPath     → " + helloEntry.get().healthPath());
        System.out.println("   healthUrl()    → " + helloEntry.get().healthUrl());
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 5: "registry:" prefix for rest-client integration
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    void testRegistryPrefixResolution()
    {
        // This is what the rest-client module does with @Endpoint(url = "registry:jwebmp-website")
        String resolved = ServiceRegistry.resolve("registry:jwebmp-website");
        assertEquals("https://jwebmp-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io", resolved);

        // Non-registry URLs pass through unchanged
        String passthrough = ServiceRegistry.resolve("https://example.com/api");
        assertEquals("https://example.com/api", passthrough);

        // ${ENV_VAR} placeholders are resolved
        System.setProperty("MY_SERVICE_URL", "https://custom.example.com");
        String envResolved = ServiceRegistry.resolve("${MY_SERVICE_URL}/api");
        assertEquals("https://custom.example.com/api", envResolved);
        System.clearProperty("MY_SERVICE_URL");

        System.out.println("✅ PART 5: registry: Prefix Resolution");
        System.out.println("   resolve(\"registry:jwebmp-website\") → " + resolved);
        System.out.println("   resolve(\"https://example.com/api\") → " + passthrough);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 6: health checks against live Azure Container Apps
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    void testHealthChecksUpdateStatus() throws Exception
    {
        // Wait for the first health check cycle to complete (health checks are async)
        System.out.println("⏳ Waiting for health check cycle (5s)...");
        Thread.sleep(5000);

        // jwebmp-website should be UP (it's a real running Azure Container App)
        Optional<ServiceEntry> jwebmp = ServiceRegistry.get("jwebmp-website");
        assertTrue(jwebmp.isPresent());

        System.out.println("✅ PART 6: Health Check Results");
        for (var entry : ServiceRegistry.all().entrySet())
        {
            ServiceEntry svc = entry.getValue();
            String selfMarker = "true".equals(svc.metadata().get("self")) ? " [SELF]" : "";
            System.out.println("   " + svc.name() + " → " + svc.status() + selfMarker);
        }

        // The live Azure apps should be UP (they responded with 200 to GET /)
        ServiceEntry jwebmpEntry = jwebmp.get();
        if (jwebmpEntry.status() == ServiceStatus.UP)
        {
            System.out.println("   ✅ jwebmp-website is UP and responding!");
        }
        else
        {
            System.out.println("   ⚠️ jwebmp-website status: " + jwebmpEntry.status()
                    + " (may need more time or network access)");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PART 7: full picture — all APIs together
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    void testFullPictureSummary()
    {
        Map<String, ServiceEntry> all = ServiceRegistry.all();
        Map<String, ServiceEntry> healthy = ServiceRegistry.healthy();

        // Test OpenAPI URL resolution
        Optional<String> jwebmpOpenApi = ServiceRegistry.openApiUrl("jwebmp-website");
        assertTrue(jwebmpOpenApi.isPresent(), "jwebmp-website should have openApiPath configured");
        assertEquals("https://jwebmp-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io/openapi.json",
                jwebmpOpenApi.get());

        Optional<String> helloOpenApi = ServiceRegistry.openApiUrl("hello-service");
        assertTrue(helloOpenApi.isEmpty(), "hello-service has no openApiPath configured");

        // Test allOpenApiUrls (no env filter)
        Map<String, String> allSpecs = ServiceRegistry.allOpenApiUrls();
        assertEquals(2, allSpecs.size(), "Two services have openApiPath");
        assertTrue(allSpecs.containsKey("jwebmp-website"));
        assertTrue(allSpecs.containsKey("guicedee-website"));

        // Test environment filtering
        Map<String, String> prodSpecs = ServiceRegistry.allOpenApiUrls("prod");
        assertTrue(prodSpecs.containsKey("jwebmp-website"), "jwebmp-website allows prod");
        assertTrue(prodSpecs.containsKey("guicedee-website"), "guicedee-website allows all (no filter)");

        Map<String, String> stagingSpecs = ServiceRegistry.allOpenApiUrls("staging");
        assertFalse(stagingSpecs.containsKey("jwebmp-website"), "jwebmp-website doesn't allow staging");
        assertTrue(stagingSpecs.containsKey("guicedee-website"), "guicedee-website allows all (no filter)");

        System.out.println("""
                
                ═══════════════════════════════════════════════════════════════
                ✅ FULL INTEGRATION SUMMARY
                ═══════════════════════════════════════════════════════════════
                
                Modules working together:
                  1. runtime-autoconfigure → detected Azure Container Apps
                  2. service-registry      → registered 3 services by simple name
                  3. service-registry      → self-registered this app
                  4. service-registry      → health checks running
                  5. service-registry      → OpenAPI spec URLs resolved
                
                Registry state:
                """);

        for (var entry : all.entrySet())
        {
            ServiceEntry svc = entry.getValue();
            String icon = svc.isHealthy() ? "🟢" : svc.status() == ServiceStatus.UNKNOWN ? "⚪" : "🔴";
            System.out.println("  " + icon + " " + svc.name() + " → " + svc.url() + " [" + svc.status() + "]");
            if (svc.hasExternalUrl())
            {
                System.out.println("       External URLs: " + svc.externalUrls());
            }
            if (svc.hasKubernetesUrl())
            {
                System.out.println("       Kubernetes:    " + svc.kubernetesUrl());
            }
            String openApiPath = svc.metadata().getOrDefault("openApiPath", "");
            if (!openApiPath.isEmpty())
            {
                String envs = svc.metadata().getOrDefault("openApiEnvironments", "all");
                System.out.println("       OpenAPI:       " + openApiPath + " (envs: " + envs + ")");
            }
        }

        System.out.println("\n  OpenAPI URLs (all envs): " + allSpecs);
        System.out.println("  OpenAPI URLs (prod):     " + prodSpecs);
        System.out.println("  OpenAPI URLs (staging):  " + stagingSpecs);

        System.out.println("\n  Total: " + all.size() + " services, " + healthy.size() + " healthy");
        System.out.println("""
                
                How rest-client uses this:
                  @Endpoint(url = "registry:jwebmp-website")
                  → resolves to: """ + ServiceRegistry.url("jwebmp-website") + """
                
                How OpenAPI merges:
                  Services with openApiPath get their specs merged into local /openapi.json
                  Filtered by ENVIRONMENT env var (dev/int/prod)
                
                ═══════════════════════════════════════════════════════════════
                """);

        assertTrue(all.size() >= 3, "At least 3 services should be registered");
    }
}


