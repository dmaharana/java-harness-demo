package com.example.demo.controller;

import com.example.demo.config.HarnessProperties;
import com.example.demo.harness.HarnessEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final HarnessEngine harnessEngine;
    private final HarnessProperties harnessProperties;

    public HarnessController(HarnessEngine harnessEngine, HarnessProperties harnessProperties) {
        this.harnessEngine = harnessEngine;
        this.harnessProperties = harnessProperties;
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
                "configuredHeaders", harnessProperties.getMcp().getHeaders().keySet(),
                "maxIterations", harnessProperties.getEngine().getMaxIterations()
        ));
    }
}
