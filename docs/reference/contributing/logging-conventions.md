---
title: Logging Conventions
type: reference
status: stable
description: "Conventions for log statements, exception handling, MDC usage, and query redaction."
---

# Logging Conventions

This document defines how to write log statements in JustSearch. Following these conventions ensures consistent, privacy-safe, and diagnostically useful logs across all three processes (Head, Worker, Brain).

## Stack

- **API**: SLF4J 2.x — all log statements use `LoggerFactory.getLogger(ClassName.class)`
- **Implementation**: Logback Classic with LogstashEncoder (JSON output)
- **No alternatives**: Do not use `java.util.logging`, Log4j directly, `System.out.println`, or Lombok `@Slf4j`

## Exception Logging

### Default: pass the exception object

```java
} catch (IOException e) {
  log.error("Failed to write index", e);  // SLF4J prints full stack trace
}
```

SLF4J treats the last `Throwable` argument specially — it appends the full stack trace. This is almost always what you want.

### When message-only is appropriate

Use `e.getMessage()` (without passing the exception) only when ALL of these apply:

1. The failure is **expected and well-understood** (validation, timeout, best-effort cleanup)
2. The log message or exception type is **self-explanatory** (the stack trace adds no diagnostic value)
3. The code **handles the failure gracefully** (fallback, retry, skip)

Mark these sites with a comment or use one of these message patterns so future audits can distinguish intentional from accidental:

```java
} catch (IllegalArgumentException e) {
  log.warn("Invalid input: {}", e.getMessage());  // validation — message IS the diagnostic
}

} catch (BackendException e) {
  log.warn("Embedding failed, falling back to keyword search: {}", e.getMessage());
  // "falling back" signals intentional degradation
}
```

**Recognized intentional markers** (in log message or comment):
- "best-effort", "non-fatal", "will fall back", "falling back", "fallback"
- "ignored", "skipped", "optional", "continuing without"

### Hot paths: WARN/DEBUG split

For sites that fire per-request (search, embedding, health checks), avoid log flooding during sustained degradation by splitting the log level:

```java
} catch (RuntimeException e) {
  log.warn("Embedding generation failed: {}", e.getMessage());
  log.debug("Embedding generation failed (stack trace)", e);
  return fallbackResult();
}
```

- **Production (INFO/WARN)**: sees the one-line warning without stack trace spam
- **Debug mode**: sees the full stack trace for root cause analysis

Use this pattern when the failure can repeat at high frequency (per-query, per-health-check, per-batch-item).

## MDC Keys

Structured JSON logs include MDC (Mapped Diagnostic Context) keys for cross-process correlation. The following keys are defined:

| Key | Set by | Where | Purpose |
|-----|--------|-------|---------|
| `trace_id` | `MdcContext.request()` | Index-half services (`WorkerIngestService`, `WorkerSearchService`) | W3C trace correlation across the Head→index-half port calls |
| `request_id` | `MdcContext.request()` | Same | Per-request correlation |
| `span_id` | Not yet populated | — | Reserved for OTel MDC bridge (future) |
| `stage_id` | `MdcContext.stage()` | Not yet adopted | Pipeline stage identification (future) |

### Using MdcContext in index-half services

Wrap the method body in a try-with-resources. The `CallContext` argument carries the trace and
request ids (it replaced the gRPC `Context` at lane F stage A):

```java
public MigrationStartResponse startMigration(MigrationStartRequest request, CallContext ctx) {
  try (var ignored = openRequestMdc(ctx)) {
    return migrationOps.startMigration(request);
  }
}
```

(Verbatim shape: `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java:431`.)

**Important**: catch blocks must be INSIDE the MdcContext scope. In Java try-with-resources, the resource closes BEFORE catch clauses on the same try. Use a nested try/catch to ensure MDC is active during error logging.

## Query Text Redaction

User query text is privacy-sensitive. Do not log raw query strings at WARN or higher.

### The WARN/DEBUG split pattern (preferred)

```java
} catch (ParseException e) {
  log.warn("Failed to parse query", e);           // No query text at WARN
  log.debug("Failed query text: {}", queryText);   // Query only at DEBUG
}
```

### The SensitiveQuery pattern (for INFO-level search logging)

```java
private record SensitiveQuery(String value) {
  @Override public String toString() { return "[REDACTED]"; }
}

log.info("Search request: query={}", new SensitiveQuery(queryText));
// Output: Search request: query=[REDACTED]
```

This pattern is used in `KnowledgeSearchController` and `SearchOrchestrator`.

## File Paths

File paths on Windows contain usernames (`C:\Users\<name>\...`). In production logs this is acceptable (logs are local-only). For diagnostic exports (zip files shared with support), paths should be redacted at export time — see `ApiErrorHandler.sanitizeMessage()`.

## Enforcement

| Rule | Tool | What it catches |
|------|------|-----------------|
| `SystemPrintln` | PMD (`config/pmd/ruleset.xml`) | `System.out.println` / `System.err.println` |
| JUL ban | ArchUnit (`LayeringEnforcementTest` Rule 8) | `java.util.logging.Logger` usage |
| `CompleteExceptionLogging` | CI: `node scripts/ci/check-slf4j-bare-message-logging.mjs`. Local AST-precise: `./gradlew rewriteDryRun --no-configuration-cache "-Drewrite.activeRecipes=org.openrewrite.java.logging.slf4j.CompleteExceptionLogging"` | Simple `log.error(e.getMessage())` patterns |

Intentional `System.out/err` usage (IPC protocol, CLI output, crash reporter) must be annotated with `@SuppressWarnings("PMD.SystemPrintln")`.
