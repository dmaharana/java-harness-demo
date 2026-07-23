package com.example.demo.controller;

import com.example.demo.config.HarnessProperties;
import com.example.demo.cron.CronTaskService;
import com.example.demo.harness.HarnessEngine;
import com.example.demo.mcp.McpHttpClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarnessControllerTest {

    private HarnessEngine harnessEngine;
    private HarnessProperties harnessProperties;
    private McpHttpClientService mcpHttpClientService;
    private CronTaskService cronTaskService;
    private HarnessController controller;

    @BeforeEach
    void setUp() {
        harnessEngine = mock(HarnessEngine.class);
        harnessProperties = new HarnessProperties();
        mcpHttpClientService = mock(McpHttpClientService.class);
        cronTaskService = mock(CronTaskService.class);

        controller = new HarnessController(harnessEngine, harnessProperties, mcpHttpClientService, cronTaskService);
    }

    @Test
    void testTriggerFlow_Success() {
        when(harnessEngine.executeIntent("Run analytics")).thenReturn(Map.of("finalAnswer", "Analytics complete"));

        ResponseEntity<Map<String, Object>> response = controller.triggerFlow(Map.of("intent", "Run analytics"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("Analytics complete", response.getBody().get("finalAnswer"));
    }

    @Test
    void testTriggerFlow_MissingIntent() {
        ResponseEntity<Map<String, Object>> response = controller.triggerFlow(Map.of());
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void testCreateCronJob_Success() {
        when(cronTaskService.scheduleJob(eq("job-1"), eq("Check health"), eq("0 */5 * * * *"), isNull()))
                .thenReturn(Map.of("status", "SUCCESS", "jobId", "job-1"));

        ResponseEntity<Map<String, Object>> response = controller.createCronJob(Map.of(
                "jobId", "job-1",
                "intent", "Check health",
                "cronExpression", "0 */5 * * * *"
        ));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals("job-1", response.getBody().get("jobId"));
    }

    @Test
    void testListCronJobs() {
        when(cronTaskService.listJobs()).thenReturn(List.of(Map.of("jobId", "job-1")));

        ResponseEntity<List<Map<String, Object>>> response = controller.listCronJobs();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("job-1", response.getBody().get(0).get("jobId"));
    }

    @Test
    void testCancelCronJob() {
        when(cronTaskService.cancelJob("job-1")).thenReturn(Map.of("status", "SUCCESS", "jobId", "job-1"));

        ResponseEntity<Map<String, Object>> response = controller.cancelCronJob("job-1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
    }
}
