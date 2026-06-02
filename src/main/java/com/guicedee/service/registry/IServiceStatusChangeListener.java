package com.guicedee.service.registry;

import com.guicedee.client.services.IDefaultService;

/**
 * SPI listener for service status change events.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} and invoked
 * whenever a service's health status changes in the registry.
 * <p>
 * Example use cases:
 * <ul>
 *     <li>Invalidate cached connections when a service goes DOWN</li>
 *     <li>Trigger circuit-breaker state transitions</li>
 *     <li>Send alerts/notifications when critical services degrade</li>
 *     <li>Refresh GraphQL/OpenAPI merged schemas when services come UP</li>
 * </ul>
 * <p>
 * Listeners are called synchronously on the health-check thread. Implementations
 * should be fast and non-blocking. For expensive reactions, dispatch to a worker thread.
 *
 * @since 2.1.0
 */
public interface IServiceStatusChangeListener<J extends IServiceStatusChangeListener<J>> extends IDefaultService<J>
{
    /**
     * Called when a service's status changes.
     *
     * @param serviceName  the logical name of the service
     * @param previousStatus the status before the change (may be null on first check)
     * @param newStatus    the new status
     * @param entry        the updated service entry
     */
    void onStatusChange(String serviceName, ServiceStatus previousStatus, ServiceStatus newStatus, ServiceEntry entry);
}

