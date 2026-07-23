package com.example.demo.harness;

import com.example.demo.config.HarnessProperties;
import com.example.demo.cron.CronTaskService;
import com.example.demo.llm.OpenAiLlmClientService;
import com.example.demo.mcp.McpHttpClientService;
import com.example.demo.memory.MemoryService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarnessEngineTest {

    private OpenAiLlmClientService llmClient;
    private McpHttpClientService mcpClient;
    private MemoryService memoryService;
    private HarnessProperties properties;
    private Tracer tracer;
    private CronTaskService cronTaskService;
    private HarnessEngine harnessEngine;

    @BeforeEach
    void setUp() {
        llmClient = mock(OpenAiLlmClientService.class);
        mcpClient = mock(McpHttpClientService.class);
        memoryService = mock(MemoryService.class);
        cronTaskService = mock(CronTaskService.class);
        properties = new HarnessProperties();
        tracer = OpenTelemetry.noop().getTracer("test");

        harnessEngine = new HarnessEngine(llmClient, mcpClient, memoryService, properties, tracer, cronTaskService);
    }

    @Test
    void testFormatToolsForOpenAi_ConvertsMcpToolSpecs() {
        List<Map<String, Object>> mcpTools = List.of(
                Map.of(
                        "name", "search_users",
                        "description", "Find users by location",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("location", Map.of("type", "string")))
                )
        );

        List<Map<String, Object>> openAiTools = harnessEngine.formatToolsForOpenAi(mcpTools);
        assertEquals(1, openAiTools.size());

        Map<String, Object> toolSpec = openAiTools.get(0);
        assertEquals("function", toolSpec.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> functionMap = (Map<String, Object>) toolSpec.get("function");
        assertEquals("search_users", functionMap.get("name"));
        assertEquals("Find users by location", functionMap.get("description"));
        assertNotNull(functionMap.get("parameters"));
    }

    @Test
    void testExecuteIntent_SendsMcpToolsToLlm() {
        List<Map<String, Object>> mcpTools = List.of(
                Map.of(
                        "name", "search_users",
                        "description", "Find users by location",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("location", Map.of("type", "string")))
                )
        );

        when(mcpClient.listTools()).thenReturn(Map.of("tools", mcpTools));
        when(mcpClient.extractTools(any())).thenReturn(mcpTools);
        when(memoryService.loadMemory()).thenReturn("");

        Map<String, Object> llmChoiceMsg = Map.of(
                "role", "assistant",
                "content", "Done"
        );
        Map<String, Object> llmResponse = Map.of("choices", List.of(Map.of("message", llmChoiceMsg)));

        when(llmClient.chatCompletion(anyList(), anyList())).thenReturn(llmResponse);

        harnessEngine.executeIntent("Find users in Miami");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chatCompletion(anyList(), toolsCaptor.capture());

        List<Map<String, Object>> capturedTools = toolsCaptor.getValue();
        assertNotNull(capturedTools);
        assertFalse(capturedTools.isEmpty());
        // 1 MCP tool + 3 internal tools = 4 total tools sent to LLM
        assertEquals(4, capturedTools.size());
    }

    @Test
    void testExecuteIntent_ExecutesToolCallWithJsonStringArguments() {
        List<Map<String, Object>> mcpTools = List.of(
                Map.of("name", "search_users", "description", "Find users")
        );
        when(mcpClient.listTools()).thenReturn(Map.of("tools", mcpTools));
        when(mcpClient.extractTools(any())).thenReturn(mcpTools);
        when(memoryService.loadMemory()).thenReturn("");

        // Step 1: LLM returns tool_call with arguments string
        Map<String, Object> toolCall = Map.of(
                "id", "call_abc",
                "type", "function",
                "function", Map.of(
                        "name", "search_users",
                        "arguments", "{\"location\":\"Miami\"}"
                )
        );
        Map<String, Object> firstLlmMsg = Map.of(
                "role", "assistant",
                "tool_calls", List.of(toolCall)
        );
        Map<String, Object> firstLlmResp = Map.of("choices", List.of(Map.of("message", firstLlmMsg)));

        // Step 2: LLM returns final answer
        Map<String, Object> secondLlmMsg = Map.of("role", "assistant", "content", "Found 2 users");
        Map<String, Object> secondLlmResp = Map.of("choices", List.of(Map.of("message", secondLlmMsg)));

        when(llmClient.chatCompletion(anyList(), anyList()))
                .thenReturn(firstLlmResp)
                .thenReturn(secondLlmResp);

        when(mcpClient.executeTool(eq("search_users"), anyMap()))
                .thenReturn("{\"users\": [\"Alice\", \"Bob\"]}");

        Map<String, Object> result = harnessEngine.executeIntent("Find users in Miami");
        assertEquals("Found 2 users", result.get("finalAnswer"));

        verify(mcpClient).executeTool(eq("search_users"), eq(Map.of("location", "Miami")));
    }

    @Test
    void testExecuteIntent_ExecutesInternalScheduleCronTool() {
        when(mcpClient.listTools()).thenReturn(Map.of("tools", List.of()));
        when(mcpClient.extractTools(any())).thenReturn(List.of());
        when(memoryService.loadMemory()).thenReturn("");

        Map<String, Object> toolCall = Map.of(
                "id", "call_cron_123",
                "type", "function",
                "function", Map.of(
                        "name", HarnessEngine.TOOL_SCHEDULE_CRON_INTENT,
                        "arguments", Map.of("jobId", "metric-job", "intent", "Check system status", "intervalSeconds", 60)
                )
        );
        Map<String, Object> firstLlmMsg = Map.of("role", "assistant", "tool_calls", List.of(toolCall));
        Map<String, Object> firstLlmResp = Map.of("choices", List.of(Map.of("message", firstLlmMsg)));

        Map<String, Object> secondLlmMsg = Map.of("role", "assistant", "content", "Cron job metric-job scheduled");
        Map<String, Object> secondLlmResp = Map.of("choices", List.of(Map.of("message", secondLlmMsg)));

        when(llmClient.chatCompletion(anyList(), anyList()))
                .thenReturn(firstLlmResp)
                .thenReturn(secondLlmResp);

        when(cronTaskService.scheduleJob(eq("metric-job"), eq("Check system status"), isNull(), eq(60L)))
                .thenReturn(Map.of("status", "SUCCESS", "jobId", "metric-job"));

        Map<String, Object> result = harnessEngine.executeIntent("Schedule system check every minute");
        assertEquals("Cron job metric-job scheduled", result.get("finalAnswer"));

        verify(cronTaskService).scheduleJob("metric-job", "Check system status", null, 60L);
        verify(mcpClient, never()).executeTool(eq(HarnessEngine.TOOL_SCHEDULE_CRON_INTENT), anyMap());
    }
}
