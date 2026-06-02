package com.guicedee.service.registry;

import java.lang.annotation.*;

/**
 * Configures the service registry behavior.
 * Place on a class or {@code package-info.java}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface ServiceRegistryOptions
{
    /** @return Interval in seconds between health checks. 0 = disabled. */
    int healthCheckInterval() default 30;

    /** @return Default health check path for services that don't specify one. */
    String healthPath() default "/health/ready";

    /** @return Whether to auto-register this application using runtime-autoconfigure metadata. */
    boolean registerSelf() default true;

    /** @return HTTP timeout in milliseconds for health checks. */
    int healthCheckTimeout() default 5000;

    /** @return Whether to use HTTPS for constructed URLs. */
    boolean useHttps() default true;

    /** @return Default port for constructed URLs. 0 = omit port from URL. */
    int defaultPort() default 0;
}

