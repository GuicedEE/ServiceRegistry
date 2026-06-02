package com.guicedee.service.registry.implementations;

import com.guicedee.client.services.lifecycle.IGuicePreDestroy;
import com.guicedee.service.registry.ServiceRegistry;
import com.guicedee.service.registry.ServiceStatus;
import lombok.extern.log4j.Log4j2;

/**
 * Graceful shutdown hook that marks this application as DOWN in the registry
 * and stops the periodic health check timer.
 * <p>
 * This ensures that during rolling deployments or shutdown, any service
 * that queries this instance's health will see it as DOWN immediately,
 * rather than waiting for the next health check cycle to timeout.
 *
 * @since 2.1.0
 */
@Log4j2
public class ServiceRegistryPreDestroy implements IGuicePreDestroy<ServiceRegistryPreDestroy>
{
    @Override
    public void onDestroy()
    {
        // Mark self as DOWN
        for (var entry : ServiceRegistry.all().entrySet())
        {
            if ("true".equals(entry.getValue().metadata().get("self")))
            {
                ServiceRegistry.updateStatus(entry.getKey(), ServiceStatus.DOWN);
                log.info("🛑 Self-service '{}' marked as DOWN during shutdown", entry.getKey());
            }
        }

        // Stop health check timer and close WebClient
        ServiceRegistryPostStartup.stop();
        log.info("🛑 Service registry health checks stopped");
    }

    @Override
    public Integer sortOrder()
    {
        // Run early in destruction — before other modules that might depend on registry
        return Integer.MIN_VALUE + 100;
    }
}

