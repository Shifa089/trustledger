package com.trustledger.core.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.RequestPath;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Framework 7 API versioning with the version in the path: {@code /api/v1/...}.
 * <ul>
 * <li>Every controller under {@code com.trustledger.core} gets the prefix {@code /api/v{version}} and
 * declares its version on the mapping, e.g. {@code @GetMapping(path = "/me", version = "1")}.</li>
 * <li>The version is read from path segment 1 <em>only for {@code /api/**}</em>. Without this predicate
 * Spring would parse segment 1 of every path, so {@code /v3/api-docs} would be rejected as version
 * "api-docs". This is why it is configured here and not with {@code spring.mvc.apiversion.*}.</li>
 * <li>The literal {@code v} sits in the prefix, not in the version string, so mappings say
 * {@code version = "1"} and springdoc renders the path as {@code /api/v1/...}.</li>
 * <li>Only version 1 is supported; {@code /api/v2/...} returns 400.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ApiVersioningConfig implements WebMvcConfigurer {

    public static final String PREFIX = "/api/v{version}";
    private static final String API_ROOT = "/api/";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forBasePackage("com.trustledger.core"));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.usePathSegment(1, ApiVersioningConfig::isApiPath)
                .addSupportedVersions("1")
                .detectSupportedVersions(false)
                // Non-API paths (springdoc, error page) carry no version; /api/** always has segment 1.
                .setVersionRequired(false);
    }

    static boolean isApiPath(RequestPath path) {
        return path.pathWithinApplication().value().startsWith(API_ROOT);
    }
}
