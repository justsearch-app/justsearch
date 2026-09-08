/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ErrorClass;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RestartRequiredException;
import io.justsearch.app.services.worker.WorkerRecoveryAuthority;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code POST /api/worker/restart} in the state it was WRITTEN for and used to fail in (tempdoc 825
 * §D5 decision 4): the worker never started, so there is no bootstrap bound here and no spawner to
 * restart. Pre-825 both arms answered 503 "Knowledge Server not configured" — the operator's escape
 * hatch was dead in exactly the state they would reach for it.
 *
 * <p>These cases pin the ROUTING, not the recovery: the authority is a stub, because the budget and
 * the vetoes are the monitor's law and are pinned by {@code BootRecoveryDecisionTest} /
 * {@code KnowledgeServerBootRecoveryTest}. What must not regress here is that the endpoint consults
 * the one authority instead of growing a second restart path.
 */
@DisplayName("POST /api/worker/restart routes through the boot-recovery authority")
final class InferenceHandlersWorkerRestartTest {

  private static InferenceHandlers handlersWithNoWorker() {
    return new InferenceHandlers(
        mock(io.justsearch.app.api.OnlineAiService.class),
        null, // no KnowledgeServerBootstrap — the 821 §O.4 state
        mock(io.justsearch.gpu.GpuCapabilitiesService.class),
        mock(io.justsearch.app.api.EnterprisePolicyService.class),
        mock(io.justsearch.app.services.settings.UiSettingsStore.class),
        null,
        null,
        null);
  }

