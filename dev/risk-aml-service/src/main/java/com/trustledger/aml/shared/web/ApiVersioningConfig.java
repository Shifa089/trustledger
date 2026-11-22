package com.trustledger.aml.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.RequestPath;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Path versioning as in core-app (SPEC §5): every controller under {@code com.trustledger.aml} gets the prefix
 * {@code /api/v{version}} and declares {@code version = "1"} on its mappings. The version is read from path segment
 * 1 only for {@code /api/**}; only version 1 exists, so {@code /api/v2/...} is a 400.
 */
@Configuration(proxyBeanMethods = false)
public class ApiVersioningConfig implements WebMvcConfigurer {

    public static final String PREFIX = "/api/v{version}";
    private static final String API_ROOT = "/api/";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forBasePackage("com.trustledger.aml"));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.usePathSegment(1, ApiVersioningConfig::isApiPath)
                .addSupportedVersions("1")
                .detectSupportedVersions(false)
                .setVersionRequired(false);
    }

    static boolean isApiPath(RequestPath path) {
        return path.pathWithinApplication().value().startsWith(API_ROOT);
    }
}
