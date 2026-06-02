package com.guicedee.service.registry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a registered service in the registry.
 * <p>
 * A service can have both an internal URL (cloud-internal, auto-constructed),
 * multiple external URLs (custom domains, public-facing), and a Kubernetes internal URL.
 */
public record ServiceEntry(
        /** Simple logical name — e.g. "jwebmp-website" */
        String name,
        /** Internal/primary URL — e.g. "https://jwebmp-website.whitegrass-2e17714c.ukwest.azurecontainerapps.io" */
        String url,
        /** External/public URLs — e.g. ["https://jwebmp.com", "https://jwebswing.com"]. May be empty. */
        List<String> externalUrls,
        /** Kubernetes internal cluster URL — e.g. "http://jwebmp-website.default.svc.cluster.local". May be empty. */
        String kubernetesUrl,
        /** Health check path appended to URL — e.g. "/health/ready" */
        String healthPath,
        /** Current health status */
        ServiceStatus status,
        /** When the health was last checked */
        Instant lastChecked,
        /** Optional metadata: provider, region, revision, etc. */
        Map<String, String> metadata,
        /** Individual health check component details from the service's health endpoint. May be empty. */
        List<ServiceHealthDetail> healthDetails
)
{
    /** Backwards-compatible constructor without externalUrls/kubernetesUrl. */
    public ServiceEntry(String name, String url, String healthPath, ServiceStatus status, Instant lastChecked, Map<String, String> metadata)
    {
        this(name, url, List.of(), "", healthPath, status, lastChecked, metadata, List.of());
    }

    /** Backwards-compatible constructor with single externalUrl. */
    public ServiceEntry(String name, String url, String externalUrl, String healthPath, ServiceStatus status, Instant lastChecked, Map<String, String> metadata)
    {
        this(name, url, externalUrl == null || externalUrl.isEmpty() ? List.of() : List.of(externalUrl), "", healthPath, status, lastChecked, metadata, List.of());
    }

    /** Constructor with multiple external URLs and kubernetes URL. */
    public ServiceEntry(String name, String url, List<String> externalUrls, String kubernetesUrl, String healthPath, ServiceStatus status, Instant lastChecked, Map<String, String> metadata)
    {
        this(name, url, externalUrls, kubernetesUrl, healthPath, status, lastChecked, metadata, List.of());
    }

    /** Full constructor with health details. */
    public ServiceEntry(String name, String url, List<String> externalUrls, String kubernetesUrl, String healthPath, ServiceStatus status, Instant lastChecked, Map<String, String> metadata, List<ServiceHealthDetail> healthDetails)
    {
        this.name = name;
        this.url = url;
        this.externalUrls = externalUrls != null ? List.copyOf(externalUrls) : List.of();
        this.kubernetesUrl = kubernetesUrl != null ? kubernetesUrl : "";
        this.healthPath = healthPath;
        this.status = status;
        this.lastChecked = lastChecked;
        this.metadata = metadata;
        this.healthDetails = healthDetails != null ? List.copyOf(healthDetails) : List.of();
    }

    public String healthUrl()
    {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String path = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        return base + path;
    }

    public boolean isHealthy()
    {
        return status == ServiceStatus.UP || status == ServiceStatus.DEGRADED;
    }

    public ServiceEntry withStatus(ServiceStatus newStatus)
    {
        return new ServiceEntry(name, url, externalUrls, kubernetesUrl, healthPath, newStatus, Instant.now(), metadata, healthDetails);
    }

    /** Returns a new ServiceEntry with updated status and health details. */
    public ServiceEntry withStatusAndDetails(ServiceStatus newStatus, List<ServiceHealthDetail> details)
    {
        return new ServiceEntry(name, url, externalUrls, kubernetesUrl, healthPath, newStatus, Instant.now(), metadata, details);
    }

    /** Returns the first external URL, or empty string if none configured. */
    public String externalUrl()
    {
        return externalUrls.isEmpty() ? "" : externalUrls.getFirst();
    }

    /** Returns true if this service has at least one external/public URL configured. */
    public boolean hasExternalUrl()
    {
        return !externalUrls.isEmpty();
    }

    /** Returns true if this service has a Kubernetes internal URL configured. */
    public boolean hasKubernetesUrl()
    {
        return kubernetesUrl != null && !kubernetesUrl.isEmpty();
    }

    /** Returns the revision/version identifier for this instance, or empty string if not set. */
    public String revision()
    {
        return metadata.getOrDefault("revision", "");
    }

    /** Returns true if this entry has a revision/version identifier. */
    public boolean hasRevision()
    {
        String rev = revision();
        return rev != null && !rev.isEmpty();
    }

    /** Returns the instance ID (name + revision), used to distinguish blue/green slots. */
    public String instanceId()
    {
        String rev = revision();
        return rev.isEmpty() ? name : name + ":" + rev;
    }
}
