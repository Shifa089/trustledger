package com.trustledger.aml.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Log lines to OpenTelemetry (M12, ADR 0020), as in core-app: an {@link OpenTelemetryAppender} on the root logger,
 * fed by Boot's {@link OpenTelemetry} SDK, whose log exporter sends the records over OTLP to Loki (profile
 * {@code obs}). Each record carries the current trace and span ID. Attached in code so Boot's own console setup stays
 * untouched; the appender sees exactly what the console sees (no personal data), and of the MDC only the correlation
 * ID is copied.
 */
@Configuration(proxyBeanMethods = false)
public class OpenTelemetryLogExportConfig {

    /** Name of the appender on the root logger. */
    public static final String APPENDER_NAME = "OTEL";

    /** The MDC key the Kafka listeners set from the record's {@code correlationId} header. */
    static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Bean
    InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> attach(openTelemetry);
    }

    /** Adds the appender once per Logback context and points every OpenTelemetry appender at this SDK. */
    static void attach(OpenTelemetry openTelemetry) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(APPENDER_NAME);
            appender.setContext(context);
            appender.setCaptureMdcAttributes(CORRELATION_ID_MDC_KEY);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(openTelemetry);
    }
}
