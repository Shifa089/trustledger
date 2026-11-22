package com.trustledger.aml.shared.observability;

import static com.trustledger.aml.Eventually.await;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import com.trustledger.aml.TestTelemetry;
import com.trustledger.aml.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * M12 wiring in risk-aml-service (ADR 0020), as in core-app: log lines reach OpenTelemetry and the console with the
 * current trace and span ID, sampling is 100%, and nothing is exported over OTLP without the {@code obs} profile.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityConfigIT {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigIT.class);

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Autowired
    ObservationRegistry observations;

    @Autowired
    Tracer tracer;

    @Autowired
    TestTelemetry telemetry;

    @BeforeEach
    void record() {
        telemetry.start();
    }

    @AfterEach
    void stopRecording() {
        telemetry.stop();
    }

    @Test
    void aLogLineCarriesTheCurrentTraceAndSpanToOpenTelemetryAndTheConsole(CapturedOutput output) {
        String marker = "otel-log-test-" + UUID.randomUUID();
        Observation observation = Observation.start("test.logging", observations);
        TraceContext span;
        try (Observation.Scope scope = observation.openScope()) {
            span = tracer.currentSpan().context();
            log.info("Marker {}", marker);
        } finally {
            observation.stop();
        }

        await(Duration.ofSeconds(10), "the log record in OpenTelemetry", () -> telemetry.logs().stream()
                .anyMatch(record -> record.getBodyValue().asString().equals("Marker " + marker)));
        assertThat(telemetry.logs()).filteredOn(record -> record.getBodyValue().asString().equals("Marker " + marker))
                .singleElement().satisfies(record -> {
                    assertThat(record.getSpanContext().getTraceId()).isEqualTo(span.traceId());
                    assertThat(record.getSpanContext().getSpanId()).isEqualTo(span.spanId());
                });
        assertThat(output.getOut().lines().filter(line -> line.contains(marker)))
                .singleElement().satisfies(line -> assertThat(line).contains(span.traceId(), span.spanId()));
    }

    @Test
    void theOpenTelemetryAppenderIsOnTheRootLoggerOnce() {
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = logback.getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(root.getAppender(OpenTelemetryLogExportConfig.APPENDER_NAME))
                .isInstanceOf(OpenTelemetryAppender.class)
                .satisfies(appender -> assertThat(appender.isStarted()).isTrue());
        OpenTelemetryLogExportConfig.attach(context.getBean(OpenTelemetry.class));
        assertThat(root.iteratorForAppenders()).toIterable()
                .filteredOn(appender -> appender instanceof OpenTelemetryAppender).hasSize(1);
    }

    @Test
    void everythingIsSampledAndNothingIsExportedOverOtlpWithoutTheObsProfile() {
        assertThat(environment.getProperty("management.tracing.sampling.probability", Double.class))
                .isEqualTo(1.0);
        assertThat(context.getBeansOfType(SpanExporter.class).values())
                .noneMatch(exporter -> exporter.getClass().getSimpleName().startsWith("Otlp"));
        assertThat(context.getBeansOfType(LogRecordExporter.class).values())
                .noneMatch(exporter -> exporter.getClass().getSimpleName().startsWith("Otlp"));
        assertThat(context.getBeansOfType(MeterRegistry.class).values())
                .noneMatch(registry -> registry.getClass().getSimpleName().startsWith("Otlp"));
    }
}
