package com.example.demo.mcp;

import com.example.demo.config.HarnessProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class McpHttpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClientService.class);

    private final HarnessProperties properties;
    private final RestTemplate restTemplate;
    private final Tracer tracer;

    public McpHttpClientService(HarnessProperties properties, Tracer tracer) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        this.tracer = tracer;
    }

    public Map<String, Object> listTools() {
        Span span = tracer.spanBuilder("mcp.tools_list")
                .setAttribute("mcp.server.url", properties.getMcp().getServerUrl())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            HttpHeaders headers = createHeaders();
            Map<String, Object> requestPayload = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/list",
                    "params", Map.of(),
                    "id", System.currentTimeMillis()
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);
            log.info("Fetching MCP tools from {}", properties.getMcp().getServerUrl());

            ResponseEntity<Map> response = restTemplate.exchange(
                    properties.getMcp().getServerUrl(),
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            span.setAttribute("mcp.response.status", response.getStatusCode().value());
            return response.getBody();
        } catch (Exception e) {
            span.recordException(e);
            log.warn("Failed to list tools from MCP server at {}: {}", properties.getMcp().getServerUrl(), e.getMessage());
            return Map.of("error", e.getMessage());
        } finally {
            span.end();
        }
    }

    public String executeTool(String toolName, Map<String, Object> arguments) {
        Span span = tracer.spanBuilder("mcp.tool_call")
                .setAttribute("mcp.tool.name", toolName)
                .setAttribute("mcp.server.url", properties.getMcp().getServerUrl())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            HttpHeaders headers = createHeaders();
            Map<String, Object> requestPayload = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/call",
                    "params", Map.of(
                            "name", toolName,
                            "arguments", arguments != null ? arguments : Map.of()
                    ),
                    "id", System.currentTimeMillis()
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);
            log.info("Executing MCP tool: {} with args: {}", toolName, arguments);

            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getMcp().getServerUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            span.setAttribute("mcp.response.status", response.getStatusCode().value());
            return response.getBody();
        } catch (Exception e) {
            span.recordException(e);
            log.error("Error executing MCP tool {}: {}", toolName, e.getMessage(), e);
            return "Error executing tool " + toolName + ": " + e.getMessage();
        } finally {
            span.end();
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getMcp().getHeaders() != null) {
            properties.getMcp().getHeaders().forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    headers.add(key, value);
                }
            });
        }
        return headers;
    }
}
