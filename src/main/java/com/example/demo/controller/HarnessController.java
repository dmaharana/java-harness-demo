package com.example.demo.controller;

import com.example.demo.config.HarnessProperties;
import com.example.demo.harness.HarnessEngine;
import com.example.demo.mcp.McpHttpClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final HarnessEngine harnessEngine;
    private final HarnessProperties harnessProperties;
    private final McpHttpClientService mcpHttpClientService;

    public HarnessController(HarnessEngine harnessEngine,
                             HarnessProperties harnessProperties,
                             McpHttpClientService mcpHttpClientService) {
        this.harnessEngine = harnessEngine;
        this.harnessProperties = harnessProperties;
        this.mcpHttpClientService = mcpHttpClientService;
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

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "llmBaseUrl", harnessProperties.getLlm().getBaseUrl(),
                "llmModel", harnessProperties.getLlm().getModel(),
                "mcpServerUrl", harnessProperties.getMcp().getServerUrl(),
                "mcpToolCount", mcpHttpClientService.getAvailableTools().size(),
                "configuredHeaders", harnessProperties.getMcp().getHeaders().keySet(),
                "maxIterations", harnessProperties.getEngine().getMaxIterations(),
                "memoryEnabled", harnessProperties.getMemory().isEnabled(),
                "memoryFilePath", harnessProperties.getMemory().getFilePath(),
                "reflectionEnabled", harnessProperties.getMemory().isReflectionEnabled()
        ));
    }
}

