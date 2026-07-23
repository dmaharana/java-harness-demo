package com.example.demo.controller;

import com.example.demo.config.HarnessProperties;
import com.example.demo.cron.CronTaskService;
import com.example.demo.harness.HarnessEngine;
import com.example.demo.mcp.McpHttpClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final HarnessEngine harnessEngine;
    private final HarnessProperties harnessProperties;
    private final McpHttpClientService mcpHttpClientService;
    private final CronTaskService cronTaskService;

    public HarnessController(HarnessEngine harnessEngine,
                             HarnessProperties harnessProperties,
                             McpHttpClientService mcpHttpClientService,
                             CronTaskService cronTaskService) {
        this.harnessEngine = harnessEngine;
        this.harnessProperties = harnessProperties;
        this.mcpHttpClientService = mcpHttpClientService;
        this.cronTaskService = cronTaskService;
    }

    @PostMapping("/intent")
    public ResponseEntity<Map<String, Object>> triggerFlow(@RequestBody Map<String, String> payload) {
        String intent = payload.get("intent");
        if (intent == null || intent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Property 'intent' is required in request body"));
        }

        Map<String, Object> result = harnessEngine.executeIntent(intent);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/intent/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter triggerFlowStream(@RequestBody Map<String, String> payload) {
        String intent = payload.get("intent");
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);

        if (intent == null || intent.isBlank()) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data(Map.of("error", "Property 'intent' is required in request body")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            try {
                harnessEngine.executeIntentStream(intent, event -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("trace").data(event));
                    } catch (Exception e) {
                        // ignore broken pipe if client disconnects
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/cron")
    public ResponseEntity<Map<String, Object>> createCronJob(@RequestBody Map<String, Object> payload) {
        String jobId = payload.get("jobId") != null ? payload.get("jobId").toString() : null;
        String intent = payload.get("intent") != null ? payload.get("intent").toString() : null;
        String cronExpression = payload.get("cronExpression") != null ? payload.get("cronExpression").toString() : null;
        Long intervalSeconds = payload.get("intervalSeconds") != null
                ? ((Number) payload.get("intervalSeconds")).longValue()
                : null;

        Map<String, Object> result = cronTaskService.scheduleJob(jobId, intent, cronExpression, intervalSeconds);
        if ("ERROR".equals(result.get("status"))) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/cron")
    public ResponseEntity<List<Map<String, Object>>> listCronJobs() {
        return ResponseEntity.ok(cronTaskService.listJobs());
    }

    @GetMapping("/cron/{jobId}")
    public ResponseEntity<Map<String, Object>> getCronJob(@PathVariable String jobId) {
        Map<String, Object> result = cronTaskService.getJob(jobId);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/cron/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelCronJob(@PathVariable String jobId) {
        Map<String, Object> result = cronTaskService.cancelJob(jobId);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.ofEntries(
                Map.entry("llmBaseUrl", harnessProperties.getLlm().getBaseUrl()),
                Map.entry("llmModel", harnessProperties.getLlm().getModel()),
                Map.entry("mcpServerUrl", harnessProperties.getMcp().getServerUrl()),
                Map.entry("mcpToolCount", mcpHttpClientService.getAvailableTools().size()),
                Map.entry("configuredHeaders", harnessProperties.getMcp().getHeaders().keySet()),
                Map.entry("maxIterations", harnessProperties.getEngine().getMaxIterations()),
                Map.entry("memoryEnabled", harnessProperties.getMemory().isEnabled()),
                Map.entry("memoryFilePath", harnessProperties.getMemory().getFilePath()),
                Map.entry("reflectionEnabled", harnessProperties.getMemory().isReflectionEnabled()),
                Map.entry("cronEnabled", harnessProperties.getCron().isEnabled()),
                Map.entry("cronPoolSize", harnessProperties.getCron().getPoolSize())
        ));
    }
}

