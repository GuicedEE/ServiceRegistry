@com.guicedee.runtime.autoconfigure.AzureContainerApps
@com.guicedee.service.registry.ServiceRegistryOptions(
        healthCheckInterval = 10,
        healthPath = "/",
        registerSelf = true,
        useHttps = true
)
@com.guicedee.service.registry.RegisteredService(name = "jwebmp-website",
        aliases = {"jwebmp", "jwebswing"},
        externalUrls = {"https://jwebmp.com", "https://jwebswing.com"},
        kubernetesUrl = "http://jwebmp-website.default.svc.cluster.local",
        openApiPath = "/openapi.json",
        openApiEnvironments = {"dev", "int", "prod"})
@com.guicedee.service.registry.RegisteredService(name = "guicedee-website",
        aliases = {"guicedee"},
        externalUrl = "https://guicedee.com",
        kubernetesUrl = "http://guicedee-website.default.svc.cluster.local",
        openApiPath = "/openapi.json")
@com.guicedee.service.registry.RegisteredService(name = "hello-service",
        url = "http://hello-service.service-discovery-test.svc.cluster.local",
        healthPath = "/healthz",
        kubernetesUrl = "http://hello-service.service-discovery-test.svc.cluster.local")
package com.guicedee.service.registry.test;
