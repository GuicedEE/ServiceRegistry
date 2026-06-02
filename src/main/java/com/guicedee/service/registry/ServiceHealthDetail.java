package com.guicedee.service.registry;

import java.util.Map;

/**
 * Represents an individual health check component from a service's health endpoint response.
 * <p>
 * When a service returns a MicroProfile Health-style JSON response, each "check" entry
 * is captured as a ServiceHealthDetail:
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "checks": [
 *     { "name": "database", "status": "UP", "data": {"connection-pool": "10/20"} },
 *     { "name": "disk-space", "status": "UP", "data": {"free": "50GB"} }
 *   ]
 * }
 * }</pre>
 *
 * @param name   The name/id of the individual health check component
 * @param status The status of this component (UP or DOWN)
 * @param data   Optional key-value data associated with this check
 */
public record ServiceHealthDetail(
        String name,
        ServiceStatus status,
        Map<String, Object> data
)
{
    public ServiceHealthDetail(String name, ServiceStatus status)
    {
        this(name, status, Map.of());
    }

    public boolean isUp()
    {
        return status == ServiceStatus.UP;
    }
}

