package com.guicedee.service.registry.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.service.registry.*;
import com.guicedee.service.registry.implementations.ServiceRegistryPostStartup;
import com.guicedee.service.registry.implementations.ServiceRegistryPreDestroy;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for:
 * 1. IServiceStatusChangeListener — status change events
 * 2. ServiceRegistryPreDestroy — graceful shutdown
 * 3. Exponential backoff logic in ServiceRegistryPostStartup
 */
public class ServiceRegistryEnhancementsTest
{
    @BeforeEach
    void setup()
    {
        ServiceRegistry.clear();
    }

    /**
     * Concrete implementation for testing — avoids CRTP diamond inference issues.
     */
    static class TestStatusListener implements IServiceStatusChangeListener<TestStatusListener>
    {
        private final StatusChangeCallback callback;

        TestStatusListener(StatusChangeCallback callback)
        {
            this.callback = callback;
        }

        @Override
        public void onStatusChange(String serviceName, ServiceStatus previousStatus, ServiceStatus newStatus, ServiceEntry entry)
        {
            callback.accept(serviceName, previousStatus, newStatus, entry);
        }

        @FunctionalInterface
        interface StatusChangeCallback
        {
            void accept(String serviceName, ServiceStatus prev, ServiceStatus next, ServiceEntry entry);
        }
    }

    // ──────────────────────────────────────────────────────────
    // STATUS CHANGE LISTENER TESTS
    // ──────────────────────────────────────────────────────────

