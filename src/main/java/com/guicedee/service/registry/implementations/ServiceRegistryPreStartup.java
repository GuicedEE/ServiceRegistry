package com.guicedee.service.registry.implementations;

import com.guicedee.client.utils.ClassLoading;
import com.guicedee.client.utils.Discovery;

import com.guicedee.client.Environment;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.services.lifecycle.IGuicePreStartup;
import com.guicedee.service.registry.*;
import io.github.classgraph.PackageInfo;
import io.github.classgraph.ScanResult;
import io.vertx.core.Future;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.time.Instant;
import java.util.*;

/**
 * Pre-startup hook that scans for {@link ServiceRegistryOptions} and {@link RegisteredService}
 * annotations, registers services, and optionally self-registers using runtime-autoconfigure.
 */
@Log4j2
public class ServiceRegistryPreStartup implements IGuicePreStartup<ServiceRegistryPreStartup>
{
    @Getter
    private static ServiceRegistryOptions options;

    @Override
    public List<Future<Boolean>> onStartup()
    {
        return List.of(Future.succeededFuture(scan()));
    }

    private Boolean scan()
    {
        ScanResult scanResult = Discovery.scanResult();
        options = scanResult == null ? Discovery.firstRegisteredAnnotation(ServiceRegistryOptions.class).orElse(null) : findOptions(scanResult);
        String defaultHealthPath = Environment.getSystemPropertyOrEnvironment("SERVICE_REGISTRY_HEALTH_PATH", options != null ? options.healthPath() : "/health/ready");
        boolean useHttps = options == null || options.useHttps();
        int defaultPort = options != null ? options.defaultPort() : 0;

        List<RegisteredService> registeredServices = scanResult == null ? Discovery.registeredAnnotations(RegisteredService.class) : findRegisteredServices(scanResult);
        String dnsSuffix = detectDnsSuffix();

        for (RegisteredService rs : registeredServices)
        {
            registerService(rs, defaultHealthPath, useHttps, defaultPort, dnsSuffix);
        }

        if (options == null || options.registerSelf())
        {
            registerSelf(defaultHealthPath, useHttps, defaultPort, dnsSuffix);
        }

        ServiceLoader<IServiceRegistryProvider> providers = ServiceLoader.load(IServiceRegistryProvider.class);
        for (var provider : providers)
        {
            try
            {
                List<ServiceEntry> entries = provider.discover();
                for (ServiceEntry entry : entries)
                {
                    ServiceRegistry.register(entry);
                }
                log.info("🔌 Registry provider '{}' contributed {} services",
                        provider.providerId(), entries.size());
            }
            catch (Exception e)
            {
                log.warn("Provider '{}' failed: {}", provider.providerId(), e.getMessage());
            }
        }

        log.info("📋 Service registry initialized with {} services: {}",
                ServiceRegistry.all().size(), ServiceRegistry.all().keySet());
        return true;
    }

