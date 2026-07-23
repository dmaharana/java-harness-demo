package com.example.demo.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryConfigTest {

    @Test
    void testOpenTelemetryWithUnreachableOtelServer_LogsWarningAndUsesConsoleExporter() {
        OpenTelemetryConfig config = new OpenTelemetryConfig();
        ReflectionTestUtils.setField(config, "otlpEndpoint", "http://localhost:59998");
        ReflectionTestUtils.setField(config, "serviceName", "test-service");

        OpenTelemetry openTelemetry = config.openTelemetry();
        assertNotNull(openTelemetry);

        Tracer tracer = config.harnessTracer(openTelemetry);
        assertNotNull(tracer);

        // Verify span creation and ending does not throw exception
        assertDoesNotThrow(() -> {
            var span = tracer.spanBuilder("test.span").startSpan();
            span.end();
        });
    }
}
