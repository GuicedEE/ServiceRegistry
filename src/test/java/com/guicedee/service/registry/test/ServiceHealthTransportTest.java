package com.guicedee.service.registry.test;

import com.guicedee.service.registry.*;
import com.guicedee.service.registry.implementations.ServiceRegistryPostStartup;
import com.guicedee.vertx.spi.VertXPreStartup;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.services.lifecycle.*;
import com.guicedee.guicedinjection.GuiceContext;
import com.google.inject.AbstractModule;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PfxOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback peers exercise the client created by the production polling hook. */
class ServiceHealthTransportTest {
    @TempDir Path directory;

    private void certificate(Path store, Path passwordFile) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        var process = new ProcessBuilder(executable, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass:file", passwordFile.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void verifiesTrustAndHostAndRefusesRedirects() throws Exception {
        String password = UUID.randomUUID().toString();
        Path secret = directory.resolve("password"), trusted = directory.resolve("trusted.p12"),
                untrusted = directory.resolve("untrusted.p12");
        Files.writeString(secret, password);
        certificate(trusted, secret);
        certificate(untrusted, secret);
        var previous = new java.util.HashMap<String, String>();
        Map.of("javax.net.ssl.trustStore", trusted.toString(), "javax.net.ssl.trustStorePassword", password,
                "javax.net.ssl.trustStoreType", "PKCS12").forEach((key, value) ->
                previous.put(key, System.setProperty(key, value)));
        Class.forName("com.guicedee.client.scopes.CallScoper");
        // HTTP handlers enter GuicedEE call scope. Supply its small injector explicitly
        // so the unrelated cloud integration declarations cannot bootstrap on first I/O.
        var context = GuiceContext.instance();
        IGuiceContext.contexts.put("default", context);
        context.getConfig().setClasspathScanning(false).setServiceLoadWithClassPath(false);
        var services = IGuiceContext.getAllLoadedServices();
        services.put(IGuiceConfigurator.class, java.util.Set.of());
        services.put(IGuicePreStartup.class, java.util.Set.of());
        services.put(IGuicePostStartup.class, java.util.Set.of());
        services.put(IGuicePreDestroy.class, java.util.Set.of());
        services.put(IGuiceModule.class, java.util.Set.of());
        var runtime = Vertx.vertx();
        var owner = VertXPreStartup.class.getDeclaredField("vertx");
        owner.setAccessible(true);
        Object previousOwner = owner.get(null);
        assertNull(previousOwner, "Fixture must own an isolated runtime");
        var requests = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        try {
            owner.set(null, runtime);
            IGuiceContext.modules.add(new AbstractModule() {
                @Override protected void configure() { bind(Vertx.class).toInstance(runtime); }
            });
            context.inject();
            context.getLoadingFinished().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            ServiceRegistry.clear();
            var peer = runtime.createHttpServer(new HttpServerOptions().setSsl(true)
                    .setKeyCertOptions(new PfxOptions().setPath(trusted.toString()).setPassword(password)))
                    .requestHandler(request -> {
                        requests.computeIfAbsent(request.path(), ignored -> new AtomicInteger()).incrementAndGet();
                        if (request.path().equals("/redirect"))
                            request.response().setStatusCode(302).putHeader("Location", "/redirect-target").end();
                        else request.response().putHeader("Content-Type", "application/json")
                                .end("{\"status\":\"UP\",\"checks\":[{\"name\":\"fixture\",\"status\":\"UP\"}]}");
                    }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var badPeer = runtime.createHttpServer(new HttpServerOptions().setSsl(true)
                    .setKeyCertOptions(new PfxOptions().setPath(untrusted.toString()).setPassword(password)))
                    .requestHandler(request -> {
                        requests.computeIfAbsent("untrusted", ignored -> new AtomicInteger()).incrementAndGet();
                        request.response().end("{}");
                    }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            String base = "https://localhost:" + peer.actualPort();
            for (var target : Map.of("trusted", base + "/health", "redirect", base + "/redirect",
                    "hostname", "https://127.0.0.1:" + peer.actualPort() + "/wrong-host",
                    "untrusted", "https://localhost:" + badPeer.actualPort() + "/untrusted").entrySet()) {
                ServiceRegistry.register(new ServiceEntry(target.getKey(), "https://application.example", "/health",
                        ServiceStatus.UNKNOWN, Instant.now(), Map.of("healthUrl", target.getValue(),
                        "expectedStatusCode", target.getKey().equals("redirect") ? "302" : "200")));
            }
            new ServiceRegistryPostStartup().postLoad();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (ServiceRegistry.all().values().stream().anyMatch(entry -> entry.status() == ServiceStatus.UNKNOWN)
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(ServiceStatus.UP, ServiceRegistry.get("trusted").orElseThrow().status());
            for (String name : java.util.List.of("redirect", "hostname", "untrusted"))
                assertEquals(ServiceStatus.DOWN, ServiceRegistry.get(name).orElseThrow().status(), name);
            assertEquals(1, requests.get("/health").get());
            assertEquals(1, requests.get("/redirect").get());
            assertFalse(requests.containsKey("/redirect-target"));
            assertFalse(requests.containsKey("/wrong-host"));
            assertFalse(requests.containsKey("untrusted"));
        } finally {
            ServiceRegistryPostStartup.stop();
            context.destroy();
            runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            owner.set(null, previousOwner);
            ServiceRegistry.clear();
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }
}
