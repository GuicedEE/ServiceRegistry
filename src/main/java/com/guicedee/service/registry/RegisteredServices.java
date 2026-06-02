package com.guicedee.service.registry;

import java.lang.annotation.*;

/**
 * Container for repeatable {@link RegisteredService} annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface RegisteredServices
{
    RegisteredService[] value();
}