  private static Context mockContext() {
    Context ctx = mock(Context.class);
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.endpointHandlerPath()).thenReturn("/api/worker/restart");
    return ctx;
  }

  /** Records what the endpoint asked for, so "it consulted the authority" is itself asserted. */
  private static final class StubAuthority implements WorkerRecoveryAuthority {
    private final Verdict verdict;
    private final AtomicInteger calls = new AtomicInteger();

    StubAuthority(Verdict verdict) {
      this.verdict = verdict;
    }

    @Override
    public Verdict requestRecoveryNow() {
      calls.incrementAndGet();
      return verdict;
    }
  }

  @Test
  @DisplayName("with no worker bound and no authority wired, the legacy 503 stands")
  void withoutAuthorityItStill503s() {
    Context ctx = mockContext();

    handlersWithNoWorker().handleRestartWorker(ctx);

    verify(ctx).status(503);
  }

  @Test
  @DisplayName("an accepted recovery answers 202 — scheduled, not 'restarted'")
  void acceptedRecoveryAnswers202() {
    InferenceHandlers handlers = handlersWithNoWorker();
    StubAuthority authority = new StubAuthority(WorkerRecoveryAuthority.Verdict.ACCEPTED);
    handlers.setWorkerRecovery(authority);
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    assertEquals(1, authority.calls.get(), "the endpoint must consult the one recovery authority");
    // 202, not 200: a worker boot takes tens of seconds, so the honest answer is "scheduled".
    verify(ctx).status(202);
  }

  @Test
  @DisplayName("a vetoed or exhausted recovery keeps 503 — the state will not change by itself")
  void declinedRecoveryKeeps503() {
    for (WorkerRecoveryAuthority.Verdict verdict :
        List.of(
            WorkerRecoveryAuthority.Verdict.VETOED_SUPERVISION,
            WorkerRecoveryAuthority.Verdict.VETOED_RESTART_EXHAUSTED,
            WorkerRecoveryAuthority.Verdict.EXHAUSTED)) {
      InferenceHandlers handlers = handlersWithNoWorker();
      handlers.setWorkerRecovery(new StubAuthority(verdict));
      Context ctx = mockContext();

      handlers.handleRestartWorker(ctx);

      verify(ctx).status(503);
    }
  }

  @Test
  @DisplayName("a TERMINAL decline is not advertised as retryable (live leg, run 2)")
  void terminalDeclineIsNotRetryable() {
    for (WorkerRecoveryAuthority.Verdict verdict :
        List.of(
            WorkerRecoveryAuthority.Verdict.EXHAUSTED,
            WorkerRecoveryAuthority.Verdict.VETOED_RESTART_EXHAUSTED)) {
      Map<String, Object> body = declineBody(verdict);

      // The live run measured errorClass=TRANSIENT / retryable=true on a state where the next
      // request provably returns the same answer until the application is restarted.
      assertEquals(ApiErrorCode.WORKER_RECOVERY_EXHAUSTED.name(), body.get("errorCode"), verdict.name());
      assertEquals(ErrorClass.PERMANENT.name(), body.get("errorClass"), verdict.name());
      assertEquals(false, body.get("retryable"), verdict.name());
      assertEquals("errors." + ApiErrorCode.WORKER_RECOVERY_EXHAUSTED.name(), body.get("i18nKey"));
      assertTrue(
          String.valueOf(body.get("error")).contains("restart the application"),
          "the message must name the one remedy that works: " + body.get("error"));
    }
  }

  @Test
  @DisplayName("a still-supervised decline stays retryable — that one really can change")
  void supervisedDeclineStaysRetryable() {
    Map<String, Object> body = declineBody(WorkerRecoveryAuthority.Verdict.VETOED_SUPERVISION);

    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE.name(), body.get("errorCode"));
    assertEquals(ErrorClass.TRANSIENT.name(), body.get("errorClass"));
    assertEquals(true, body.get("retryable"));
    assertTrue(
        String.valueOf(body.get("error")).contains("retry shortly"),
        "…and says so: " + body.get("error"));
  }

  @Test
  @DisplayName("the pre-bind startup window says so, instead of 'not configured' (live leg, run 3)")
  void preBindWindowIsWordedHonestly() {
    // A POST fired the instant the pin became visible landed here: the bootstrap narrates its
    // failure inside tryStartKnowledgeServer, and connectWorker binds the recovery authority a
    // moment later. The window is inherent to that ordering; what it said was not.
    InferenceHandlers handlers = handlersWithNoWorker(); // no authority bound yet
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    Map<String, Object> body = capturedBody(ctx);
    verify(ctx).status(503);
    assertEquals(ErrorClass.TRANSIENT.name(), body.get("errorClass"), "retrying does resolve this");
    assertEquals(true, body.get("retryable"));
    String message = String.valueOf(body.get("error"));
    assertTrue(message.contains("initializing"), "must name the startup window: " + message);
    assertFalse(
        message.contains("not configured"),
        "the worker IS configured — it is mid-boot: " + message);
  }

  /** Drives one decline verdict through the handler and returns the JSON envelope it wrote. */
  private static Map<String, Object> declineBody(WorkerRecoveryAuthority.Verdict verdict) {
    InferenceHandlers handlers = handlersWithNoWorker();
    handlers.setWorkerRecovery(new StubAuthority(verdict));
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    return capturedBody(ctx);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> capturedBody(Context ctx) {
    ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(json.capture());
    return (Map<String, Object>) json.getValue();
  }

  /**
   * The restart-as-reload answer itself, which nothing asserted until now. Every case above pins
   * the pre-bind states — no bootstrap, no client — and each ends before the handler's last
   * statement, so the ONE arm an operator hits on a healthy Engine ran untested: index half up,
   * client bound, request arrives, answer is 409 + {@code restart_required}.
   *
   * <p>Both halves matter and neither implies the other. The STATUS is what a caller branches on
   * before it parses anything, and 409 (not 503, not 200) says "your request conflicts with the
   * state, and no retry changes that". The CODE is what a caller keys off once it has the body —
   * {@link RestartRequiredException#CODE} exists as a constant precisely so a consumer is not
   * matching on wording, and asserting the constant rather than the literal is what keeps this
   * test from passing while the wire value drifts.
   */
  @Test
  @DisplayName("with the index half bound, restart answers 409 restart_required")
  void boundIndexHalfAnswers409RestartRequired() {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(true);
    InferenceHandlers handlers =
        new InferenceHandlers(
            mock(io.justsearch.app.api.OnlineAiService.class),
            bootstrap,
            mock(io.justsearch.gpu.GpuCapabilitiesService.class),
            mock(io.justsearch.app.api.EnterprisePolicyService.class),
            mock(io.justsearch.app.services.settings.UiSettingsStore.class),
            null,
            null,
            null);
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    verify(ctx).status(409);
    Map<String, Object> body = capturedBody(ctx);
    assertEquals(
        RestartRequiredException.CODE,
        body.get("code"),
        "the machine-readable handle a caller keys off must be on the body, not only in the prose");
    assertEquals(ErrorClass.PERMANENT.name(), body.get("errorClass"));
    assertEquals(false, body.get("retryable"), "no number of retries reloads an in-process index");
    assertTrue(
        String.valueOf(body.get("error")).contains("restart JustSearch"),
        "the message must name the one remedy that works: " + body.get("error"));
  }

  @Test
  @DisplayName("NOT_APPLICABLE falls through to the ordinary path rather than answering for it")
  void notApplicableFallsThrough() {
    InferenceHandlers handlers = handlersWithNoWorker();
    handlers.setWorkerRecovery(new StubAuthority(WorkerRecoveryAuthority.Verdict.NOT_APPLICABLE));
    Context ctx = mockContext();

    handlers.handleRestartWorker(ctx);

    // With no bootstrap there is nothing to fall through TO, so the legacy 503 is correct here —
    // what matters is that the recovery authority did not claim a request it could not serve.
    verify(ctx).status(503);
  }
}
