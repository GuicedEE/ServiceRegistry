package com.guicedee.service.registry.implementations;

import com.guicedee.client.services.lifecycle.IGuicePostStartup;
import com.guicedee.client.services.lifecycle.IGuicePostStartup;
import com.guicedee.service.registry.ServiceEntry;
import com.guicedee.service.registry.ServiceHealthDetail;
import com.guicedee.service.registry.ServiceRegistry;
import com.guicedee.service.registry.ServiceRegistryOptions;
import com.guicedee.service.registry.ServiceStatus;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-startup hook that starts the periodic health check timer for all registered services.
 * Uses Vert.x WebClient to check each service's health endpoint.
 * <p>
 * Features:
 * <ul>
 *     <li>Periodic health polling with configurable interval</li>
 *     <li>Exponential backoff for DOWN services (reduces polling pressure)</li>
 *     <li>MicroProfile Health JSON response parsing into {@link ServiceHealthDetail} entries</li>
 *     <li>Status change event firing via {@link com.guicedee.service.registry.IServiceStatusChangeListener}</li>
 * </ul>
 * <p>
 * When a service returns a MicroProfile Health-style JSON response, the individual check
 * components are parsed and stored as {@link ServiceHealthDetail} entries on the {@link com.guicedee.service.registry.ServiceEntry}.
 */
@Log4j2
public class ServiceRegistryPostStartup implements IGuicePostStartup<ServiceRegistryPostStartup>
{
    private static WebClient webClient;
    private static long timerId = -1;

    /** Tracks consecutive failure counts per service for exponential backoff */
    private static final Map<String, Integer> FAILURE_COUNTS = new ConcurrentHashMap<>();
    /** Maximum backoff multiplier (max skip = 2^MAX_BACKOFF_EXPONENT intervals) */
    private static final int MAX_BACKOFF_EXPONENT = 5; // max 32x interval
    /** Tracks how many intervals have been skipped per service */
    private static final Map<String, Integer> SKIP_COUNTERS = new ConcurrentHashMap<>();

    @Override
    public List<Uni<Boolean>> postLoad()
    {
        ServiceRegistryOptions options = ServiceRegistryPreStartup.getOptions();
        int interval = options != null ? options.healthCheckInterval() : 30;
        int timeout = options != null ? options.healthCheckTimeout() : 5000;

        if (interval <= 0 || ServiceRegistry.all().isEmpty())
        {
            log.debug("Health checks disabled or no services registered");
            return List.of(Uni.createFrom().item(true));
        }

        Vertx vertx = VertXPreStartup.getVertx();
        if (vertx == null)
        {
            log.warn("⚠️ Vertx not available — cannot start health checks");
            return List.of(Uni.createFrom().item(true));
        }

        webClient = WebClient.create(vertx, new WebClientOptions()
                .setConnectTimeout(timeout)
                .setTrustAll(true)
                .setFollowRedirects(true));

        // Initial health check
        checkAllServices();

        // Schedule periodic checks
        timerId = vertx.setPeriodic(interval * 1000L, id -> checkAllServices());

        log.info("🏥 Health checks started — checking {} services every {}s",
                ServiceRegistry.all().size(), interval);

        return List.of(Uni.createFrom().item(true));
    }

    private void checkAllServices()
    {
        // Check all primary service entries
        for (var entry : ServiceRegistry.all().entrySet())
        {
            var service = entry.getValue();

            // Skip self
            if ("true".equals(service.metadata().get("self")))
            {
                continue;
            }

            // If this service has instances, check each instance individually
            List<ServiceEntry> instances = ServiceRegistry.instances(service.name());
            if (!instances.isEmpty())
            {
                for (ServiceEntry instance : instances)
                {
                    checkServiceInstance(instance, instance.instanceId());
                }
                continue; // Don't also check the primary entry
            }

            // No instances — check the primary entry directly
            checkServiceInstance(service, entry.getKey());
        }
    }