    /** Applies declaration text before handing effective metadata to the registry. */
    static void registerService(RegisteredService rs, String defaultHealthPath, boolean useHttps, int defaultPort, String dnsSuffix)
    {
        String serviceName = Environment.resolveAnnotationValue(rs.name());
        String url = Environment.resolveAnnotationValue(rs.url());
        if (url.isEmpty())
        {
            if (dnsSuffix != null && !dnsSuffix.isEmpty())
            {
                String scheme = useHttps ? "https" : "http";
                String portPart = defaultPort > 0 ? ":" + defaultPort : "";
                url = scheme + "://" + serviceName + "." + dnsSuffix + portPart;
            }
            else
            {
                log.warn("⚠️ Cannot construct URL for service '{}' — no DNS suffix available. " +
                        "Set url explicitly or add runtime-autoconfigure.", serviceName);
                return;
            }
        }

        String healthPath = Environment.resolveAnnotationValue(rs.healthPath());
        if (healthPath.isEmpty()) healthPath = defaultHealthPath;

        // Resolve external URLs
        List<String> extUrls = new ArrayList<>();
        if (!rs.externalUrl().isEmpty())
        {
            extUrls.add(Environment.resolveAnnotationValue(rs.externalUrl()));
        }
        for (String eu : rs.externalUrls())
        {
            if (!eu.isEmpty())
            {
                extUrls.add(Environment.resolveAnnotationValue(eu));
            }
        }

        // Resolve Kubernetes URL
        String k8sUrl = rs.kubernetesUrl().isEmpty() ? "" : Environment.resolveAnnotationValue(rs.kubernetesUrl());

        // Build metadata from openApiPath and openApiEnvironments
        Map<String, String> metadata = new java.util.HashMap<>();
        if (!rs.healthUrl().isEmpty())
        {
            metadata.put("healthUrl", Environment.resolveAnnotationValue(rs.healthUrl()));
        }
        String openApiPath = rs.openApiPath().isEmpty() ? "" : Environment.resolveAnnotationValue(rs.openApiPath());
        if (!openApiPath.isEmpty())
        {
            metadata.put("openApiPath", openApiPath);
        }
        if (rs.openApiEnvironments().length > 0)
        {
            metadata.put("openApiEnvironments", Arrays.stream(rs.openApiEnvironments()).map(Environment::resolveAnnotationValue).collect(java.util.stream.Collectors.joining(",")));
        }

        // GraphQL metadata
        String graphqlPath = rs.graphqlPath().isEmpty() ? "" : Environment.resolveAnnotationValue(rs.graphqlPath());
        if (!graphqlPath.isEmpty())
        {
            metadata.put("graphqlPath", graphqlPath);
        }
        if (rs.graphqlEnvironments().length > 0)
        {
            metadata.put("graphqlEnvironments", Arrays.stream(rs.graphqlEnvironments()).map(Environment::resolveAnnotationValue).collect(java.util.stream.Collectors.joining(",")));
        }

        // Health check expectations
        if (rs.expectedStatusCode() != 200)
        {
            metadata.put("expectedStatusCode", String.valueOf(rs.expectedStatusCode()));
        }
        if (!"application/json".equals(Environment.resolveAnnotationValue(rs.expectedContentType())))
        {
            metadata.put("expectedContentType", Environment.resolveAnnotationValue(rs.expectedContentType()));
        }

        // Revision tracking for blue/green deployments
        String revision = rs.revision().isEmpty() ? "" : Environment.resolveAnnotationValue(rs.revision());
        if (!revision.isEmpty())
        {
            metadata.put("revision", revision);
        }

        // Authentication scheme metadata for OpenAPI merge
        if (!Environment.resolveAnnotationValue(rs.authScheme()).isEmpty())
        {
            metadata.put("authScheme", Environment.resolveAnnotationValue(rs.authScheme()));
        }
        if (!rs.authTokenUrl().isEmpty())
        {
            metadata.put("authTokenUrl", Environment.resolveAnnotationValue(rs.authTokenUrl()));
        }
        if (rs.authScopes().length > 0)
        {
            metadata.put("authScopes", Arrays.stream(rs.authScopes()).map(Environment::resolveAnnotationValue).collect(java.util.stream.Collectors.joining("|")));
        }

        ServiceRegistry.register(new ServiceEntry(
                serviceName, url, extUrls, k8sUrl, healthPath, ServiceStatus.UNKNOWN, Instant.now(),
                metadata.isEmpty() ? Map.of() : Map.copyOf(metadata)));

        // Register aliases
        for (String alias : rs.aliases())
        {
            if (!alias.isEmpty())
            {
                ServiceRegistry.registerAlias(Environment.resolveAnnotationValue(alias), serviceName);
            }
        }
    }

