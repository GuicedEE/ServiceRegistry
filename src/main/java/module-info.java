import com.guicedee.client.services.lifecycle.IGuicePreStartup;
import com.guicedee.client.services.lifecycle.IGuicePostStartup;
import com.guicedee.client.services.lifecycle.IGuicePreDestroy;
import com.guicedee.service.registry.implementations.ServiceRegistryPreStartup;
import com.guicedee.service.registry.implementations.ServiceRegistryPostStartup;
import com.guicedee.service.registry.implementations.ServiceRegistryPreDestroy;
import com.guicedee.service.registry.IServiceRegistryProvider;
import com.guicedee.service.registry.IServiceStatusChangeListener;

module com.guicedee.service.registry {
    exports com.guicedee.service.registry;

    requires transitive com.guicedee.client;
    requires transitive com.guicedee.vertx;
    requires io.vertx.web.client;
    requires io.smallrye.mutiny;
    requires org.apache.logging.log4j;
    requires static lombok;

    requires static com.guicedee.runtime.autoconfigure;
    requires static com.guicedee.vertx.servicediscovery;

    provides IGuicePreStartup with ServiceRegistryPreStartup;
    provides IGuicePostStartup with ServiceRegistryPostStartup;
    provides IGuicePreDestroy with ServiceRegistryPreDestroy;

    uses IServiceRegistryProvider;
    uses IServiceStatusChangeListener;

    opens com.guicedee.service.registry to com.google.guice;
    opens com.guicedee.service.registry.implementations to com.google.guice;

    exports com.guicedee.service.registry.implementations to com.guicedee.service.registry.test;
}



