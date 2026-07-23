package com.example.demo.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}")
    private String otlpEndpoint;

    @Value("${otel.service.name:java-harness-demo}")
    private String serviceName;

    @Bean
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.builder()
                        .put("service.name", serviceName)
                        .build())
        );

        SpanExporter spanExporter;
        String tracesEndpoint = otlpEndpoint.endsWith("/v1/traces") ? otlpEndpoint : otlpEndpoint + "/v1/traces";

        if (isServerReachable(otlpEndpoint)) {
            try {
                spanExporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(tracesEndpoint)
                        .build();
                log.info("Initialized OpenTelemetry HTTP SpanExporter pointing to {}", tracesEndpoint);
            } catch (Exception e) {
                log.warn("Failed to initialize OtlpHttpSpanExporter ({}), using console exporter", e.getMessage());
                spanExporter = LoggingSpanExporter.create();
            }
        } else {
            log.warn("Configured OTEL server at {} is not available/reachable. Falling back to console exporter.", otlpEndpoint);
            spanExporter = LoggingSpanExporter.create();
        }

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .build();
    }

    @Bean
    public Tracer harnessTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.example.demo.harness", "1.0.0");
    }

    private boolean isServerReachable(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
