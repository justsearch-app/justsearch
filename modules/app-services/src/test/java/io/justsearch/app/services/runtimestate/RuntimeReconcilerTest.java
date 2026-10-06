/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase-1 tests for the single-writer {@link RuntimeReconciler} (tempdoc 737 item 7 a–e). */
final class RuntimeReconcilerTest {

  @TempDir Path tmp;
  private final List<RuntimeIntentTestFixture> fixtures = new ArrayList<>();
  private final AtomicInteger fixtureIds = new AtomicInteger();

  private RuntimeSpecStore specStore(boolean chatEnabled) {
    try {
      var fixture = new RuntimeIntentTestFixture(
          tmp.resolve("runtime-intent-" + fixtureIds.incrementAndGet()), chatEnabled);
      fixtures.add(fixture);
      return fixture.spec();
    } catch (Exception failure) {
      throw new AssertionError("Failed to compose runtime intent fixture", failure);
    }
  }

  @AfterEach
  void closeFixtures() {
    fixtures.forEach(RuntimeIntentTestFixture::close);
  }

  private RuntimeReconciler reconciler(
      RecordingLifecycleControl control, RuntimeSpecStore spec, EnterprisePolicyService policy) {
    return new RuntimeReconciler(
        control,
        control::currentModeValue,
        control::externalValue,
        control::detach,
        policy,
        spec,
        new RuntimeGpuLease());
  }

  private static EnterprisePolicyService policyOnlineEnabled(boolean enabled) {
    EffectivePolicy p =
        new EffectivePolicy(
            true, enabled, true, false, List.of(), List.of(), "test", false, null, null, false);
    return () -> p;
  }

  @Test
  void failedActivationLeavesRetriesToComponentOwnerAndNewIntentStillConverges() throws Exception {
    var control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    var pending = new AtomicBoolean();
    var attempts = new AtomicInteger();
    try (var reconciler = new RuntimeReconciler(control, control::currentModeValue,
        control::externalValue, control::detach, null, specStore(true), new RuntimeGpuLease(),
        reason -> {
          attempts.incrementAndGet();
          if (reason == TransitionReason.AUTO_START) {
            pending.set(true);
            control.fireModeChange(Mode.TRANSITIONING, Mode.OFFLINE);
            throw new ModeTransitionException(ModeTransitionException.Reason.ONLINE_START_FAILED,
                "captured initial A failed");
          }
          pending.set(false);
          control.switchToOnlineMode();
        }, reason -> control.switchToIndexingMode(), pending::get)) {
      reconciler.start();
      reconciler.requestBootConvergence();
      assertTrue(reconciler.awaitQuiescent(5_000), "failed UP must not schedule a private retry");
      assertEquals(1, attempts.get());
      reconciler.beginProcedure(RuntimeStatus.ProcedureKind.ACTIVATION, "failed activation");
      reconciler.endProcedure(RuntimeStatus.ProcedureKind.ACTIVATION);
      assertTrue(reconciler.awaitQuiescent(5_000));
      assertEquals(1, attempts.get(), "procedure return must not bypass component recovery");
      reconciler.specChanged();
      assertTrue(reconciler.awaitQuiescent(5_000));
      assertEquals(2, attempts.get(), "explicit new intent remains actionable");
      assertEquals(Mode.ONLINE, control.currentModeValue());
    }
  }

  @Test
  void componentRecoveryDoesNotSuppressRetryOfDisabledIntentShutdown() throws Exception {
    var control = new RecordingLifecycleControl().withMode(Mode.ONLINE);
    var downAttempts = new AtomicInteger();
    var failedAt = new AtomicLong();
    var retriedAt = new AtomicLong();
    try (var reconciler = new RuntimeReconciler(control, control::currentModeValue,
        control::externalValue, control::detach, null, specStore(false), new RuntimeGpuLease(),
        reason -> control.switchToOnlineMode(), reason -> {
          if (downAttempts.incrementAndGet() == 1) {
            control.fireModeChange(Mode.ONLINE, Mode.TRANSITIONING);
            control.fireModeChange(Mode.TRANSITIONING, Mode.ONLINE);
            failedAt.set(System.nanoTime());
            throw new ModeTransitionException(ModeTransitionException.Reason.INDEXING_START_FAILED,
                "prior physical stop is incomplete");
          }
          retriedAt.set(System.nanoTime());
          control.switchToIndexingMode();
        }, () -> true)) {
      reconciler.start();
      reconciler.requestBootConvergence();
      assertTrue(reconciler.awaitQuiescent(5_000));
      assertEquals(2, downAttempts.get());
      assertEquals(Mode.INDEXING, control.currentModeValue());
      assertTrue(retriedAt.get() - failedAt.get() >= TimeUnit.MILLISECONDS.toNanos(900),
          "transition and rollback callbacks must not bypass the one-second failure backoff");
    }
  }

