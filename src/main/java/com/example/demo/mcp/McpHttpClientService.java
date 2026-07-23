package com.example.demo.mcp;

import com.example.demo.config.HarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class McpHttpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClientService.class);

    private final HarnessProperties properties;
    private final RestTemplate restTemplate;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private List<Map<String, Object>> availableTools = Collections.emptyList();
    private String mcpSessionId = null;

    public McpHttpClientService(HarnessProperties properties, Tracer tracer) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getMcp().getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getMcp().getReadTimeoutMs());
        this.restTemplate = new RestTemplate(requestFactory);
        this.tracer = tracer;
        this.objectMapper = new ObjectMapper();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadToolsOnStartup() {
        String serverUrl = properties.getMcp().getServerUrl();
        log.info("Harness starting: loading available tools from MCP server at {}", serverUrl);
        try {
            Map<String, Object> response = listTools();
            if (response == null || response.containsKey("error")) {
                String errorDetails = response != null && response.get("error") != null
                        ? response.get("error").toString()
                        : "No response received";
                log.warn("MCP server is not reachable at {}: {}", serverUrl, errorDetails);
                this.availableTools = Collections.emptyList();
                return;
            }

            List<Map<String, Object>> tools = extractTools(response);
            this.availableTools = tools;

            if (tools.isEmpty()) {
                log.warn("MCP server at {} is reachable, but tool count is zero", serverUrl);
            } else {
                log.info("Successfully loaded {} tools from MCP server at {}", tools.size(), serverUrl);
            }
        } catch (Exception e) {
            log.warn("MCP server is not reachable at {}: {}", serverUrl, e.getMessage());
            this.availableTools = Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getAvailableTools() {
        return availableTools;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractTools(Map<String, Object> response) {
        if (response == null || response.containsKey("error")) {
            return Collections.emptyList();
        }
        Object toolsObj = null;
        if (response.containsKey("tools")) {
            toolsObj = response.get("tools");
        } else if (response.containsKey("result") && response.get("result") instanceof Map) {
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            toolsObj = result.get("tools");
        }

        if (toolsObj instanceof List) {
            return (List<Map<String, Object>>) toolsObj;
        }
        return Collections.emptyList();
    }

    public Map<String, Object> listTools() {
        return listToolsInternal(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listToolsInternal(boolean allowRetry) {
        ensureSessionInitialized();

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

            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getMcp().getServerUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            updateSessionIdFromResponse(response.getHeaders());
            span.setAttribute("mcp.response.status", response.getStatusCode().value());
            Map<String, Object> body = parseResponse(response.getBody());

            if (body != null && body.toString().contains("Session not found") && allowRetry) {
                log.warn("Session expired or invalid on server. Re-initializing session...");
                this.mcpSessionId = null;
                return listToolsInternal(false);
            }

            if (body != null && !body.containsKey("error")) {
                this.availableTools = extractTools(body);
            }
            return body;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Session not found") && allowRetry) {
                log.warn("Session expired or invalid on server. Re-initializing session...");
                this.mcpSessionId = null;
                return listToolsInternal(false);
            }
            span.recordException(e);
            log.warn("Failed to list tools from MCP server at {}: {}", properties.getMcp().getServerUrl(), e.getMessage());
            return Map.of("error", e.getMessage());
        } finally {
            span.end();
        }
    }

    public String executeTool(String toolName, Map<String, Object> arguments) {
        return executeToolInternal(toolName, arguments, true);
    }

    private String executeToolInternal(String toolName, Map<String, Object> arguments, boolean allowRetry) {
        ensureSessionInitialized();

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

            updateSessionIdFromResponse(response.getHeaders());
            span.setAttribute("mcp.response.status", response.getStatusCode().value());
            String body = response.getBody();

            if (body != null && body.contains("Session not found") && allowRetry) {
                log.warn("Session expired during tool execution. Re-initializing session...");
                this.mcpSessionId = null;
                return executeToolInternal(toolName, arguments, false);
            }

            return body;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Session not found") && allowRetry) {
                log.warn("Session expired during tool execution. Re-initializing session...");
                this.mcpSessionId = null;
                return executeToolInternal(toolName, arguments, false);
            }
            span.recordException(e);
            log.error("Error executing MCP tool {}: {}", toolName, e.getMessage(), e);
            return "Error executing tool " + toolName + ": " + e.getMessage();
        } finally {
            span.end();
        }
    }

    private synchronized void ensureSessionInitialized() {
        if (this.mcpSessionId != null) {
            return;
        }
        String serverUrl = properties.getMcp().getServerUrl();
        log.info("Initializing MCP session with server at {}", serverUrl);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
            if (properties.getMcp().getHeaders() != null) {
                properties.getMcp().getHeaders().forEach((k, v) -> {
                    if (v != null && !v.isBlank()) headers.add(k, v);
                });
            }

            Map<String, Object> initPayload = Map.of(
                    "jsonrpc", "2.0",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "java-harness-demo", "version", "1.0.0")
                    ),
                    "id", System.currentTimeMillis()
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(initPayload, headers);
            ResponseEntity<String> response = restTemplate.exchange(serverUrl, HttpMethod.POST, entity, String.class);

            updateSessionIdFromResponse(response.getHeaders());
            if (this.mcpSessionId != null) {
                log.info("Established MCP session ID: {}", this.mcpSessionId);
                try {
                    HttpHeaders initHeaders = createHeaders();
                    Map<String, Object> initializedNotification = Map.of(
                            "jsonrpc", "2.0",
                            "method", "notifications/initialized"
                    );
                    restTemplate.exchange(serverUrl, HttpMethod.POST, new HttpEntity<>(initializedNotification, initHeaders), String.class);
                } catch (Exception e) {
                    log.debug("Notification 'initialized' sent: {}", e.getMessage());
                }
            } else {
                log.warn("MCP server at {} did not return a session ID during initialize handshake", serverUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize MCP session at {}: {}", serverUrl, e.getMessage());
        }
    }

    private void updateSessionIdFromResponse(HttpHeaders headers) {
        if (headers == null) return;
        String respSessionId = headers.getFirst("Mcp-Session-Id");
        if (respSessionId == null) {
            respSessionId = headers.getFirst("mcp-session-id");
        }
        if (respSessionId != null && !respSessionId.isBlank()) {
            this.mcpSessionId = respSessionId;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        String content = rawBody.trim();
        if (content.contains("data:")) {
            int idx = content.indexOf("data:");
            content = content.substring(idx + 5).trim();
            int endIdx = content.indexOf("\n");
            if (endIdx != -1) {
                content = content.substring(0, endIdx).trim();
            }
        }
        try {
            return objectMapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse MCP JSON response: {}", e.getMessage());
            return Map.of("raw", rawBody);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (mcpSessionId != null && !mcpSessionId.isBlank()) {
            headers.add("Mcp-Session-Id", mcpSessionId);
            headers.add("mcp-session-id", mcpSessionId);
        }
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




