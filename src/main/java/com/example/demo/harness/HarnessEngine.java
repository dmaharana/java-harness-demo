package com.example.demo.harness;

import com.example.demo.config.HarnessProperties;
import com.example.demo.cron.CronTaskService;
import com.example.demo.llm.OpenAiLlmClientService;
import com.example.demo.mcp.McpHttpClientService;
import com.example.demo.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HarnessEngine {

    private static final Logger log = LoggerFactory.getLogger(HarnessEngine.class);

    public static final String TOOL_SCHEDULE_CRON_INTENT = "schedule_cron_intent";
    public static final String TOOL_LIST_CRON_JOBS = "list_cron_jobs";
    public static final String TOOL_CANCEL_CRON_JOB = "cancel_cron_job";

    private final OpenAiLlmClientService llmClient;
    private final McpHttpClientService mcpClient;
    private final MemoryService memoryService;
    private final HarnessProperties properties;
    private final Tracer tracer;
    private final CronTaskService cronTaskService;
    private final ObjectMapper objectMapper;

    public HarnessEngine(OpenAiLlmClientService llmClient,
                         McpHttpClientService mcpClient,
                         MemoryService memoryService,
                         HarnessProperties properties,
                         Tracer tracer,
                         @Lazy CronTaskService cronTaskService) {
        this.llmClient = llmClient;
        this.mcpClient = mcpClient;
        this.memoryService = memoryService;
        this.properties = properties;
        this.tracer = tracer;
        this.cronTaskService = cronTaskService;
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> executeIntent(String userIntent) {
        return executeIntentStream(userIntent, null);
    }

    public Map<String, Object> executeIntentStream(String userIntent, java.util.function.Consumer<Map<String, Object>> listener) {
        Span rootSpan = tracer.spanBuilder("harness.intent_execution")
                .setAttribute("user.intent", userIntent)
                .startSpan();

        List<Map<String, Object>> executionHistory = new ArrayList<>();
        List<String> newLessonsLearned = new ArrayList<>();

        try (Scope scope = rootSpan.makeCurrent()) {
            log.info("Starting Harness execution for intent: {}", userIntent);

            // Step 1: Discover Tools from MCP Server & Combine with Internal Tools
            Map<String, Object> availableToolsResponse = mcpClient.listTools();
            List<Map<String, Object>> toolsList = mcpClient.extractTools(availableToolsResponse);
            if (toolsList.isEmpty()) {
                toolsList = mcpClient.getAvailableTools();
            }
            rootSpan.setAttribute("harness.mcp.tools_available", !toolsList.isEmpty());

            List<Map<String, Object>> openAiTools = new ArrayList<>(formatToolsForOpenAi(toolsList));
            openAiTools.addAll(getInternalTools());

            // Step 2: Load AGENTS.md / MEMORY.md persistent memory
            String agentMemory = memoryService.loadMemory();
            rootSpan.setAttribute("harness.memory_active", !agentMemory.isBlank());

            // Step 3: Assemble System & User Context with Injected Memory
            StringBuilder systemPrompt = new StringBuilder();
            systemPrompt.append("You are an autonomous agent operating within a Harness Engineering system. ")
                    .append("Your goal is to solve the user's intent reliably using available MCP tools and internal tools. ")
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

                    // Call LLM with formatted tools (MCP + internal)
                    Map<String, Object> response = llmClient.chatCompletion(messages, openAiTools.isEmpty() ? null : openAiTools);
                    Map<String, Object> message = extractChoiceMessage(response);

                    if (message == null) {
                        loopSpan.setAttribute("harness.step_status", "EMPTY_LLM_RESPONSE");
                        break;
                    }

                    String content = (String) message.get("content");
                    List<Map<String, Object>> toolCalls = extractToolCalls(message);

                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        // Execute requested tools (Internal or MCP)
                        for (Map<String, Object> toolCall : toolCalls) {
                            String functionName = extractFunctionName(toolCall);
                            Map<String, Object> arguments = extractFunctionArgs(toolCall);

                            log.info("Harness step {}: LLM requested Tool call [{}]", currentIteration, functionName);
                            String toolResult;
                            if (isInternalTool(functionName)) {
                                toolResult = executeInternalTool(functionName, arguments);
                            } else {
                                toolResult = mcpClient.executeTool(functionName, arguments);
                            }

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

                            Map<String, Object> stepMap = Map.of(
                                    "iteration", currentIteration,
                                    "action", "TOOL_CALL",
                                    "tool", functionName,
                                    "args", arguments,
                                    "result", toolResult
                            );
                            executionHistory.add(stepMap);

                            if (listener != null) {
                                listener.accept(Map.of("type", "STEP", "step", stepMap));
                            }
                        }
                    } else {
                        // Final Answer from LLM
                        finalAnswer = content != null ? content : "Completed intent processing.";
                        log.info("Harness completed with final answer at iteration {}: {}", currentIteration, finalAnswer);

                        Map<String, Object> stepMap = Map.of(
                                "iteration", currentIteration,
                                "action", "FINAL_ANSWER",
                                "content", finalAnswer
                        );
                        executionHistory.add(stepMap);

                        if (listener != null) {
                            listener.accept(Map.of("type", "STEP", "step", stepMap));
                        }

                        loopSpan.setAttribute("harness.step_status", "FINAL_ANSWER");
                        rootSpan.setAttribute("harness.status", "SUCCESS");
                        break;
                    }
                } catch (Exception e) {
                    loopSpan.recordException(e);
                    encounteredError = true;
                    log.error("Error during harness loop step {}: {}", currentIteration, e.getMessage(), e);

                    Map<String, Object> stepMap = Map.of(
                            "iteration", currentIteration,
                            "action", "ERROR",
                            "error", e.getMessage()
                    );
                    executionHistory.add(stepMap);

                    if (listener != null) {
                        listener.accept(Map.of("type", "STEP", "step", stepMap));
                    }
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
                if (listener != null && !newLessonsLearned.isEmpty()) {
                    for (String lesson : newLessonsLearned) {
                        listener.accept(Map.of("type", "LESSON", "lesson", lesson));
                    }
                }
            }

            Map<String, Object> finalResult = Map.of(
                    "intent", userIntent,
                    "finalAnswer", finalAnswer,
                    "iterationsCount", currentIteration,
                    "maxIterations", maxIterations,
                    "memoryUsed", !agentMemory.isBlank(),
                    "newLessonsLearned", newLessonsLearned,
                    "executionTrace", executionHistory
            );

            if (listener != null) {
                listener.accept(Map.of("type", "COMPLETE", "result", finalResult));
            }

            return finalResult;

        } catch (Exception e) {
            rootSpan.recordException(e);
            log.error("Harness execution failed for intent [{}]: {}", userIntent, e.getMessage(), e);
            Map<String, Object> failResult = Map.of(
                    "intent", userIntent,
                    "error", e.getMessage(),
                    "status", "FAILED"
            );
            if (listener != null) {
                listener.accept(Map.of("type", "ERROR", "error", e.getMessage()));
            }
            return failResult;
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
    public List<Map<String, Object>> formatToolsForOpenAi(List<Map<String, Object>> mcpTools) {
        if (mcpTools == null || mcpTools.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (Map<String, Object> tool : mcpTools) {
            if (tool == null) continue;
            if (tool.containsKey("type") && "function".equals(tool.get("type")) && tool.containsKey("function")) {
                openAiTools.add(tool);
            } else if (tool.containsKey("name")) {
                Map<String, Object> functionMap = new HashMap<>();
                functionMap.put("name", tool.get("name"));
                if (tool.containsKey("description") && tool.get("description") != null) {
                    functionMap.put("description", tool.get("description"));
                }
                Object params = tool.get("inputSchema");
                if (params == null) {
                    params = tool.get("parameters");
                }
                if (params != null) {
                    functionMap.put("parameters", params);
                } else {
                    functionMap.put("parameters", Map.of("type", "object", "properties", Map.of()));
                }
                openAiTools.add(Map.of("type", "function", "function", functionMap));
            }
        }
        return openAiTools;
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
        if (message == null) return null;
        if (message.containsKey("tool_calls") && message.get("tool_calls") instanceof List) {
            List<Map<String, Object>> calls = (List<Map<String, Object>>) message.get("tool_calls");
            if (calls != null && !calls.isEmpty()) {
                return calls;
            }
        }
        if (message.containsKey("function_call") && message.get("function_call") instanceof Map) {
            Map<String, Object> fnCall = (Map<String, Object>) message.get("function_call");
            return List.of(Map.of(
                    "id", "call_" + System.currentTimeMillis(),
                    "type", "function",
                    "function", fnCall
            ));
        }
        return null;
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
        if (args instanceof Map) {
            return (Map<String, Object>) args;
        }
        if (args instanceof String) {
            String strArgs = (String) args;
            if (strArgs.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(strArgs, Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse tool call arguments string: {}", strArgs, e);
                return Map.of("raw", strArgs);
            }
        }
        return Map.of("raw", args.toString());
    }

    public List<Map<String, Object>> getInternalTools() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", TOOL_SCHEDULE_CRON_INTENT,
                                "description", "Schedule a recurring cron job process to run a user intent at a configured interval or cron expression.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "jobId", Map.of("type", "string", "description", "Optional unique job identifier"),
                                                "intent", Map.of("type", "string", "description", "The user intent to execute periodically"),
                                                "cronExpression", Map.of("type", "string", "description", "Standard cron expression (e.g., '0 */5 * * * *')"),
                                                "intervalSeconds", Map.of("type", "integer", "description", "Interval in seconds between execution runs")
                                        ),
                                        "required", List.of("intent")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", TOOL_LIST_CRON_JOBS,
                                "description", "List all currently active scheduled cron jobs and their execution metrics.",
                                "parameters", Map.of("type", "object", "properties", Map.of())
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", TOOL_CANCEL_CRON_JOB,
                                "description", "Cancel a running scheduled cron job by its jobId.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "jobId", Map.of("type", "string", "description", "The unique jobId to cancel")
                                        ),
                                        "required", List.of("jobId")
                                )
                        )
                )
        );
    }

    public boolean isInternalTool(String name) {
        return TOOL_SCHEDULE_CRON_INTENT.equals(name) ||
                TOOL_LIST_CRON_JOBS.equals(name) ||
                TOOL_CANCEL_CRON_JOB.equals(name);
    }

    public String executeInternalTool(String name, Map<String, Object> arguments) {
        try {
            switch (name) {
                case TOOL_SCHEDULE_CRON_INTENT:
                    String jobId = (String) arguments.get("jobId");
                    String intent = (String) arguments.get("intent");
                    String cronExpression = (String) arguments.get("cronExpression");
                    Long intervalSeconds = arguments.get("intervalSeconds") != null
                            ? ((Number) arguments.get("intervalSeconds")).longValue()
                            : null;
                    Map<String, Object> schedResult = cronTaskService.scheduleJob(jobId, intent, cronExpression, intervalSeconds);
                    return objectMapper.writeValueAsString(schedResult);

                case TOOL_LIST_CRON_JOBS:
                    List<Map<String, Object>> jobs = cronTaskService.listJobs();
                    return objectMapper.writeValueAsString(jobs);

                case TOOL_CANCEL_CRON_JOB:
                    String cancelJobId = (String) arguments.get("jobId");
                    Map<String, Object> cancelResult = cronTaskService.cancelJob(cancelJobId);
                    return objectMapper.writeValueAsString(cancelResult);

                default:
                    return "Error: Unknown internal tool " + name;
            }
        } catch (Exception e) {
            log.error("Error executing internal tool {}: {}", name, e.getMessage(), e);
            return "Error executing internal tool " + name + ": " + e.getMessage();
        }
    }
}
