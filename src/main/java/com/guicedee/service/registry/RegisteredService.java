package com.guicedee.service.registry;

import java.lang.annotation.*;

/**
 * Declares a known service that should be registered in the service registry.
 * <p>
 * If only {@code name} is provided, the URL is auto-constructed from the DNS suffix.
 * If {@code url} is provided, it is used as-is (supports ${ENV_VAR} placeholders).
 * <p>
 * Services can have multiple aliases and external URLs:
 * <pre>{@code
 * @RegisteredService(name = "jwebmp-website",
 *     aliases = {"jwebmp", "jwebswing"},
 *     externalUrl = "https://jwebmp.com")
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Repeatable(RegisteredServices.class)
public @interface RegisteredService
{
    /** @return The simple logical name for this service. */
    String name();

    /** @return Full URL. If empty, auto-constructed from DNS suffix. Supports ${ENV_VAR}. */
    String url() default "";

    /** @return Health check path override. If empty, uses the registry default. */
    String healthPath() default "";

    /**
     * @return The HTTP status code that indicates the service is healthy.
     * Default is 200. For services that return 204, 202, etc., set accordingly.
     */
    int expectedStatusCode() default 200;

    /**
     * @return The expected response content type from the health endpoint.
     * Default is "application/json" (MicroProfile Health JSON — enables deep detail parsing).
     * Set to "text/html" or other types for services that don't return JSON —
     * status will still be reported accurately based on HTTP status code, but
     * individual health check details will not be available.
     */
    String expectedContentType() default "application/json";

    /**
     * @return The revision/version identifier for this service instance.
     * Used for blue/green deployments where multiple instances of the same service
     * may be running simultaneously. If empty, no revision tracking is applied.
     * Supports ${ENV_VAR} placeholders (e.g. "${CONTAINER_APP_REVISION}").
     */
    String revision() default "";

    /** @return Alternative names that also resolve to this service. */
    String[] aliases() default {};

    /** @return External/public URLs (custom domains). If empty, not registered. Supports ${ENV_VAR}. */
    String[] externalUrls() default {};

    /** @return Single external URL shorthand — convenience for one URL. Supports ${ENV_VAR}. */
    String externalUrl() default "";

    /** @return Kubernetes internal cluster URL. If empty, not registered. Supports ${ENV_VAR}. */
    String kubernetesUrl() default "";

    /** @return OpenAPI spec path for this service (e.g. "/openapi.json"). If empty, not available. Supports ${ENV_VAR}. */
    String openApiPath() default "";

    /** @return Environments where this service's OpenAPI spec should be merged (e.g. {"dev", "int", "prod"}). Empty = all environments. */
    String[] openApiEnvironments() default {};

    /**
     * @return The authentication scheme type this service requires.
     * Supported values: "bearer", "basic", "apiKey", "oauth2", "openIdConnect", or empty (none).
     * When set, a corresponding OpenAPI SecurityScheme is auto-generated during spec merge.
     * For oauth2, set {@link #authTokenUrl()} as well.
     */
    String authScheme() default "";

    /**
     * @return The OAuth2 token URL (for authScheme="oauth2"). Supports ${ENV_VAR}.
     * Only used when {@link #authScheme()} is "oauth2".
     */
    String authTokenUrl() default "";

    /**
     * @return The OAuth2 scopes this service requires, as "scope=description" pairs.
     * Only used when {@link #authScheme()} is "oauth2".
     * Example: {"read=Read access", "write=Write access"}
     */
    String[] authScopes() default {};

    /** @return GraphQL endpoint path for this service (e.g. "/graphql"). If empty, GraphQL is not available. Supports ${ENV_VAR}. */
    String graphqlPath() default "";

    /** @return Environments where this service's GraphQL schema should be merged (e.g. {"dev", "int"}). Empty = all environments. */
    String[] graphqlEnvironments() default {};
}
