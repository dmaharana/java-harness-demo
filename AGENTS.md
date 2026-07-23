# AGENTS.md - Harness Memory & Project Rules

This file is maintained by the Spring Boot Agentic Harness to persist learned rules, tool execution patterns, and error recovery strategies across sessions.

## 📋 Core Behavioral Guidelines
1. **Verification First**: Always verify tool inputs before execution and validate outputs after execution.
2. **Context Precision**: Do not repeat failed tool invocations with identical parameters without fixing the root cause.
3. **Structured Responses**: Ensure final answers summarize actions taken and findings clearly.

## 🧠 Learned Memory & Lessons
- [Initial System Rule] Verify HTTP MCP server availability before attempting tool calls.

- [2026-07-23 06:59:59] The function call to `select_users` failed because the required parameters were not provided. To proceed, the user should specify the employee ID, first name, or address they want to use.
- [2026-07-23 07:03:13] The JSON array in the execution trace is used to store and retrieve user data, such as their name, address, and other relevant information, to provide the user with the specific details they need to find in Miami.