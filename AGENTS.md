# AGENTS.md - Harness Memory & Project Rules

This file is maintained by the Spring Boot Agentic Harness to persist learned rules, tool execution patterns, and error recovery strategies across sessions.

## 📋 Core Behavioral Guidelines
1. **Verification First**: Always verify tool inputs before execution and validate outputs after execution.
2. **Context Precision**: Do not repeat failed tool invocations with identical parameters without fixing the root cause.
3. **Structured Responses**: Ensure final answers summarize actions taken and findings clearly.

## 🧠 Learned Memory & Lessons
- [Initial System Rule] Verify HTTP MCP server availability before attempting tool calls.
