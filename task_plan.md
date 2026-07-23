# Task Plan: Enable Cron Process in Spring Boot Harness to Run Intent at Configured Interval

## Goal
Design and recommend a user-friendly option for the harness to create a cron process and run an intent at a configured interval. If approved/requested, implement an internal tool/function within the Spring Boot harness application.

## User Request Analysis
- Requirement: Enable harness to create a cron process and run an intent at the configured interval.
- Action: Recommend a user-friendly option, most probably by creating an internal tool/function in the harness codebase.

## Phases
- [x] **Phase 1: Codebase Exploration & Discovery**
  - Goal: Explore existing harness features, tools, intent processing architecture, and scheduling mechanisms in `java-harness-demo`.
  - Verification: Documented findings in `findings.md`.
- [x] **Phase 2: Architectural Design & Recommendation**
  - Goal: Formulate user-friendly options for cron process creation & intent execution; present recommendations and design.
  - Verification: Documented options in `findings.md` and design in `task_plan.md`.
- [x] **Phase 3: Implementation of Cron Process Tool/Function & REST API**
  - Goal: Implement `CronTaskService`, internal tools (`schedule_cron_intent`, `list_cron_jobs`, `cancel_cron_job`), REST endpoints, and unit tests.
  - Verification: Code compiles via Maven, all 22 tests pass.
- [x] **Phase 4: Verification & Testing**
  - Goal: Run tests and verify cron task scheduling end-to-end.
  - Verification: Maven test suite passes cleanly.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| `Map.of()` >10 args compilation error | 1 | Changed `Map.of` to `Map.ofEntries` in `HarnessController.java` |
| Mockito matcher raw null error | 1 | Replaced raw `null` with `isNull()` in `HarnessEngineTest` & `HarnessControllerTest` |
