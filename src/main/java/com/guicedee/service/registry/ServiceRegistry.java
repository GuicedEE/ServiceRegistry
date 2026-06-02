package com.guicedee.service.registry;

import com.guicedee.client.Environment;
import lombok.extern.log4j.Log4j2;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Central service registry — maintains a map of named services with their URLs and health status.
 * <p>
 * Services can be registered declaratively via {@link RegisteredService} annotations,
 * programmatically via {@link #register(ServiceEntry)}, or automatically via
 * {@link IServiceRegistryProvider} SPIs and runtime-autoconfigure self-registration.
 * <p>
 * <h2>Usage with rest-client:</h2>
 * When service-registry is on the classpath, {@code @Endpoint} URLs can use the {@code registry:} prefix:
 * <pre>{@code
 * @Endpoint(url = "registry:jwebmp-website")
 * @Named("jwebmp-api")
 * private RestClient<Void, Response> client;
 * }</pre>
 */
@Log4j2
public class ServiceRegistry
{
    private static final Map<String, ServiceEntry> SERVICES = new ConcurrentHashMap<>();
    /** Alias → canonical name mapping */
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();
    /** Registered status change listeners */
    private static final List<IServiceStatusChangeListener<?>> LISTENERS = new CopyOnWriteArrayList<>();
    /** Multi-instance tracking: service name → list of instances (for blue/green deployments) */
    private static final Map<String, List<ServiceEntry>> INSTANCES = new ConcurrentHashMap<>();

    private ServiceRegistry() {}

    /**
     * Adds a status change listener programmatically.
     * Listeners added this way supplement those discovered via ServiceLoader.
     */
    public static void addStatusChangeListener(IServiceStatusChangeListener<?> listener)
    {
        LISTENERS.add(listener);
    }

    /**
     * Removes a previously added status change listener.
     */
    public static void removeStatusChangeListener(IServiceStatusChangeListener<?> listener)
    {
        LISTENERS.remove(listener);
    }

    /**
     * Gets a service entry by name (or alias).
     */
    public static Optional<ServiceEntry> get(String name)
    {
        String key = name.toLowerCase().trim();
        // Check alias first
        String canonical = ALIASES.getOrDefault(key, key);
        return Optional.ofNullable(SERVICES.get(canonical));
    }

    /**
     * Gets the internal/primary URL for a named service. Throws if not found.
     */
    public static String url(String name)
    {
        return get(name).map(ServiceEntry::url)
                .orElseThrow(() -> new NoSuchElementException(
                        "Service '" + name + "' not found in registry. Known services: " + SERVICES.keySet()));
    }

    /**
     * Gets the external/public URL for a named service (custom domain).
     * Falls back to internal URL if no external URL is configured.
     */
    public static String externalUrl(String name)
    {
        return get(name)
                .map(e -> e.hasExternalUrl() ? e.externalUrl() : e.url())
                .orElseThrow(() -> new NoSuchElementException(
                        "Service '" + name + "' not found in registry. Known services: " + SERVICES.keySet()));
    }

    /**
     * Gets all external/public URLs for a named service.
     * Returns empty list if no external URLs are configured.
     */
    public static List<String> externalUrls(String name)
    {
        return get(name)
                .map(ServiceEntry::externalUrls)
                .orElseThrow(() -> new NoSuchElementException(
                        "Service '" + name + "' not found in registry. Known services: " + SERVICES.keySet()));
    }

    /**
     * Gets the Kubernetes internal cluster URL for a named service.
     * Falls back to internal URL if no Kubernetes URL is configured.
     */
    public static String kubernetesUrl(String name)
    {
        return get(name)
                .map(e -> e.hasKubernetesUrl() ? e.kubernetesUrl() : e.url())
                .orElseThrow(() -> new NoSuchElementException(
                        "Service '" + name + "' not found in registry. Known services: " + SERVICES.keySet()));
    }

    /**
     * Gets the URL for a named service, only if it's healthy.
     */
    public static Optional<String> healthyUrl(String name)
    {
        return get(name).filter(ServiceEntry::isHealthy).map(ServiceEntry::url);
    }

    /**
     * Gets the external/public URL for a named service, only if it's healthy.
     * Falls back to internal URL if no external URL is configured.
     * Returns empty if the service is not healthy (DOWN, UNKNOWN, or WARNING).
     * <p>
     * Ideal for blue/green deployments — callers will not receive URLs for services
     * that are in the process of switching or are currently unreachable.
     */
    public static Optional<String> healthyExternalUrl(String name)
    {
        return get(name)
                .filter(ServiceEntry::isHealthy)
                .map(e -> e.hasExternalUrl() ? e.externalUrl() : e.url());
    }

    /**
     * Gets all external/public URLs for a named service, only if it's healthy.
     * Returns empty if the service is not healthy.
     */
    public static Optional<List<String>> healthyExternalUrls(String name)
    {
        return get(name)
                .filter(ServiceEntry::isHealthy)
                .map(ServiceEntry::externalUrls);
    }

    /**
     * Resolves a URL string — only returns a value if the service is healthy.
     * If the service is DOWN/UNKNOWN/WARNING during a blue/green switch, returns empty.
     * <p>
     * Supports "registry:" prefix and ${ENV_VAR} placeholders.
     */
    public static Optional<String> resolveHealthy(String urlOrRegistryName)
    {
        if (urlOrRegistryName == null || urlOrRegistryName.isBlank()) return Optional.empty();
        String resolved = resolveEnvPlaceholders(urlOrRegistryName);
        if (resolved.startsWith("registry:"))
        {
            String serviceName = resolved.substring("registry:".length()).trim();
            return healthyUrl(serviceName);
        }
        return Optional.of(resolved);
    }

    /** @return Only healthy services that have external URLs configured. */
    public static Map<String, ServiceEntry> healthyWithExternalUrls()
    {
        return SERVICES.entrySet().stream()
                .filter(e -> e.getValue().isHealthy() && e.getValue().hasExternalUrl())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ── Blue/Green Instance Management ──────────────────────────────────────────

    /**
     * Registers a service instance for blue/green tracking.
     * Multiple instances of the same logical service can coexist (e.g. v1 and v2 during deployment).
     * <p>
     * The primary SERVICES map is also updated with the latest registered instance.
     */
    public static void registerInstance(ServiceEntry entry)
    {
        String key = entry.name().toLowerCase().trim();
        INSTANCES.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        List<ServiceEntry> instances = INSTANCES.get(key);

        // Replace existing instance with same revision, or add new
        String instanceId = entry.instanceId();
        instances.removeIf(e -> e.instanceId().equals(instanceId));
        instances.add(entry);

        // Primary map always holds the "best" instance (latest healthy, or latest registered)
        ServiceEntry best = selectBestInstance(instances);
        if (best != null)
        {
            SERVICES.put(key, best);
        }

        log.info("📋 Instance registered: '{}' (revision: '{}') → {}", entry.name(), entry.revision(), entry.url());
    }

    /**
     * Unregisters a specific instance by revision.
     */
    public static void unregisterInstance(String name, String revision)
    {
        String key = name.toLowerCase().trim();
        List<ServiceEntry> instances = INSTANCES.get(key);
        if (instances != null)
        {
            instances.removeIf(e -> e.revision().equals(revision));
            if (instances.isEmpty())
            {
                INSTANCES.remove(key);
                SERVICES.remove(key);
            }
            else
            {
                ServiceEntry best = selectBestInstance(instances);
                if (best != null) SERVICES.put(key, best);
            }
            log.info("🗑️ Instance unregistered: '{}' revision '{}'", name, revision);
        }
    }

    /**
     * Updates the status of a specific instance by revision.
     * Recalculates which instance is "best" for the primary service map.
     */
    public static void updateInstanceStatus(String name, String revision, ServiceStatus status)
    {
        updateInstanceStatus(name, revision, status, List.of());
    }

    /**
     * Updates the status and health details of a specific instance by revision.
     */
    public static void updateInstanceStatus(String name, String revision, ServiceStatus status, List<ServiceHealthDetail> details)
    {
        String key = name.toLowerCase().trim();
        List<ServiceEntry> instances = INSTANCES.get(key);
        if (instances != null)
        {
            for (int i = 0; i < instances.size(); i++)
            {
                ServiceEntry inst = instances.get(i);
                if (inst.revision().equals(revision))
                {
                    ServiceStatus previousStatus = inst.status();
                    ServiceEntry updated = inst.withStatusAndDetails(status, details);
                    instances.set(i, updated);

                    if (previousStatus != status)
                    {
                        fireStatusChange(name + ":" + revision, previousStatus, status, updated);
                    }
                    break;
                }
            }

            // Recalculate best instance for primary map
            ServiceEntry best = selectBestInstance(instances);
            if (best != null)
            {
                ServiceStatus previousPrimary = Optional.ofNullable(SERVICES.get(key))
                        .map(ServiceEntry::status).orElse(ServiceStatus.UNKNOWN);
                SERVICES.put(key, best);
                if (previousPrimary != best.status())
                {
                    fireStatusChange(name, previousPrimary, best.status(), best);
                }
            }
        }
    }

    /**
     * Gets all instances (all revisions) for a named service.
     */
    public static List<ServiceEntry> instances(String name)
    {
        String key = name.toLowerCase().trim();
        String canonical = ALIASES.getOrDefault(key, key);
        List<ServiceEntry> list = INSTANCES.get(canonical);
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * Gets all healthy instances for a named service.
     * During blue/green overlap, this returns both v1 and v2 if both are UP.
     */
    public static List<ServiceEntry> healthyInstances(String name)
    {
        return instances(name).stream()
                .filter(ServiceEntry::isHealthy)
                .toList();
    }

    /**
     * Gets the latest healthy instance (by revision, lexicographically latest).
     * During blue/green: if v2 is UP, returns v2. If only v1 is UP, returns v1.
     * If neither is UP, returns empty.
     */
    public static Optional<ServiceEntry> latestHealthyInstance(String name)
    {
        List<ServiceEntry> healthy = healthyInstances(name);
        if (healthy.isEmpty()) return Optional.empty();
        if (healthy.size() == 1) return Optional.of(healthy.getFirst());

        // Prefer the latest revision (lexicographic sort, descending)
        return healthy.stream()
                .max(Comparator.comparing(ServiceEntry::revision));
    }

    /**
     * Selects the best instance for the primary SERVICES map.
     * Priority: latest healthy revision > any healthy > latest unhealthy.
     */
    private static ServiceEntry selectBestInstance(List<ServiceEntry> instances)
    {
        if (instances == null || instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.getFirst();

        // Prefer healthy instances, then latest revision
        Optional<ServiceEntry> bestHealthy = instances.stream()
                .filter(ServiceEntry::isHealthy)
                .max(Comparator.comparing(ServiceEntry::revision));

        return bestHealthy.orElse(instances.getLast());
    }

    /**
     * Gets the full OpenAPI spec URL for a named service.
     * Returns empty if the service has no openApiPath configured.
     */
    public static Optional<String> openApiUrl(String name)
    {
        return get(name).flatMap(e -> {
            String path = e.metadata().getOrDefault("openApiPath", "");
            if (path.isEmpty()) return Optional.empty();
            String base = e.url().endsWith("/") ? e.url().substring(0, e.url().length() - 1) : e.url();
            return Optional.of(base + (path.startsWith("/") ? path : "/" + path));
        });
    }

    /**
     * Gets all services that have an OpenAPI spec configured, optionally filtered by environment.
     *
     * @param environment the current environment (e.g. "dev", "int", "prod"), or null/empty for all
     * @return map of service name → full OpenAPI spec URL
     */
    public static Map<String, String> allOpenApiUrls(String environment)
    {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (var entry : SERVICES.entrySet())
        {
            String path = entry.getValue().metadata().getOrDefault("openApiPath", "");
            if (path.isEmpty()) continue;

            // Check environment filter
            String envFilter = entry.getValue().metadata().getOrDefault("openApiEnvironments", "");
            if (!envFilter.isEmpty() && environment != null && !environment.isEmpty())
            {
                boolean allowed = java.util.Arrays.stream(envFilter.split(","))
                        .map(String::trim)
                        .anyMatch(e -> e.equalsIgnoreCase(environment));
                if (!allowed) continue;
            }

            String base = entry.getValue().url().endsWith("/")
                    ? entry.getValue().url().substring(0, entry.getValue().url().length() - 1)
                    : entry.getValue().url();
            result.put(entry.getKey(), base + (path.startsWith("/") ? path : "/" + path));
        }
        return result;
    }

    /**
     * Gets all services that have an OpenAPI spec configured (no environment filter).
     */
    public static Map<String, String> allOpenApiUrls()
    {
        return allOpenApiUrls(null);
    }

    /**
     * Gets all services that have a GraphQL endpoint configured, optionally filtered by environment.
     *
     * @param environment the current environment (e.g. "dev", "int", "prod"), or null/empty for all
     * @return map of service name → full GraphQL endpoint URL
     */
    public static Map<String, String> allGraphQLUrls(String environment)
    {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (var entry : SERVICES.entrySet())
        {
            String path = entry.getValue().metadata().getOrDefault("graphqlPath", "");
            if (path.isEmpty()) continue;

            String envFilter = entry.getValue().metadata().getOrDefault("graphqlEnvironments", "");
            if (!envFilter.isEmpty() && environment != null && !environment.isEmpty())
            {
                boolean allowed = java.util.Arrays.stream(envFilter.split(","))
                        .map(String::trim)
                        .anyMatch(e -> e.equalsIgnoreCase(environment));
                if (!allowed) continue;
            }

            String base = entry.getValue().url().endsWith("/")
                    ? entry.getValue().url().substring(0, entry.getValue().url().length() - 1)
                    : entry.getValue().url();
            result.put(entry.getKey(), base + (path.startsWith("/") ? path : "/" + path));
        }
        return result;
    }

    /**
     * Gets all services that have a GraphQL endpoint configured (no environment filter).
     */
    public static Map<String, String> allGraphQLUrls()
    {
        return allGraphQLUrls(null);
    }

    /**
     * Registers or updates a service entry.
     * If the entry has a revision in metadata, it's also tracked as an instance for blue/green support.
     */
    public static void register(ServiceEntry entry)
    {
        String key = entry.name().toLowerCase().trim();
        if (entry.hasRevision())
        {
            registerInstance(entry);
        }
        else
        {
            SERVICES.put(key, entry);
        }
        log.info("📋 Service registered: '{}' → {}", entry.name(), entry.url());
    }

    /**
     * Registers a service with just a name and URL (status = UNKNOWN).
     */
    public static void register(String name, String url)
    {
        register(new ServiceEntry(name, url, "/health/ready", ServiceStatus.UNKNOWN, Instant.now(), Map.of()));
    }

    /**
     * Unregisters a service.
     */
    public static void unregister(String name)
    {
        SERVICES.remove(name.toLowerCase().trim());
        log.info("🗑️ Service unregistered: '{}'", name);
    }

    /**
     * Updates the health status of a service. Fires status change events if status differs.
     */
    public static void updateStatus(String name, ServiceStatus status)
    {
        String key = name.toLowerCase().trim();
        ServiceEntry existing = SERVICES.get(key);
        if (existing != null)
        {
            ServiceStatus previousStatus = existing.status();
            ServiceEntry updated = existing.withStatus(status);
            SERVICES.put(key, updated);
            if (previousStatus != status)
            {
                fireStatusChange(name, previousStatus, status, updated);
            }
        }
    }

    /**
     * Updates the health status and component details of a service. Fires status change events if status differs.
     */
    public static void updateStatus(String name, ServiceStatus status, List<ServiceHealthDetail> details)
    {
        String key = name.toLowerCase().trim();
        ServiceEntry existing = SERVICES.get(key);
        if (existing != null)
        {
            ServiceStatus previousStatus = existing.status();
            ServiceEntry updated = existing.withStatusAndDetails(status, details);
            SERVICES.put(key, updated);
            if (previousStatus != status)
            {
                fireStatusChange(name, previousStatus, status, updated);
            }
        }
    }

    /**
     * Fires status change notifications to all registered listeners.
     */
    private static void fireStatusChange(String serviceName, ServiceStatus previous, ServiceStatus newStatus, ServiceEntry entry)
    {
        log.info("🔄 Service '{}' status changed: {} → {}", serviceName, previous, newStatus);

        // Fire to programmatic listeners
        for (var listener : LISTENERS)
        {
            try
            {
                listener.onStatusChange(serviceName, previous, newStatus, entry);
            }
            catch (Exception e)
            {
                log.warn("Status change listener {} failed: {}", listener.getClass().getName(), e.getMessage());
            }
        }

        // Fire to ServiceLoader-discovered listeners
        try
        {
            ServiceLoader<IServiceStatusChangeListener> loader = ServiceLoader.load(IServiceStatusChangeListener.class);
            for (var listener : loader)
            {
                try
                {
                    listener.onStatusChange(serviceName, previous, newStatus, entry);
                }
                catch (Exception e)
                {
                    log.warn("Status change listener {} failed: {}", listener.getClass().getName(), e.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            log.debug("No ServiceLoader listeners available: {}", e.getMessage());
        }
    }

    /**
     * Gets the health details (individual check components) for a named service.
     *
     * @return list of health detail components, or empty list if not available
     */
    public static List<ServiceHealthDetail> healthDetails(String name)
    {
        return get(name).map(ServiceEntry::healthDetails).orElse(List.of());
    }

    /** @return All registered services (unmodifiable). */
    public static Map<String, ServiceEntry> all()
    {
        return Collections.unmodifiableMap(SERVICES);
    }

    /** @return Only healthy services (UP or DEGRADED). */
    public static Map<String, ServiceEntry> healthy()
    {
        return SERVICES.entrySet().stream()
                .filter(e -> e.getValue().isHealthy())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** @return Service names that are currently DOWN. */
    public static List<String> down()
    {
        return SERVICES.entrySet().stream()
                .filter(e -> e.getValue().status() == ServiceStatus.DOWN)
                .map(Map.Entry::getKey).toList();
    }

    /** @return true if a service with the given name (or alias) is registered. */
    public static boolean contains(String name)
    {
        String key = name.toLowerCase().trim();
        return SERVICES.containsKey(key) || ALIASES.containsKey(key);
    }

    /**
     * Registers an alias that resolves to a canonical service name.
     */
    public static void registerAlias(String alias, String canonicalName)
    {
        ALIASES.put(alias.toLowerCase().trim(), canonicalName.toLowerCase().trim());
        log.info("📋 Alias registered: '{}' → '{}'", alias, canonicalName);
    }

    /**
     * Resolves a URL string — if it starts with "registry:" prefix, resolves from registry.
     * Otherwise returns as-is. Supports ${ENV_VAR} placeholders.
     */
    public static String resolve(String urlOrRegistryName)
    {
        if (urlOrRegistryName == null || urlOrRegistryName.isBlank()) return urlOrRegistryName;
        String resolved = resolveEnvPlaceholders(urlOrRegistryName);
        if (resolved.startsWith("registry:"))
        {
            return url(resolved.substring("registry:".length()).trim());
        }
        return resolved;
    }

    /**
     * Resolves ${ENV_VAR} placeholders in a string.
     */
    public static String resolveEnvPlaceholders(String input)
    {
        if (input == null || !input.contains("${")) return input;
        String result = input;
        int start;
        while ((start = result.indexOf("${")) >= 0)
        {
            int end = result.indexOf("}", start);
            if (end < 0) break;
            String varName = result.substring(start + 2, end);
            String value = Environment.getSystemPropertyOrEnvironment(varName, "");
            result = result.substring(0, start) + value + result.substring(end + 1);
        }
        return result;
    }

    /** Clears all registered services, aliases, instances, and listeners. Used for testing. */
    public static void clear()
    {
        SERVICES.clear();
        ALIASES.clear();
        INSTANCES.clear();
        LISTENERS.clear();
    }
}


