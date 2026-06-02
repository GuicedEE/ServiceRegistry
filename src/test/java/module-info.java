open module com.guicedee.service.registry.test {
    requires transitive com.guicedee.service.registry;
    requires com.guicedee.runtime.autoconfigure;
    requires com.guicedee.guicedinjection;
    requires com.google.guice;
    requires io.vertx.core;

    requires org.junit.jupiter;
}

