/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.registry.operations.handlers.CancelAiInstallHandler;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One boundary between cancelling an install and its completion switching answers on: the real
 * install, cancel operation, recorded settings owner and its commit admission, with the owner's
 * answer-component preparation held by the test so cancellation lands at an exact point.
 */
final class AnswersInstallCancellationBoundaryTest {
  private static final String CONSENTED =
      "{\"acceptTerms\":true,\"enableChatOnCompletion\":true}";

  /** Holds the settings owner's preparation of a matching candidate until released. */
  private static final class HeldPreparation implements SettingsComponentComposer {
    final java.util.function.Predicate<UiSettings> held;
    final CountDownLatch preparing = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);

    HeldPreparation(java.util.function.Predicate<UiSettings> held) { this.held = held; }

    @Override public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected) {
      if (held.test(candidate)) {
        preparing.countDown();
        try {
          if (!proceed.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
      return new Prepared() {
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component observation) {
          throw new UnsupportedOperationException("No generation projection");
        }
        @Override public void withOwnerLocks(Runnable publication) { publication.run(); }
        @Override public void validate() { }
        @Override public void install() { }
        @Override public void notifyObservers() { }
        @Override public void retire() { }
        @Override public void abort() { }
      };
    }
  }

  private static OperationResult cancelThroughOperation(AnswersInstallRig rig) {
    return new CancelAiInstallHandler(() -> new BrainInstallServiceImpl(rig.installer))
        .execute("{}", TestEngineContexts.internal());
  }

  @Test
  void cancelWhileTheAnswersEnableIsPreparingLeavesAnswersOffAndTheInstallCancelled(
      @TempDir Path home) throws Exception {
    var held = new HeldPreparation(candidate -> Boolean.TRUE.equals(candidate.getChatEnabled()));
    try (var rig = new AnswersInstallRig(home, held)) {
      rig.start(CONSENTED);
      var before = rig.store.inspect();
      rig.release.countDown();
      assertTrue(held.preparing.await(20, TimeUnit.SECONDS),
          "The completion's answers enable must reach the settings owner's preparation");
      var cancel = cancelThroughOperation(rig);
      held.proceed.countDown();
      var ended = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> rig.completion.toCompletableFuture().get(30, TimeUnit.SECONDS));
      assertInstanceOf(java.util.concurrent.CancellationException.class, ended.getCause(),
          "The start operation must end as cancelled");

      assertTrue(cancel.success(), cancel.toString());
      assertEquals("AI install cancel requested", cancel.message());
      assertEquals("cancelled", rig.installer.getStatus().state, rig.installer.getStatus().message);
      var after = rig.store.inspect();
      assertEquals(Boolean.FALSE, after.settings().getChatEnabled(),
          "A cancellation that won before commit admission must leave answers as they were");
      assertEquals(before.witness(), after.witness(), "Nothing may be saved after the cancellation");
    }
  }

  @Test
  void cancelWhileThePictureReadingEnableIsPreparingSavesNothingAndCancels(@TempDir Path home)
      throws Exception {
    var held = new HeldPreparation(
        candidate -> Boolean.TRUE.equals(candidate.getPictureReadingEnabled()));
    try (var rig = new AnswersInstallRig(home, held)) {
      rig.start("{\"acceptTerms\":true,\"enablePictureReadingOnCompletion\":true}");
      var before = rig.store.inspect();
      rig.release.countDown();
      assertTrue(held.preparing.await(20, TimeUnit.SECONDS),
          "The completion's picture-reading enable must reach the settings owner's preparation");
      var cancel = cancelThroughOperation(rig);
      held.proceed.countDown();
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> rig.completion.toCompletableFuture().get(30, TimeUnit.SECONDS));

      assertEquals("AI install cancel requested", cancel.message());
      assertEquals("cancelled", rig.installer.getStatus().state, rig.installer.getStatus().message);
      var after = rig.store.inspect();
      assertEquals(Boolean.FALSE, after.settings().getPictureReadingEnabled());
      assertEquals(before.witness(), after.witness(), "Nothing may be saved after the cancellation");
    }
  }

  @Test
  void cancelAfterTheAnswersEnableCommittedReportsAlreadyCompletedAndChangesNothing(
      @TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      var cancel = new AtomicReference<OperationResult>();
      var answersWhenCancelled = new AtomicReference<Boolean>();
      // Reconciliation is told only after the enable committed, before completion bookkeeping.
      rig.installer.setChatIntentChanged(() -> {
        answersWhenCancelled.set(rig.store.inspect().settings().getChatEnabled());
        cancel.set(cancelThroughOperation(rig));
      });
      rig.start(CONSENTED);
      var committed = rig.finish();

      assertEquals(Boolean.TRUE, answersWhenCancelled.get(), "The enable had committed first");
      assertNotNull(cancel.get(), "Cancellation must have been requested after the commit");
      assertTrue(cancel.get().success(), cancel.get().toString());
      assertEquals("AI install already completed; nothing was cancelled", cancel.get().message());
      assertEquals(Boolean.TRUE, cancel.get().structuredData().get("alreadyCompleted"));
      assertFalse(rig.installer.getStatus().cancelRequested,
          "A completed install must not record a cancellation request");
      assertEquals(Boolean.TRUE, committed.settings().getChatEnabled(),
          "Cancellation after commit must not change answers");
    }
  }
}
