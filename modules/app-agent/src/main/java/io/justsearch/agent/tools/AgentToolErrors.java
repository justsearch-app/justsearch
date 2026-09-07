/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * Tempdoc 877 §2.6 — the one classifier turning a caught exception into an agent-tool
 * {@link OperationResult}.
 *
 * <p>It replaces seven copies of {@code catch (Exception e) { LOG.error(...); return
 * OperationResult.failure(prefix + e.getMessage()); }}. Two facts were split across those copies.
 *
 * <p><b>Fact one: severity.</b> A model that emits malformed JSON is making a MODEL mistake, and
 * every one of them wrote a stack trace into the Head log — which is bundled into the diagnostics
 * export, where it reads as a system fault. Input-shaped failures log at WARN with no trace here;
 * only genuinely unexpected ones keep ERROR-with-trace.
 *
 * <p><b>Fact two: the typed fields.</b> {@code OperationResult} has carried
 * {@code errorCode}/{@code errorDetails}/{@code retryable} since slice 3a-2-c and no agent tool
 * used any of them, so every tool failure reached consumers as an untyped string. The mapping is
 * deliberately narrow — three causes, three existing {@link ApiErrorCode} values, no new enum
 * constants (a new code would also require an {@code errorMessages.ts} entry per that enum's
 * contract test, and this change has nothing new to say to a user).
 *
 * <p>{@code retryable} is not decided here: it is read off {@link ApiErrorCode#isRetryable()}, so
 * the retry answer comes from the same classification the REST surface uses rather than from a
 * second opinion held in this file.
 */
public final class AgentToolErrors {

  private AgentToolErrors() {}

  private static final Logger LOG = LoggerFactory.getLogger(AgentToolErrors.class);

  /**
   * Classify a failure from an agent tool.
   *
   * @param tool the tool name for the log line (e.g. {@code "core_search_index"})
   * @param userMessagePrefix the agent-facing prefix the tool already used (e.g. {@code "Search
   *     error"}), preserved so the model-visible text does not change shape
   */
  public static OperationResult classify(String tool, String userMessagePrefix, Throwable error) {
    Throwable cause = unwrap(error);
    ApiErrorCode code = codeFor(cause);
    String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();

    if (code == ApiErrorCode.INTERNAL_ERROR) {
      LOG.error("{} failed unexpectedly", tool, cause);
    } else {
      // Input-shaped and worker-shaped failures are expected operating conditions, not defects:
      // one line, no trace. The class name is kept because "which exception" is the only part a
      // reader loses by dropping the trace.
      LOG.warn("{} failed: {}: {}", tool, cause.getClass().getSimpleName(), detail);
    }

    // The MODEL gets the actionable sentence for a Worker outage; the raw transport detail stays in
    // the WARN line above, where a reader (not a language model) can use it. Live during a
    // `/api/worker/restart` chaos run the model was handed "Browse error: No valid port in signal
    // bus" — an internal invariant it cannot act on, and which does not say the one thing that
    // matters: waiting fixes this (877 open items).
    String modelFacing =
        code == ApiErrorCode.SERVICE_UNAVAILABLE ? WORKER_UNAVAILABLE_GUIDANCE : detail;

    return OperationResult.failure(
        userMessagePrefix + ": " + modelFacing,
        code.name(),
        Map.of("tool", tool),
        code.isRetryable());
  }

  /**
   * The Worker-outage sentence the model sees, in the shape {@code
   * OperationExecutorImpl.capabilityUnavailableMessage} already uses for {@code worker-online}: name
   * the condition, say it self-heals, name the retry. One wording for one condition, whether the
   * dispatcher refused the call up front or the tool got as far as the transport.
   */
  static final String WORKER_UNAVAILABLE_GUIDANCE =
      "the knowledge worker is not reachable; the index is restarting — retry shortly";

  /**
   * A malformed-argument failure the tool detected itself (a missing required field, an unusable
   * value) — {@code BAD_REQUEST}, not retryable, and no log line at all: the message goes back to
   * the model, which is the party that can fix it.
   */
  public static OperationResult badRequest(String tool, String message) {
    return OperationResult.failure(
        message, ApiErrorCode.BAD_REQUEST.name(), Map.of("tool", tool), false);
  }

  /**
   * The three causes, in priority order. Anything not recognised is {@code INTERNAL_ERROR} — the
   * conservative end, because an unrecognised failure is exactly the one worth a stack trace.
   */
  private static ApiErrorCode codeFor(Throwable cause) {
    if (cause instanceof JacksonException || cause instanceof ToolArgs.BadArgument) {
      return ApiErrorCode.BAD_REQUEST;
    }
    if (cause instanceof TimeoutException) {
      return ApiErrorCode.TIMEOUT;
    }
    if (isWorkerUnreachable(cause) || isWorkerRestarting(cause)) {
      return ApiErrorCode.SERVICE_UNAVAILABLE;
    }
    return ApiErrorCode.INTERNAL_ERROR;
  }

  /**
   * The Worker outage that never reaches the transport. When the Worker process was being replaced,
   * the Head's client re-discovered its port through the shared signal bus, and
   * {@code RemoteKnowledgeClient.reconnect} throws a plain {@link IllegalStateException} before any
   * gRPC call exists to fail — so {@link #isWorkerUnreachable} (which looks for transport types)
   * cannot see it and the failure landed in {@code INTERNAL_ERROR}, with its internal invariant
   * text copied to the model.
   *
   * <p>Matched on the message for the same reason the transport types are matched by name: the
   * thrower lives in {@code app-services}, which {@code app-agent} does not depend on. The two
   * literals are the complete set {@code reconnect} can throw
   * ({@code RemoteKnowledgeClient.java:404} and its PID-validation sibling below it), both meaning
   * "the Worker is mid-restart".
   *
   * <p><b>Lane F stage A:</b> item A6 made {@code EngineKnowledgeClient} the live client and item
   * A11 deleted the worker process, so nothing reconnects and this branch has no producer on the
   * Engine path. It is kept until item A10 removes {@code RemoteKnowledgeClient}, which is still the
   * declared thrower.
   */
  private static boolean isWorkerRestarting(Throwable cause) {
    if (!(cause instanceof IllegalStateException)) {
      return false;
    }
    String message = cause.getMessage();
    if (message == null) {
      return false;
    }
    return message.contains("No valid port in signal bus")
        || message.contains("PID mismatch after reconnect");
  }

  /**
   * gRPC's {@code StatusRuntimeException} is matched by NAME rather than by type: {@code app-agent}
   * does not depend on the gRPC runtime (the Head's Worker client lives in {@code app-services}), so
   * an {@code instanceof} here would not compile. Matching the class name keeps the classification
   * in one place without dragging a transport dependency into the tool module.
   */
  private static boolean isWorkerUnreachable(Throwable cause) {
    for (Throwable t = cause; t != null; t = t.getCause()) {
      String name = t.getClass().getName();
      if (name.endsWith("StatusRuntimeException")
          || name.endsWith("UnavailableException")
          || name.endsWith("ConnectException")) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  /** Strip the future/completion wrappers so classification sees the real cause. */
  private static Throwable unwrap(Throwable error) {
    Throwable t = error;
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null
        && t.getCause() != t) {
      t = t.getCause();
    }
    return t == null ? error : t;
  }
}
