package com.example.demo.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
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
        try {
            String tracesEndpoint = otlpEndpoint.endsWith("/v1/traces") ? otlpEndpoint : otlpEndpoint + "/v1/traces";
            spanExporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(tracesEndpoint)
                    .build();
            log.info("Initialized OpenTelemetry HTTP SpanExporter pointing to {}", tracesEndpoint);
        } catch (Exception e) {
            log.warn("Failed to initialize OtlpHttpSpanExporter ({}), using Noop span exporter", e.getMessage());
            spanExporter = SpanExporter.composite();
        }

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .buildAndRegisterGlobal();
    }

    @Bean
    public Tracer harnessTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.example.demo.harness", "1.0.0");
    }
}
