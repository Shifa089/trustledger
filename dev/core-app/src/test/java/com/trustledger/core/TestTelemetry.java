package com.trustledger.core;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;

/**
 * What the app would send to the OTLP collector, kept in memory for assertions (M12, ADR 0020). Boot hands every
 * {@link SpanExporter} and {@link LogRecordExporter} bean to its OpenTelemetry SDK, next to (in the obs profile) the
 * OTLP ones. Registered in {@link TestcontainersConfiguration}, so all ITs share one context.
 * <p>
 * Records only between {@link #start()} and {@link #stop()}: the other ITs produce spans and log lines too, and
 * keeping them all would grow without bound over the run.
 */
public final class TestTelemetry {

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
    private volatile boolean recording;

    /** Forgets what was recorded so far and records from now on. */
    public void start() {
        spans.reset();
        logs.reset();
        recording = true;
    }

    public void stop() {
        recording = false;
    }

    public List<SpanData> spans() {
        return spans.getFinishedSpanItems();
    }

    public List<SpanData> spansOfTrace(String traceId) {
        return spans().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    public List<LogRecordData> logs() {
        return logs.getFinishedLogRecordItems();
    }

    public SpanExporter spanExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                return recording ? spans.export(batch) : CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    public LogRecordExporter logRecordExporter() {
        return new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> batch) {
                return recording ? logs.export(batch) : CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
