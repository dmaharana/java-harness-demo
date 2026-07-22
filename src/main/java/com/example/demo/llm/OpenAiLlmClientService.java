package com.example.demo.llm;

import com.example.demo.config.HarnessProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiLlmClientService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClientService.class);

    private final HarnessProperties properties;
    private final RestTemplate restTemplate;
    private final Tracer tracer;

    public OpenAiLlmClientService(HarnessProperties properties, Tracer tracer) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        this.tracer = tracer;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Span span = tracer.spanBuilder("llm.chat_completion")
                .setAttribute("llm.model", properties.getLlm().getModel())
                .setAttribute("llm.base_url", properties.getLlm().getBaseUrl())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (properties.getLlm().getApiKey() != null && !properties.getLlm().getApiKey().isBlank()) {
                headers.add("Authorization", "Bearer " + properties.getLlm().getApiKey());
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", properties.getLlm().getModel());
            requestBody.put("messages", messages);
            requestBody.put("temperature", properties.getLlm().getTemperature());
            requestBody.put("max_tokens", properties.getLlm().getMaxTokens());

            if (tools != null && !tools.isEmpty()) {
                requestBody.put("tools", tools);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            String endpoint = properties.getLlm().getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

            log.info("Sending request to OpenAI compatible endpoint: {} with model: {}", endpoint, properties.getLlm().getModel());

            ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, entity, Map.class);
            span.setAttribute("llm.response.status", response.getStatusCode().value());

            return response.getBody();
        } catch (Exception e) {
            span.recordException(e);
            log.error("Error invoking OpenAI LLM API: {}", e.getMessage(), e);
            throw e;
        } finally {
            span.end();
        }
    }
}
