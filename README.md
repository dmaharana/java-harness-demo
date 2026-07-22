# Spring Boot Agentic Harness with MCP & OpenTelemetry

A production-ready AI Agent Harness engineering framework built with **Spring Boot 3.x / 4.x**, **Model Context Protocol (MCP)** over HTTP, **OpenAI-compliant LLM API integration**, and **OpenTelemetry (OTel)** tracing.

---

## 🚀 Features

- **Agentic Harness Engineering**: Implements closed-loop intent execution with verification steps and iteration safeguards.
- **OpenAI-Compliant LLM Client**: Integrates with OpenAI, Ollama, vLLM, or any OpenAI `/v1/chat/completions` API endpoint.
- **HTTP MCP Tool Bridge**: Connects to remote Model Context Protocol (MCP) servers over HTTP with support for custom headers (OAuth/Bearer tokens, multi-tenant headers).
- **OpenTelemetry Instrumentation**: Generates standard distributed trace spans for every harness action:
  - `harness.intent_execution`: Root trace span for user intent.
  - `harness.loop_step`: Span for each iteration loop.
  - `llm.chat_completion`: Span for LLM API invocations.
  - `mcp.tools_list` & `mcp.tool_call`: Spans for MCP tool discovery and execution.

---

## ⚙️ Configuration Properties

The application is configured via [`application.yml`](src/main/resources/application.yml) and supports overrides using environment variables.

### Environment Variables & Settings

| Property | Environment Variable | Default Value | Description |
|---|---|---|---|
| `harness.llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` | Base URL for OpenAI compatible API |
| `harness.llm.model` | `LLM_MODEL` | `gpt-4o` | LLM Model name |
| `harness.llm.api-key` | `LLM_API_KEY` | `sk-proj-placeholder` | API Key for LLM service |
| `harness.llm.temperature` | - | `0.2` | Temperature for LLM completions |
| `harness.llm.max-tokens` | - | `2000` | Max tokens limit per completion |
| `harness.mcp.server-url` | `MCP_SERVER_URL` | `http://localhost:8081/mcp` | HTTP MCP Server endpoint URL |
| `harness.mcp.headers.Authorization` | `MCP_AUTH_HEADER` | `Bearer default-mcp-token` | Custom authorization header for MCP |
| `harness.mcp.headers.X-Tenant-ID` | `MCP_TENANT_ID` | `default-tenant` | Custom multi-tenant header for MCP |
| `harness.engine.max-iterations` | - | `5` | Guardrail limit for harness loops |
| `otel.exporter.otlp.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OpenTelemetry OTLP Exporter Endpoint |
| `otel.service.name` | - | `java-harness-demo` | OpenTelemetry Service Name |

---

## 📦 How to Build & Run

### Prerequisites
- **Java 17** or higher
- **Maven 3.8+** (or use included `./mvnw` wrapper)

### Build the Project
```bash
./mvnw clean compile
```

### Run Tests
```bash
./mvnw clean test
```

### Start the Application
```bash
./mvnw spring-boot:run
```
Alternatively, build a JAR file and execute:
```bash
./mvnw clean package -DskipTests
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

---

## 🎯 How to Trigger the Application

### 1. Trigger Agentic Flow via User Intent API

Send a `POST` request to `/api/harness/intent` with the user intent payload:

```bash
curl -X POST http://localhost:8080/api/harness/intent \
  -H "Content-Type: application/json" \
  -d '{
    "intent": "Analyze recent order metrics and check system health via MCP tools."
  }'
```

#### Example JSON Response
```json
{
  "intent": "Analyze recent order metrics and check system health via MCP tools.",
  "finalAnswer": "Harness successfully executed intent using available tools.",
  "iterationsCount": 1,
  "maxIterations": 5,
  "executionTrace": [
    {
      "iteration": 1,
      "action": "FINAL_ANSWER",
      "content": "Harness successfully executed intent using available tools."
    }
  ]
}
```

---

### 2. Inspect Active Harness Configuration

Send a `GET` request to `/api/harness/config` to view configured endpoints and headers:

```bash
curl -X GET http://localhost:8080/api/harness/config
```

#### Example JSON Response
```json
{
  "llmBaseUrl": "https://api.openai.com/v1",
  "llmModel": "gpt-4o",
  "mcpServerUrl": "http://localhost:8081/mcp",
  "configuredHeaders": ["Authorization", "X-Tenant-ID", "X-Client-Source"],
  "maxIterations": 5
}
```

---

## 🛠️ Customizing MCP Headers & Custom Endpoints

To run the harness pointing to a custom Ollama local instance and custom MCP server:

```bash
export LLM_BASE_URL="http://localhost:11434/v1"
export LLM_MODEL="llama3"
export MCP_SERVER_URL="http://mcp-server.internal.company.com/mcp"
export MCP_AUTH_HEADER="Bearer secret-company-token"

./mvnw spring-boot:run
```