  @Test
  void explicitEnableDuringProcedureSurvivesPendingRecoveryAndDeferredConvergence() throws Exception {
    var control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    var spec = specStore(false);
    var reasonSeen = new AtomicReference<TransitionReason>();
    try (var reconciler = new RuntimeReconciler(control, control::currentModeValue,
        control::externalValue, control::detach, null, spec, new RuntimeGpuLease(), reason -> {
          reasonSeen.set(reason);
          control.switchToOnlineMode();
        }, reason -> control.switchToIndexingMode(), () -> true)) {
      reconciler.start();
      reconciler.beginProcedure(RuntimeStatus.ProcedureKind.ACTIVATION, "install");
      spec.setChatEnabled(true);
      reconciler.specChanged();
      assertTrue(reconciler.awaitQuiescent(5_000), "the explicit request is deferred by the procedure");
      assertEquals(0, control.onlineSwitchCount.get());
      reconciler.endProcedure(RuntimeStatus.ProcedureKind.ACTIVATION);
      assertTrue(reconciler.awaitQuiescent(5_000));
      assertEquals(TransitionReason.USER_SWITCH, reasonSeen.get());
      assertEquals(1, control.onlineSwitchCount.get());
    }
  }

  // (a) spec-on + engine down → exactly one switchToOnlineMode.
  @Test
  void specOnEngineDown_switchesOnlineExactlyOnce() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS), "expected an online switch");
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get(), "exactly one online switch");
    assertEquals(0, control.indexingSwitchCount.get());
    r.close();
  }

  // (b) convergence never runs on a caller/listener thread — it runs on the reconciler thread.
  @Test
  void convergenceRunsOnReconcilerThreadNotCaller() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS));
    assertNotNull(control.lastSwitchThread);
    assertNotEquals(Thread.currentThread(), control.lastSwitchThread, "must not run on the caller thread");
    assertEquals("runtime-reconciler", control.lastSwitchThread.getName());
    r.close();
  }

  // NOTE: Spec A step 2 deleted Phase 2's continuous return-to-spec and its anti-flap hold
  // (reconciliation F6); `foreignUnloadWithChatEnabled_neverReloads` below pins that a foreign flip
  // no longer re-converges.

  // (d) admin policy off is a hard ceiling that wins over spec-on.
  @Test
  void policyOffWinsOverSpecOn() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), policyOnlineEnabled(false));
    r.start();
    r.requestBootConvergence();
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(0, control.onlineSwitchCount.get(), "policy off must block the online switch");
    r.close();
  }

  // (e) spec-write to chatEnabled=false while engine is up → stop primitive (switchToIndexingMode).
  @Test
  void specDisableWhileOnline_callsStopPrimitive() throws Exception {
    RecordingLifecycleControl control =
        new RecordingLifecycleControl().withMode(Mode.ONLINE).withExternal(false);
    RuntimeSpecStore spec = specStore(true);
    RuntimeReconciler r = reconciler(control, spec, null);
    r.start();

    // User turns chat off.
    spec.setChatEnabled(false);
    r.specChanged();

    assertTrue(control.firstIndexingSwitch.await(5, TimeUnit.SECONDS), "expected a stop (indexing) switch");
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.indexingSwitchCount.get());
    assertEquals(0, control.onlineSwitchCount.get());
    r.close();
  }

  // ==================== Phase 2 (tempdoc 737 task 7 a–e) ====================

  // (a) §3d regression — the never-switch-back bug becomes IMPOSSIBLE by construction. Spec A
  // step 6: the chat controller (not the reconciler) brings the engine up for the VDU hold, and the
  // embedding hand-off park is gone (O2). With spec on, the engine the hold started is already the
  // spec, so the end of the hold makes no reconciler switch at all.
  @Test
  void procedureWithSpecOn_returnsEngineOnlineAfterEnd() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start(); // no boot convergence — start the procedure from a down engine

    r.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "test");
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE); // the controller starts it for the hold
    assertTrue(r.awaitQuiescent(5_000));
    r.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);

    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue(), "engine must be at spec (ONLINE) after procedure");
    assertEquals(List.of(), control.switchLog, "no park for embeddings and no reconciler switch");
    r.close();
  }

  // (b) spec-off variant — after endProcedure the engine stays DOWN (soft-off: chat service off).
  @Test
  void procedureWithSpecOff_engineStaysDownAfterEnd() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(false), null);
    r.start();

    r.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "test");
    // The controller starts the engine for the hold even when chat is disabled.
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue());
    r.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);

    assertTrue(r.awaitQuiescent(5_000));
    assertNotEquals(Mode.ONLINE, control.currentModeValue(), "spec-off: engine must NOT stay online");
    assertEquals(0, control.onlineSwitchCount.get(), "no return-to-spec up");
    assertEquals(List.of("indexing"), control.switchLog, "one return-to-spec stop");
    r.close();
  }

  // (c) Spec A step 2 (reconciliation F6): an engine that went down behind the reconciler's back
  // with chatEnabled still true is NOT reloaded — foreign-flip convergence is deleted, so only the
  // chat controller's own decisions start chat. Status still reflects the observed state.
  @Test
  void foreignUnloadWithChatEnabled_neverReloads() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS));
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get());

    // The engine is unloaded by something other than the reconciler, repeatedly (past the old
    // anti-flap cap of 3, so neither convergence nor a flap hold can explain the outcome).
    for (int i = 0; i < 5; i++) {
      control.fireModeChange(Mode.ONLINE, Mode.INDEXING);
      assertTrue(r.awaitQuiescent(5_000));
      assertEquals(Mode.INDEXING, control.currentModeValue(), "a foreign unload stays unloaded");
      assertEquals(
          RuntimeStatus.REASON_GPU_YIELDED_TO_INDEXING,
          r.current().condition(RuntimeStatus.Axis.ENGINE).orElseThrow().reason(),
          "status still republishes the observed state");
      control.fireModeChange(Mode.INDEXING, Mode.ONLINE);
      assertTrue(r.awaitQuiescent(5_000));
    }
    assertEquals(1, control.onlineSwitchCount.get(), "only the boot forward; no reload");
    assertEquals(List.of("online"), control.switchLog);
    r.close();
  }

  // (e) soft-off legibility — a procedure holds the engine up while spec disables chat → the ENGINE
  // condition carries 'engine-up-for-background-processing'.
  @Test
  void procedureHoldsEngineUpSpecOff_engineReasonIsBackground() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(false), null);
    r.start();

    r.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "test");
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE); // the controller starts it for the hold
    assertTrue(r.awaitQuiescent(5_000));

    assertEquals(Mode.ONLINE, control.currentModeValue());
    RuntimeStatus.Condition engine = r.current().condition(RuntimeStatus.Axis.ENGINE).orElseThrow();
    assertEquals(
        RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND,
        engine.reason(),
        "soft-off: engine up for background work while chat is disabled");
    // The procedure overlay axis is present and reflects VDU_BATCH.
    assertEquals(
        RuntimeStatus.ProcedureKind.VDU_BATCH.name(),
        r.current().condition(RuntimeStatus.Axis.PROCEDURE).orElseThrow().status());
    r.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    r.close();
  }

  // (g) a procedure owns the engine — an explicit spec-write mid-procedure is DEFERRED (no second
  // writer fighting the procedure) and honored at endProcedure. Regression for the critical-analysis
  // finding that the loop previously ran explicit convergence even with a procedure active.
  @Test
  void specWriteDuringProcedure_deferredUntilEnd() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeSpecStore spec = specStore(true);
    RuntimeReconciler r = reconciler(control, spec, null);
    r.start();

    r.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "test");
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE); // the controller starts it for the hold
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue());
    int indexingAfterUp = control.indexingSwitchCount.get();

    // User disables chat MID-procedure — the reconciler must NOT park the engine now.
    spec.setChatEnabled(false);
    r.specChanged();
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue(), "procedure owns the engine; no mid-procedure park");
    assertEquals(indexingAfterUp, control.indexingSwitchCount.get(), "no park during the procedure");

    // Procedure ends → converge to the now-current spec (off) → engine parks down.
    r.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    assertTrue(r.awaitQuiescent(5_000));
    assertNotEquals(Mode.ONLINE, control.currentModeValue(), "after the procedure, spec (off) wins → engine down");
    r.close();
  }

  // ==================== Fix pack (tempdoc 737 §12a fix pack — multi-kind procedures) ====================

  // (fix-a) ACTIVATION procedure: the engine comes ONLINE (foreign flip from applyRuntimeOverrides)
  // while spec is still false — the reconciler must NOT drift-park it down. After the intent lands
  // (spec true) and the procedure ends, the engine stays ONLINE with no reconciler switch at all.
  @Test
  void activationProcedure_specOffEngineOnlineDrift_noDownSwitch_thenStaysOnline() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeSpecStore spec = specStore(false);
    RuntimeReconciler r = reconciler(control, spec, null);
    r.start();

    r.beginProcedure(RuntimeStatus.ProcedureKind.ACTIVATION, "activation-test");
    // applyRuntimeOverrides(RESTART_ALWAYS) brings the engine up mid-activation — a foreign-looking
    // flip the ACTIVATION procedure must absorb while spec is still false.
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(0, control.indexingSwitchCount.get(), "procedure active: no drift down-switch while spec=false");
    assertEquals(Mode.ONLINE, control.currentModeValue());

    // recordUserEnabled writes spec true; specChanged nudges; endProcedure converges to spec.
    spec.setChatEnabled(true);
    r.specChanged();
    r.endProcedure(RuntimeStatus.ProcedureKind.ACTIVATION);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue(), "spec true + already online → stays online");
    assertEquals(
        List.of(),
        control.switchLog,
        "no reconciler switch at all — the engine was brought up by activation, spec agrees");
    r.close();
  }

  // (fix-b) INSTALL_SMOKE_TEST with spec off: the controller's running hold brings the engine up and
  // it is NOT parked during the window; endProcedure returns to spec → exactly one down-switch
  // (install != enable). Sequence-pinned [indexing]: the up is the controller's, not a reconciler
  // switch.
  @Test
  void installSmokeTestProcedure_specOff_engineUpNoDown_thenOneDownAtEnd() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(false), null);
    r.start();

    r.beginProcedure(RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST, "smoke");
    control.fireModeChange(Mode.OFFLINE, Mode.ONLINE); // the controller starts it for the hold
    assertEquals(Mode.ONLINE, control.currentModeValue());
    assertEquals(0, control.onlineSwitchCount.get(), "the reconciler makes no up-switch");
    // The reconciler gets a chance to (wrongly) park the engine — it must not, procedure active.
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(0, control.indexingSwitchCount.get(), "no down-switch while the procedure holds the engine");

    r.endProcedure(RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST);
    assertTrue(r.awaitQuiescent(5_000));
    assertNotEquals(Mode.ONLINE, control.currentModeValue(), "spec off: engine returns down (install != enable)");
    assertEquals(1, control.indexingSwitchCount.get(), "exactly one return-to-spec down-switch");
    assertEquals(
        List.of("indexing"),
        control.switchLog,
        "one park back to spec after the smoke test");
    r.close();
  }

  // (fix-c) VDU_BATCH + ACTIVATION concurrent: a foreign park is tolerated while BOTH are active AND
  // while only one remains; drift converges back to spec exactly once, only after the LAST ends.
  @Test
  void concurrentProcedures_driftSuppressedUntilBothEnd_thenOneConvergence() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS));
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get());

    r.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "vdu");
    r.beginProcedure(RuntimeStatus.ProcedureKind.ACTIVATION, "activation");

    // Foreign park while BOTH active — no re-convergence.
    control.fireModeChange(Mode.ONLINE, Mode.INDEXING);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.INDEXING, control.currentModeValue(), "drift suppressed while procedures active");
    assertEquals(1, control.onlineSwitchCount.get(), "no re-convergence during procedures");

    // End ONE — still suppressed (ACTIVATION holds).
    r.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.INDEXING, control.currentModeValue(), "still suppressed: one procedure remains");
    assertEquals(1, control.onlineSwitchCount.get());

    // End the LAST — converge to spec (online), exactly one up-switch.
    r.endProcedure(RuntimeStatus.ProcedureKind.ACTIVATION);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(Mode.ONLINE, control.currentModeValue(), "last procedure ended → converge to spec");
    assertEquals(2, control.onlineSwitchCount.get(), "exactly one convergence after both ended");
    assertEquals(
        List.of("online", "online"),
        control.switchLog,
        "boot up, then one convergence up after the last procedure ends (the foreign park is not a switch call)");
    r.close();
  }
}