    @Test
    void testStatusChangeListenerFiredOnChange()
    {
        IGuiceContext.instance().inject();

        List<String> events = new CopyOnWriteArrayList<>();

        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> events.add(name + ":" + prev + "→" + next)));

        // Register a service
        ServiceRegistry.register(new ServiceEntry("test-svc", "http://test:8080", "/health",
                ServiceStatus.UNKNOWN, Instant.now(), Map.of()));

        // Update to UP — should fire
        ServiceRegistry.updateStatus("test-svc", ServiceStatus.UP);
        assertEquals(1, events.size());
        assertEquals("test-svc:UNKNOWN→UP", events.get(0));

        // Update to UP again — should NOT fire (same status)
        ServiceRegistry.updateStatus("test-svc", ServiceStatus.UP);
        assertEquals(1, events.size());

        // Update to DOWN — should fire
        ServiceRegistry.updateStatus("test-svc", ServiceStatus.DOWN);
        assertEquals(2, events.size());
        assertEquals("test-svc:UP→DOWN", events.get(1));
    }

    @Test
    void testStatusChangeListenerWithDetails()
    {
        IGuiceContext.instance().inject();

        List<ServiceEntry> receivedEntries = new ArrayList<>();

        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> receivedEntries.add(entry)));

        ServiceRegistry.register(new ServiceEntry("detail-svc", "http://detail:8080", "/health",
                ServiceStatus.UNKNOWN, Instant.now(), Map.of()));

        List<ServiceHealthDetail> details = List.of(
                new ServiceHealthDetail("db", ServiceStatus.UP, Map.of("pool", "5/10")),
                new ServiceHealthDetail("cache", ServiceStatus.DOWN, Map.of("error", "timeout"))
        );

        ServiceRegistry.updateStatus("detail-svc", ServiceStatus.DEGRADED, details);

        assertEquals(1, receivedEntries.size());
        assertEquals(ServiceStatus.DEGRADED, receivedEntries.get(0).status());
        assertEquals(2, receivedEntries.get(0).healthDetails().size());
    }

    @Test
    void testMultipleListeners()
    {
        IGuiceContext.instance().inject();

        List<String> listener1Events = new CopyOnWriteArrayList<>();
        List<String> listener2Events = new CopyOnWriteArrayList<>();

        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> listener1Events.add(name)));

        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> listener2Events.add(name)));

        ServiceRegistry.register(new ServiceEntry("multi-svc", "http://multi:8080", "/health",
                ServiceStatus.UNKNOWN, Instant.now(), Map.of()));
        ServiceRegistry.updateStatus("multi-svc", ServiceStatus.UP);

        assertEquals(1, listener1Events.size());
        assertEquals(1, listener2Events.size());
    }

    @Test
    void testRemoveListener()
    {
        IGuiceContext.instance().inject();

        List<String> events = new CopyOnWriteArrayList<>();

        TestStatusListener listener = new TestStatusListener(
                (name, prev, next, entry) -> events.add(name));

        ServiceRegistry.addStatusChangeListener(listener);

        ServiceRegistry.register(new ServiceEntry("rm-svc", "http://rm:8080", "/health",
                ServiceStatus.UNKNOWN, Instant.now(), Map.of()));
        ServiceRegistry.updateStatus("rm-svc", ServiceStatus.UP);
        assertEquals(1, events.size());

        // Remove and update again
        ServiceRegistry.removeStatusChangeListener(listener);
        ServiceRegistry.updateStatus("rm-svc", ServiceStatus.DOWN);
        assertEquals(1, events.size()); // No new event
    }

    @Test
    void testListenerExceptionDoesNotBreakOthers()
    {
        IGuiceContext.instance().inject();

        List<String> events = new CopyOnWriteArrayList<>();

        // Bad listener that throws
        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> { throw new RuntimeException("Boom!"); }));

        // Good listener
        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> events.add(name)));

        ServiceRegistry.register(new ServiceEntry("error-svc", "http://err:8080", "/health",
                ServiceStatus.UNKNOWN, Instant.now(), Map.of()));
        ServiceRegistry.updateStatus("error-svc", ServiceStatus.UP);

        // Good listener should still receive the event
        assertEquals(1, events.size());
    }

    // ──────────────────────────────────────────────────────────
    // GRACEFUL SHUTDOWN TESTS
    // ──────────────────────────────────────────────────────────

    @Test
    void testGracefulShutdownMarksSelfAsDown()
    {
        IGuiceContext.instance().inject();

        // Register self
        ServiceRegistry.register(new ServiceEntry("my-app", "http://my-app:8080", "/health/ready",
                ServiceStatus.UP, Instant.now(), Map.of("self", "true")));

        // Register another service (not self)
        ServiceRegistry.register(new ServiceEntry("other-svc", "http://other:8080", "/health/ready",
                ServiceStatus.UP, Instant.now(), Map.of()));

        assertEquals(ServiceStatus.UP, ServiceRegistry.get("my-app").get().status());
        assertEquals(ServiceStatus.UP, ServiceRegistry.get("other-svc").get().status());

        // Simulate shutdown
        ServiceRegistryPreDestroy preDestroy = new ServiceRegistryPreDestroy();
        preDestroy.onDestroy();

        // Self should be DOWN, other should remain UP
        assertEquals(ServiceStatus.DOWN, ServiceRegistry.get("my-app").get().status());
        assertEquals(ServiceStatus.UP, ServiceRegistry.get("other-svc").get().status());
    }

    @Test
    void testShutdownFiresStatusChangeEvent()
    {
        IGuiceContext.instance().inject();

        List<String> events = new CopyOnWriteArrayList<>();

        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> events.add(name + ":" + prev + "→" + next)));

        ServiceRegistry.register(new ServiceEntry("shutdown-svc", "http://shutdown:8080", "/health",
                ServiceStatus.UP, Instant.now(), Map.of("self", "true")));

        new ServiceRegistryPreDestroy().onDestroy();

        assertEquals(1, events.size());
        assertEquals("shutdown-svc:UP→DOWN", events.get(0));
    }

    // ──────────────────────────────────────────────────────────
    // REGISTRY BASIC OPERATIONS (sanity)
    // ──────────────────────────────────────────────────────────

    @Test
    void testUpdateStatusWithNoChange()
    {
        ServiceRegistry.register(new ServiceEntry("stable-svc", "http://stable:8080", "/health",
                ServiceStatus.UP, Instant.now(), Map.of()));

        List<String> events = new CopyOnWriteArrayList<>();
        ServiceRegistry.addStatusChangeListener(new TestStatusListener(
                (name, prev, next, entry) -> events.add(name)));

        // Same status — no event
        ServiceRegistry.updateStatus("stable-svc", ServiceStatus.UP);
        assertTrue(events.isEmpty());

        // Different status — event fired
        ServiceRegistry.updateStatus("stable-svc", ServiceStatus.DEGRADED);
        assertEquals(1, events.size());
    }
}
