package com.guicedee.service.registry;

import com.guicedee.client.services.IDefaultService;

import java.util.List;

/**
 * SPI for external service registry sources.
 * <p>
 * Implementations can provide service entries from external systems like
 * Azure Container Apps API, Consul, Kubernetes API, static config, Eureka, etc.
 */
public interface IServiceRegistryProvider<J extends IServiceRegistryProvider<J>> extends IDefaultService<J>
{
    /** @return Service entries discovered by this provider. */
    List<ServiceEntry> discover();

    /** @return Unique identifier for this provider (e.g. "azure", "consul", "static"). */
    String providerId();
}