    private void checkServiceInstance(ServiceEntry service, String key)
    {
        // Exponential backoff: skip checks for DOWN services based on failure count
        int failures = FAILURE_COUNTS.getOrDefault(key, 0);
        if (failures > 0)
        {
            int backoffMultiplier = Math.min(1 << Math.min(failures, MAX_BACKOFF_EXPONENT), 32);
            int skipped = SKIP_COUNTERS.getOrDefault(key, 0);
            if (skipped < backoffMultiplier - 1)
            {
                SKIP_COUNTERS.put(key, skipped + 1);
                return; // Skip this check cycle
            }
            SKIP_COUNTERS.put(key, 0); // Reset skip counter, do the check
        }

        final boolean hasRevision = service.hasRevision();
        final String revision = service.revision();

        try
        {
            String healthUrl = service.healthUrl();
            var uri = java.net.URI.create(healthUrl);
            int port = uri.getPort() > 0 ? uri.getPort()
                    : (uri.getScheme().equals("https") ? 443 : 80);
            boolean ssl = uri.getScheme().equals("https");

            // Read per-service expectations from metadata
            int expectedStatus = 200;
            String expectedContentType = "application/json";
            String statusMeta = service.metadata().get("expectedStatusCode");
            if (statusMeta != null && !statusMeta.isEmpty())
            {
                try { expectedStatus = Integer.parseInt(statusMeta); } catch (NumberFormatException ignored) {}
            }
            String contentMeta = service.metadata().get("expectedContentType");
            if (contentMeta != null && !contentMeta.isEmpty())
            {
                expectedContentType = contentMeta;
            }

            final int expectedStatusCode = expectedStatus;
            final boolean expectsJson = "application/json".equals(expectedContentType);

            webClient.get(port, uri.getHost(), uri.getPath())
                    .ssl(ssl)
                    .send()
                    .onSuccess(response -> {
                        int statusCode = response.statusCode();

                        if (expectsJson)
                        {
                            // MicroProfile Health JSON mode — parse deep details
                            List<ServiceHealthDetail> details = parseHealthDetails(response.bodyAsString());
                            boolean hasWarning = details.stream()
                                    .anyMatch(d -> d.status() == ServiceStatus.WARNING);

                            if (statusCode == expectedStatusCode || (statusCode >= 200 && statusCode < 400))
                            {
                                if (hasWarning)
                                {
                                    updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.WARNING, details);
                                    log.debug("⚠️ {} → HTTP {} but response is not valid health JSON",
                                            service.name(), statusCode);
                                }
                                else
                                {
                                    updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.UP, details);
                                    FAILURE_COUNTS.remove(key);
                                    SKIP_COUNTERS.remove(key);
                                }
                            }
                            else if (statusCode == 503)
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DEGRADED, details);
                                FAILURE_COUNTS.remove(key);
                                SKIP_COUNTERS.remove(key);
                            }
                            else
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DOWN, details);
                                FAILURE_COUNTS.merge(key, 1, Integer::sum);
                                log.debug("🔴 {} → HTTP {} (failures: {})", key, statusCode, FAILURE_COUNTS.get(key));
                            }
                        }
                        else
                        {
                            // Non-JSON mode — status only, no deep details
                            if (statusCode == expectedStatusCode)
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.UP, List.of());
                                FAILURE_COUNTS.remove(key);
                                SKIP_COUNTERS.remove(key);
                            }
                            else if (statusCode >= 200 && statusCode < 400)
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.WARNING, List.of());
                                log.debug("⚠️ {} → HTTP {} (expected {})", key, statusCode, expectedStatusCode);
                            }
                            else if (statusCode == 503)
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DEGRADED, List.of());
                                FAILURE_COUNTS.remove(key);
                                SKIP_COUNTERS.remove(key);
                            }
                            else
                            {
                                updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DOWN, List.of());
                                FAILURE_COUNTS.merge(key, 1, Integer::sum);
                                log.debug("🔴 {} → HTTP {} (failures: {})", key, statusCode, FAILURE_COUNTS.get(key));
                            }
                        }
                    })
                    .onFailure(err -> {
                        updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DOWN, List.of());
                        FAILURE_COUNTS.merge(key, 1, Integer::sum);
                        log.debug("🔴 {} → {} (failures: {})", key, err.getMessage(), FAILURE_COUNTS.get(key));
                    });
        }
        catch (Exception e)
        {
            updateServiceStatus(service.name(), hasRevision, revision, ServiceStatus.DOWN, List.of());
            FAILURE_COUNTS.merge(key, 1, Integer::sum);
        }
    }

    /**
     * Routes status updates to either instance-aware or simple update depending on whether the service has a revision.
     */
    private void updateServiceStatus(String name, boolean hasRevision, String revision, ServiceStatus status, List<ServiceHealthDetail> details)
    {
        if (hasRevision)
        {
            ServiceRegistry.updateInstanceStatus(name, revision, status, details);
        }
        else if (details.isEmpty())
        {
            ServiceRegistry.updateStatus(name, status);
        }
        else
        {
            ServiceRegistry.updateStatus(name, status, details);
        }
    }

    /**
     * Parses a MicroProfile Health JSON response body into a list of {@link ServiceHealthDetail}.
     * <p>
     * Expected format:
     * <pre>{@code
     * {
     *   "status": "UP",
     *   "checks": [
     *     { "name": "database", "status": "UP", "data": {"pool": "10/20"} },
     *     { "name": "disk", "status": "DOWN", "data": {"free": "100MB"} }
     *   ]
     * }
     * }</pre>
     * <p>
     * If the response body appears to be HTML (starts with {@code <}), a single
     * {@link ServiceHealthDetail} with status {@link ServiceStatus#WARNING} is returned
     * indicating the endpoint returned HTML instead of the expected JSON.
     *
     * @param body the JSON response body, may be null
     * @return list of parsed health details, or empty list if unparseable
     */
    private List<ServiceHealthDetail> parseHealthDetails(String body)
    {
        if (body == null || body.isBlank())
        {
            return List.of();
        }

        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<"))
        {
            log.warn("⚠️ Health endpoint returned HTML instead of expected JSON — is the health path correct?");
            return List.of(new ServiceHealthDetail(
                    "invalid-content-type",
                    ServiceStatus.WARNING,
                    Map.of("reason", "Expected JSON but received HTML response",
                           "hint", "Verify the healthPath points to a MicroProfile Health endpoint")
            ));
        }

        try
        {
            JsonObject json = new JsonObject(body);
            JsonArray checks = json.getJsonArray("checks");
            if (checks == null || checks.isEmpty())
            {
                return List.of();
            }
            List<ServiceHealthDetail> details = new ArrayList<>(checks.size());
            for (int i = 0; i < checks.size(); i++)
            {
                JsonObject check = checks.getJsonObject(i);
                String name = check.getString("name", check.getString("id", "unknown"));
                String statusStr = check.getString("status", "DOWN");
                ServiceStatus status = "UP".equalsIgnoreCase(statusStr) ? ServiceStatus.UP : ServiceStatus.DOWN;
                Map<String, Object> data = Map.of();
                if (check.getJsonObject("data") != null)
                {
                    data = new LinkedHashMap<>(check.getJsonObject("data").getMap());
                }
                details.add(new ServiceHealthDetail(name, status, data));
            }
            return details;
        }
        catch (Exception e)
        {
            log.debug("Could not parse health response as MicroProfile Health JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Stops health checks, closes the WebClient, and clears backoff state.
     */
    public static void stop()
    {
        if (timerId >= 0)
        {
            Vertx vertx = VertXPreStartup.getVertx();
            if (vertx != null) vertx.cancelTimer(timerId);
            timerId = -1;
        }
        if (webClient != null)
        {
            webClient.close();
            webClient = null;
        }
        FAILURE_COUNTS.clear();
        SKIP_COUNTERS.clear();
    }

    @Override
    public Integer sortOrder()
    {
        // After service-discovery post startup (MIN+600)
        return Integer.MIN_VALUE + 700;
    }
}
