package com.example.demo.harness;

import com.example.demo.config.HarnessProperties;
import com.example.demo.llm.OpenAiLlmClientService;
import com.example.demo.mcp.McpHttpClientService;
import com.example.demo.memory.MemoryService;
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
    private final MemoryService memoryService;
    private final HarnessProperties properties;
    private final Tracer tracer;

    public HarnessEngine(OpenAiLlmClientService llmClient,
                         McpHttpClientService mcpClient,
                         MemoryService memoryService,
                         HarnessProperties properties,
                         Tracer tracer) {
        this.llmClient = llmClient;
        this.mcpClient = mcpClient;
        this.memoryService = memoryService;
        this.properties = properties;
        this.tracer = tracer;
    }

    public Map<String, Object> executeIntent(String userIntent) {
        Span rootSpan = tracer.spanBuilder("harness.intent_execution")
                .setAttribute("user.intent", userIntent)
                .startSpan();

        List<Map<String, Object>> executionHistory = new ArrayList<>();
        List<String> newLessonsLearned = new ArrayList<>();

        try (Scope scope = rootSpan.makeCurrent()) {
            log.info("Starting Harness execution for intent: {}", userIntent);

            // Step 1: Discover Tools from MCP Server
            Map<String, Object> availableToolsResponse = mcpClient.listTools();
            List<Map<String, Object>> toolsList = mcpClient.extractTools(availableToolsResponse);
            rootSpan.setAttribute("harness.mcp.tools_available", !toolsList.isEmpty());

            // Step 2: Load AGENTS.md / MEMORY.md persistent memory
            String agentMemory = memoryService.loadMemory();
            rootSpan.setAttribute("harness.memory_active", !agentMemory.isBlank());

            // Step 3: Assemble System & User Context with Injected Memory
            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("You are an autonomous agent operating within a Harness Engineering system. ")
                    .append("Your goal is to solve the user's intent reliably using available MCP tools. ")
                    .append("Follow verification loops, check outputs, and avoid repeating failing tool calls.\n\n");

            if (!agentMemory.isBlank()) {
                systemPrompt.append("=== PERSISTENT MEMORY & GUIDELINES (from AGENTS.md) ===\n")
                        .append(agentMemory)
                        .append("\n=======================================================\n");
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt.toString()));
            messages.add(Map.of("role", "user", "content", userIntent));

            int maxIterations = properties.getEngine().getMaxIterations();
            int currentIteration = 0;
            String finalAnswer = null;
            boolean encounteredError = false;

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

                            if (toolResult.startsWith("Error")) {
                                encounteredError = true;
                            }

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
                    encounteredError = true;
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

            // Step 4: Post-Run Self-Reflection Step for Self-Improvement
            if (properties.getMemory().isReflectionEnabled() && (encounteredError || currentIteration > 1)) {
                performSelfReflection(userIntent, executionHistory, newLessonsLearned);
            }

            return Map.of(
                    "intent", userIntent,
                    "finalAnswer", finalAnswer,
                    "iterationsCount", currentIteration,
                    "maxIterations", maxIterations,
                    "memoryUsed", !agentMemory.isBlank(),
                    "newLessonsLearned", newLessonsLearned,
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

    private void performSelfReflection(String intent, List<Map<String, Object>> trace, List<String> lessonsOutput) {
        Span span = tracer.spanBuilder("harness.self_reflection").startSpan();
        try (Scope scope = span.makeCurrent()) {
            log.info("Running Harness Self-Reflection step to extract insights for AGENTS.md");

            List<Map<String, Object>> reflectionMessages = List.of(
                    Map.of("role", "system", "content", "You are an AI Harness Evaluator. Analyze execution logs to find actionable rules for AGENTS.md."),
                    Map.of("role", "user", "content", "Review execution trace for intent: '" + intent + "'. Trace: " + trace +
                            "\nDid any tool call fail or require correction? If yes, summarize the lesson in 1 sentence starting with 'LESSON:'. If none, reply 'NONE'.")
            );

            Map<String, Object> response = llmClient.chatCompletion(reflectionMessages, null);
            Map<String, Object> msg = extractChoiceMessage(response);
            if (msg != null) {
                String reflectionText = (String) msg.get("content");
                if (reflectionText != null && reflectionText.contains("LESSON:")) {
                    String lesson = reflectionText.substring(reflectionText.indexOf("LESSON:") + 7).trim();
                    memoryService.appendLesson(lesson);
                    lessonsOutput.add(lesson);
                    span.setAttribute("harness.lesson_added", lesson);
                }
            }
        } catch (Exception e) {
            span.recordException(e);
            log.warn("Self-reflection step skipped/failed: {}", e.getMessage());
        } finally {
            span.end();
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
