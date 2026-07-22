package com.example.demo.harness;

import com.example.demo.config.HarnessProperties;
import com.example.demo.llm.OpenAiLlmClientService;
import com.example.demo.mcp.McpHttpClientService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HarnessEngine {

    private static final Logger log = LoggerFactory.getLogger(HarnessEngine.class);

    private final OpenAiLlmClientService llmClient;
    private final McpHttpClientService mcpClient;
    private final HarnessProperties properties;
    private final Tracer tracer;

    public HarnessEngine(OpenAiLlmClientService llmClient,
                         McpHttpClientService mcpClient,
                         HarnessProperties properties,
                         Tracer tracer) {
        this.llmClient = llmClient;
        this.mcpClient = mcpClient;
        this.properties = properties;
        this.tracer = tracer;
    }

    public Map<String, Object> executeIntent(String userIntent) {
        Span rootSpan = tracer.spanBuilder("harness.intent_execution")
                .setAttribute("user.intent", userIntent)
                .startSpan();

        List<Map<String, Object>> executionHistory = new ArrayList<>();

        try (Scope scope = rootSpan.makeCurrent()) {
            log.info("Starting Harness execution for intent: {}", userIntent);

            // Step 1: Discover Tools from MCP Server
            Map<String, Object> availableTools = mcpClient.listTools();
            rootSpan.setAttribute("harness.mcp.tools_available", availableTools.containsKey("tools"));

            // Step 2: Assemble System & User Context
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of(
                    "role", "system",
                    "content", "You are an autonomous agent operating within a Harness Engineering system. " +
                               "Your goal is to solve the user's intent reliably using available MCP tools. " +
                               "Follow verification loops, check outputs, and avoid repeating failing tool calls."
            ));
            messages.add(Map.of(
                    "role", "user",
                    "content", userIntent
            ));

            int maxIterations = properties.getEngine().getMaxIterations();
            int currentIteration = 0;
            String finalAnswer = null;

            while (currentIteration < maxIterations) {
                currentIteration++;
                Span loopSpan = tracer.spanBuilder("harness.loop_step")
                        .setAttribute("harness.step_number", currentIteration)
                        .startSpan();

                try (Scope loopScope = loopSpan.makeCurrent()) {
                    log.info("Harness iteration {}/{}", currentIteration, maxIterations);

                    // Call LLM
                    Map<String, Object> response = llmClient.chatCompletion(messages, null);
                    Map<String, Object> message = extractChoiceMessage(response);

                    if (message == null) {
                        loopSpan.setAttribute("harness.step_status", "EMPTY_LLM_RESPONSE");
                        break;
                    }

                    String content = (String) message.get("content");
                    List<Map<String, Object>> toolCalls = extractToolCalls(message);

                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        // Execute requested tools via HTTP MCP Client
                        for (Map<String, Object> toolCall : toolCalls) {
                            String functionName = extractFunctionName(toolCall);
                            Map<String, Object> arguments = extractFunctionArgs(toolCall);

                            log.info("Harness step {}: LLM requested MCP Tool call [{}]", currentIteration, functionName);
                            String toolResult = mcpClient.executeTool(functionName, arguments);

                            messages.add(Map.of(
                                    "role", "assistant",
                                    "content", content != null ? content : "",
                                    "tool_calls", List.of(toolCall)
                            ));
                            messages.add(Map.of(
                                    "role", "tool",
                                    "tool_call_id", toolCall.getOrDefault("id", "call_" + System.currentTimeMillis()),
                                    "content", toolResult
                            ));

                            executionHistory.add(Map.of(
                                    "iteration", currentIteration,
                                    "action", "TOOL_CALL",
                                    "tool", functionName,
                                    "args", arguments,
                                    "result", toolResult
                            ));
                        }
                    } else {
                        // Final Answer from LLM
                        finalAnswer = content != null ? content : "Completed intent processing.";
                        log.info("Harness completed with final answer at iteration {}: {}", currentIteration, finalAnswer);

                        executionHistory.add(Map.of(
                                "iteration", currentIteration,
                                "action", "FINAL_ANSWER",
                                "content", finalAnswer
                        ));

                        loopSpan.setAttribute("harness.step_status", "FINAL_ANSWER");
                        rootSpan.setAttribute("harness.status", "SUCCESS");
                        break;
                    }
                } catch (Exception e) {
                    loopSpan.recordException(e);
                    log.error("Error during harness loop step {}: {}", currentIteration, e.getMessage(), e);
                    executionHistory.add(Map.of(
                            "iteration", currentIteration,
                            "action", "ERROR",
                            "error", e.getMessage()
                    ));
                    break;
                } finally {
                    loopSpan.end();
                }
            }

            if (finalAnswer == null) {
                finalAnswer = "Harness reached maximum iteration limit (" + maxIterations + ") or encountered error without final answer.";
                rootSpan.setAttribute("harness.status", "MAX_ITERATIONS_REACHED");
            }

            return Map.of(
                    "intent", userIntent,
                    "finalAnswer", finalAnswer,
                    "iterationsCount", currentIteration,
                    "maxIterations", maxIterations,
                    "executionTrace", executionHistory
            );

        } catch (Exception e) {
            rootSpan.recordException(e);
            log.error("Harness execution failed for intent [{}]: {}", userIntent, e.getMessage(), e);
            return Map.of(
                    "intent", userIntent,
                    "error", e.getMessage(),
                    "status", "FAILED"
            );
        } finally {
            rootSpan.end();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractChoiceMessage(Map<String, Object> response) {
        if (response == null || !response.containsKey("choices")) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        return (Map<String, Object>) choices.get(0).get("message");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> message) {
        if (message == null || !message.containsKey("tool_calls")) return null;
        return (List<Map<String, Object>>) message.get("tool_calls");
    }

    @SuppressWarnings("unchecked")
    private String extractFunctionName(Map<String, Object> toolCall) {
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        return function != null ? (String) function.get("name") : "unknown_tool";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFunctionArgs(Map<String, Object> toolCall) {
        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
        if (function == null || !function.containsKey("arguments")) return Map.of();
        Object args = function.get("arguments");
        if (args instanceof Map) return (Map<String, Object>) args;
        return Map.of("raw", args.toString());
    }
}