    private void registerSelf(String defaultHealthPath, boolean useHttps, int defaultPort, String dnsSuffix)
    {
        try
        {
            Class<?> cls = Class.forName("com.guicedee.runtime.autoconfigure.implementations.RuntimeAutoConfigurePreStartup");
            var currentMethod = cls.getMethod("current");
            @SuppressWarnings("unchecked")
            Optional<Object> envOpt = (Optional<Object>) currentMethod.invoke(null);
            if (envOpt.isPresent())
            {
                Object env = envOpt.get();
                String serviceName = (String) env.getClass().getMethod("serviceName").invoke(env);
                String hostname = (String) env.getClass().getMethod("hostname").invoke(env);
                Integer port = (Integer) env.getClass().getMethod("port").invoke(env);

                if (serviceName != null && !serviceName.isEmpty())
                {
                    String scheme = useHttps ? "https" : "http";
                    String url;
                    if (hostname != null && !hostname.isEmpty())
                    {
                        String portPart = (port != null && port > 0 && port != 80 && port != 443) ? ":" + port : "";
                        url = scheme + "://" + hostname + portPart;
                    }
                    else if (dnsSuffix != null && !dnsSuffix.isEmpty())
                    {
                        url = scheme + "://" + serviceName + "." + dnsSuffix;
                    }
                    else
                    {
                        url = scheme + "://localhost" + (port != null && port > 0 ? ":" + port : "");
                    }

                    ServiceRegistry.register(new ServiceEntry(
                            serviceName, url, defaultHealthPath,
                            ServiceStatus.UP, Instant.now(),
                            Map.of("self", "true")));
                    log.info("🏠 Self-registered as '{}' → {}", serviceName, url);
                }
            }
        }
        catch (ClassNotFoundException e)
        {
            log.debug("⏭️ runtime-autoconfigure not on classpath, skipping self-registration");
        }
        catch (Exception e)
        {
            log.debug("Self-registration failed: {}", e.getMessage());
        }
    }

    private String detectDnsSuffix()
    {
        String suffix = Environment.getSystemPropertyOrEnvironment("CONTAINER_APP_ENV_DNS_SUFFIX", "");
        if (!suffix.isEmpty()) return suffix;

        try
        {
            Class<?> cls = Class.forName("com.guicedee.runtime.autoconfigure.implementations.RuntimeAutoConfigurePreStartup");
            @SuppressWarnings("unchecked")
            Optional<Object> envOpt = (Optional<Object>) cls.getMethod("current").invoke(null);
            if (envOpt.isPresent())
            {
                @SuppressWarnings("unchecked")
                Map<String, String> extras = (Map<String, String>) envOpt.get().getClass().getMethod("extras").invoke(envOpt.get());
                if (extras != null && extras.containsKey("dnsSuffix"))
                {
                    return extras.get("dnsSuffix");
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Could not get DNS suffix from runtime-autoconfigure: {}", e.getMessage());
        }

        return Environment.getSystemPropertyOrEnvironment("SERVICE_REGISTRY_DNS_SUFFIX", "");
    }

    private ServiceRegistryOptions findOptions(ScanResult scanResult)
    {
        for (PackageInfo pi : scanResult.getPackageInfo())
        {
            if (pi.getDirectAnnotationInfo(ServiceRegistryOptions.class.getName()) != null)
            {
                try
                {
                    return Class.forName(pi.getName() + ".package-info").getAnnotation(ServiceRegistryOptions.class);
                }
                catch (ClassNotFoundException ignored) {}
            }
        }
        var classes = scanResult.getClassesWithAnnotation(ServiceRegistryOptions.class);
        return classes.isEmpty() ? null : ClassLoading.loadClass(classes.getFirst()).getAnnotation(ServiceRegistryOptions.class);
    }

    private List<RegisteredService> findRegisteredServices(ScanResult scanResult)
    {
        List<RegisteredService> results = new ArrayList<>();
        for (PackageInfo pi : scanResult.getPackageInfo())
        {
            if (pi.getDirectAnnotationInfo(RegisteredService.class.getName()) != null
                    || pi.getDirectAnnotationInfo(RegisteredServices.class.getName()) != null)
            {
                try
                {
                    results.addAll(Arrays.asList(
                            Class.forName(pi.getName() + ".package-info").getAnnotationsByType(RegisteredService.class)));
                }
                catch (ClassNotFoundException ignored) {}
            }
        }
        for (var ci : scanResult.getClassesWithAnnotation(RegisteredService.class))
        {
            if (!ci.getName().endsWith(".package-info"))
                results.addAll(Arrays.asList(ClassLoading.loadClass(ci).getAnnotationsByType(RegisteredService.class)));
        }
        for (var ci : scanResult.getClassesWithAnnotation(RegisteredServices.class))
        {
            if (!ci.getName().endsWith(".package-info"))
                results.addAll(Arrays.asList(ClassLoading.loadClass(ci).getAnnotationsByType(RegisteredService.class)));
        }
        return results;
    }

    @Override
    public Integer sortOrder()
    {
        // After runtime-autoconfigure (MIN+50) and VertXPreStartup (MIN+100)
        return Integer.MIN_VALUE + 150;
    }
}
