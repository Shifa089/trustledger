package com.trustledger.core.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Log lines to OpenTelemetry (M12, ADR 0020): attaches an {@link OpenTelemetryAppender} to the root logger and
 * hands it Boot's {@link OpenTelemetry} SDK, whose log exporter sends the records over OTLP to Loki (profile
 * {@code obs}). Each record carries the trace and span ID of the span that was current when it was logged, so Loki
 * lines link to their Tempo trace.
 * <p>
 * Attached in code, not in a {@code logback-spring.xml}: a custom Logback file would replace Boot's own console setup,
 * including the ECS structured format outside the local profile (SPEC §6 "Logging"). The appender sees exactly what
 * the console sees, which is already masked (no personal data); of the MDC only the correlation ID is copied.
 * Without the {@code obs} profile there is no OTLP log exporter, and the records go nowhere.
 */
@Configuration(proxyBeanMethods = false)
public class OpenTelemetryLogExportConfig {

    /** Name of the appender on the root logger. */
    public static final String APPENDER_NAME = "OTEL";

    @Bean
    InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> attach(openTelemetry);
    }

    /**
     * Adds the appender once per Logback context (Boot resets the context when it reinitializes logging, e.g. for a
     * second test context, so the check runs again each time) and points every OpenTelemetry appender at this SDK.
     */
    static void attach(OpenTelemetry openTelemetry) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(APPENDER_NAME);
            appender.setContext(context);
            appender.setCaptureMdcAttributes(CorrelationIdFilter.MDC_KEY);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(openTelemetry);
    }
}
