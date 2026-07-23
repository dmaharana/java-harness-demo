# Findings & Discoveries

## Codebase Overview
*Workspace*: `/home/titu/sources/java-workspace/java-harness-demo`
*Framework*: Spring Boot 3/4 (Java 17), Spring Web, OpenTelemetry, Jackson, Spring AI MCP Client dependency.

### Existing Harness Architecture
1. **`HarnessEngine.java`**: Main execution engine.
   - Takes `userIntent` via `executeIntent(String userIntent)`.
   - Fetches available MCP tools via `McpHttpClientService`.
   - Loads memory from `AGENTS.md` via `MemoryService`.
   - Calls OpenAI-compatible API (`OpenAiLlmClientService`) in an iterative loop (up to `maxIterations`).
   - Executes tool calls via HTTP MCP bridge or returns `finalAnswer`.
   - Runs post-execution self-reflection step.
2. **`HarnessController.java`**: REST API.
   - `POST /api/harness/intent` - triggers single intent execution.
   - `GET /api/harness/config` - gets harness configuration.
3. **`HarnessProperties.java`**: Spring `@ConfigurationProperties(prefix = "harness")`.

## Options for Cron Process / Intent Scheduling

### Option A: Internal MCP/Harness Tool + REST API (Recommended Hybrid)
- **Internal Tool (`schedule_intent_cron`)**: Registers dynamic cron jobs in the harness engine using Spring's `ThreadPoolTaskScheduler`. Allows the LLM itself or harness callers to schedule cron jobs using natural language or tool calls.
- **REST Endpoints**:
  - `POST /api/harness/cron` - Schedule an intent.
  - `GET /api/harness/cron` - List active cron jobs with execution statistics (last run, run count, next run).
  - `DELETE /api/harness/cron/{jobId}` - Cancel a cron job.

### Option B: Spring Boot `@Scheduled` + Application Config (`application.yml`)
- Define static scheduled intents in `application.yml` under `harness.scheduled-intents`.
- Useful for fixed system background tasks configured at startup.

### Option C: Standalone Internal Service with Dynamic TaskScheduler
- Core service managing `CronTrigger` / `PeriodicTrigger` in memory with thread-safe management.
