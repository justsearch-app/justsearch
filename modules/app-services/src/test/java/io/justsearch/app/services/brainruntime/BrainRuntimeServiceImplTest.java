/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.brainruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 737 fix pack (fix 4): {@code switchInferenceMode} records a chat-enabled intent through
 * the ONE runtime-intent authority (spec write + {@code reconciler.specChanged()}) and never
 * raw-switches the engine. The reconciler here is constructed but not started — the assertions only
 * need the synchronous spec write + spec-change nudge, not live convergence.
 */
final class BrainRuntimeServiceImplTest {

  @TempDir Path tmp;
  private final List<RuntimeIntentTestFixture> intentFixtures = new ArrayList<>();

  /** Records raw switch primitives so the test can assert they are never called. */
  private static final class RecordingOnlineAi implements OnlineAiService {
    final AtomicInteger switchOnline = new AtomicInteger();
    final AtomicInteger switchIndexing = new AtomicInteger();

    @Override
    public void switchToOnlineMode() {
      switchOnline.incrementAndGet();
    }

    @Override
    public void switchToIndexingMode() {
      switchIndexing.incrementAndGet();
    }

    @Override
    public String getCurrentMode() {
      return "indexing";
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.completedFuture("");
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.completedFuture("");
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }
  }

  private record Fixture(
      RecordingOnlineAi onlineAi,
      RuntimeSpecStore spec,
      AtomicInteger nudged,
      BrainRuntimeServiceImpl svc) {}

  private Fixture fixture(boolean initialChatEnabled) {
    final RuntimeIntentTestFixture intent;
    try {
      intent = new RuntimeIntentTestFixture(
          tmp.resolve("runtime-intent-" + intentFixtures.size()), initialChatEnabled);
    } catch (Exception failure) {
      throw new AssertionError("Failed to compose runtime intent fixture", failure);
    }
    intentFixtures.add(intent);
    RuntimeSpecStore spec = intent.spec();
    // Unstarted reconciler: specChanged() is synchronous (bump version, reset flap, notify
    // spec-change listeners) and does not touch the null control.
    RuntimeReconciler reconciler =
        new RuntimeReconciler(null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
    AtomicInteger nudged = new AtomicInteger();
    reconciler.addSpecChangeListener(nudged::incrementAndGet);
    RecordingOnlineAi onlineAi = new RecordingOnlineAi();
    BrainRuntimeServiceImpl svc =
        new BrainRuntimeServiceImpl(onlineAi, null, spec, reconciler);
    return new Fixture(onlineAi, spec, nudged, svc);
  }

  @AfterEach
  void closeIntentFixtures() {
    intentFixtures.forEach(RuntimeIntentTestFixture::close);
  }

  /**
   * Tempdoc 804 §B6 re-pin: the live mode stays "indexing" here (the reconciler is not running), and
   * that is exactly the round-10 shape that used to be reported as an unqualified success. The
   * outcome must now SAY the requested mode has only been recorded, not reached.
   */
  @Test
  void switchOnline_writesSpecTrue_nudges_noRawSwitch() throws Exception {
    Fixture f = fixture(false);
    String key = OperationKeys.generate(Clock.systemUTC());

    ModeTransitionOutcome outcome =
        f.svc().switchInferenceMode("online", TestEngineContexts.internal(), key);

    assertEquals("online", outcome.requested());
    assertEquals(
        "indexing", outcome.mode(), "returns the live getCurrentMode() (may still be transitioning)");
    assertEquals(
        ModeTransitionOutcome.STATE_RECORDED,
        outcome.state(),
        "live mode != requested mode, so the transition is recorded — not converged");
    assertTrue(f.spec().load().chatEnabled(), "intent recorded: chatEnabled=true");
    assertEquals(1, f.nudged().get(), "reconciler nudged via specChanged()");
    assertEquals(0, f.onlineAi().switchOnline.get(), "no raw switchToOnlineMode");
    assertEquals(0, f.onlineAi().switchIndexing.get(), "no raw switchToIndexingMode");

    ModeTransitionOutcome replay =
        f.svc().switchInferenceMode("online", TestEngineContexts.internal(), key);
    assertEquals(ModeTransitionOutcome.STATE_RECORDED, replay.state());
    assertEquals(key, replay.operationKey());
    assertEquals(null, replay.mode(), "a receipt replay cannot take a new live observation");
    assertEquals(1, f.nudged().get(), "a completed retry cannot nudge or observe again");

    var reused = assertThrows(OperationStoreException.class,
        () -> f.svc().switchInferenceMode("indexing", TestEngineContexts.internal(), key));
    assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, reused.code());
    assertTrue(f.spec().load().chatEnabled(), "changed target cannot reuse the completed key");
    assertEquals(1, f.nudged().get());
  }

  /** The converged case: the live mode already equals what was requested. */
  @Test
  void switchIndexing_writesSpecFalse_nudges_noRawSwitch() throws Exception {
    Fixture f = fixture(true);

    ModeTransitionOutcome outcome = f.svc().switchInferenceMode("indexing",
        TestEngineContexts.internal(), OperationKeys.generate(Clock.systemUTC()));

    assertEquals("indexing", outcome.requested());
    assertEquals("indexing", outcome.mode());
    assertEquals(
        ModeTransitionOutcome.STATE_CONVERGED,
        outcome.state(),
        "live mode == requested mode, so the transition is already converged");
    assertFalse(f.spec().load().chatEnabled(), "intent recorded: chatEnabled=false");
    assertEquals(1, f.nudged().get(), "reconciler nudged via specChanged()");
    assertEquals(0, f.onlineAi().switchOnline.get(), "no raw switchToOnlineMode");
    assertEquals(0, f.onlineAi().switchIndexing.get(), "no raw switchToIndexingMode");
  }

  @Test
  void switchInvalidMode_throwsIllegalArgument() {
    Fixture f = fixture(false);
    assertThrows(IllegalArgumentException.class, () -> f.svc().switchInferenceMode("bogus",
        TestEngineContexts.internal(), OperationKeys.generate(Clock.systemUTC())));
    assertEquals(0, f.nudged().get(), "invalid mode records no intent");
    assertEquals(0, f.onlineAi().switchOnline.get());
    assertEquals(0, f.onlineAi().switchIndexing.get());
  }
}
