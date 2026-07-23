package com.example.demo.mcp;

import com.example.demo.config.HarnessProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpClientServiceTest {

    private HarnessProperties harnessProperties;
    private Tracer tracer;
    private McpHttpClientService mcpService;

    @BeforeEach
    void setUp() {
        harnessProperties = new HarnessProperties();
        // Point to non-existent server to test unreachable behavior safely
        harnessProperties.getMcp().setServerUrl("http://localhost:59999/mcp");
        tracer = OpenTelemetry.noop().getTracer("test");
        mcpService = new McpHttpClientService(harnessProperties, tracer);
    }

    @Test
    void testStartupWithUnreachableMcpServer_StartsGracefully() {
        // When MCP server is unreachable, loadToolsOnStartup should not throw exception
        assertDoesNotThrow(() -> mcpService.loadToolsOnStartup());
        assertTrue(mcpService.getAvailableTools().isEmpty());
    }

    @Test
    void testExtractTools_WithValidJsonRpcResult() {
        Map<String, Object> response = Map.of(
                "jsonrpc", "2.0",
                "result", Map.of("tools", List.of(
                        Map.of("name", "calculator", "description", "Math tool")
                ))
        );

        List<Map<String, Object>> tools = mcpService.extractTools(response);
        assertEquals(1, tools.size());
        assertEquals("calculator", tools.get(0).get("name"));
    }

    @Test
    void testExtractTools_WithFlatToolsList() {
        Map<String, Object> response = Map.of(
                "tools", List.of(
                        Map.of("name", "weather", "description", "Weather tool")
                )
        );

        List<Map<String, Object>> tools = mcpService.extractTools(response);
        assertEquals(1, tools.size());
        assertEquals("weather", tools.get(0).get("name"));
    }

    @Test
    void testExtractTools_WithEmptyToolsList() {
        Map<String, Object> response = Map.of(
                "tools", Collections.emptyList()
        );

        List<Map<String, Object>> tools = mcpService.extractTools(response);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testExtractTools_WithErrorResponse() {
        Map<String, Object> response = Map.of(
                "error", "Connection refused"
        );

        List<Map<String, Object>> tools = mcpService.extractTools(response);
        assertTrue(tools.isEmpty());
    }
}
