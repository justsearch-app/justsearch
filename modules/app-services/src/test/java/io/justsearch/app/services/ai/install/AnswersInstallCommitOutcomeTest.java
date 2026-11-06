/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.registry.operations.handlers.CancelAiInstallHandler;
import io.justsearch.app.services.settings.SettingsComponentComposer;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What cancelling reports after a completion's settings commit failed: the real install, cancel
 * operation and recorded settings owner, with the failure injected on either side of the settings
 * file replacement. The completion's claim stands unless the saved settings prove nothing committed.
 */
final class AnswersInstallCommitOutcomeTest {
  private static final String CONSENTED =
      "{\"acceptTerms\":true,\"enableChatOnCompletion\":true}";

  /** Fails component publication, which follows the settings file replacement. */
  private static final class FailingPublication implements SettingsComponentComposer {
    @Override public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
        Map<String, Set<String>> affected) {
      return new Prepared() {
        @Override public void includeObservation(
            io.justsearch.core.component.EngineComponentSnapshot.Component observation) {
          throw new UnsupportedOperationException("No generation projection");
        }
        @Override public void withOwnerLocks(Runnable publication) { publication.run(); }
        @Override public void validate() { }
        @Override public void install() {
          if (Boolean.TRUE.equals(candidate.getChatEnabled())) {
            throw new IllegalStateException("Answer component publication failed");
          }
        }
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

  /** Releases the download and waits for the run to end with its completion's commit failing. */
  private static void finishWithFailedCompletion(AnswersInstallRig rig) {
    rig.release.countDown();
    var ended = assertThrows(ExecutionException.class,
        () -> rig.completion.toCompletableFuture().get(30, TimeUnit.SECONDS));
    assertFalse(ended.getCause() instanceof CancellationException, ended.toString());
    assertEquals("failed", rig.installer.getStatus().state, rig.installer.getStatus().message);
  }

  /** Cancelling reports the completion and records nothing. */
  private static void assertCancelReportsAlreadyCompleted(AnswersInstallRig rig) {
    var cancel = cancelThroughOperation(rig);
    assertTrue(cancel.success(), cancel.toString());
    assertEquals("AI install already completed; nothing was cancelled", cancel.message());
    assertEquals(Boolean.TRUE, cancel.structuredData().get("alreadyCompleted"));
    assertFalse(rig.installer.getStatus().cancelRequested,
        "A committed enable must not record a cancellation request");
  }

  @Test
  void cancelAfterComponentPublicationFailedPastTheCommitReportsAlreadyCompleted(
      @TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home, new FailingPublication())) {
      rig.start(CONSENTED);
      finishWithFailedCompletion(rig);

      assertEquals(Boolean.TRUE, rig.store.inspect().settings().getChatEnabled(),
          "The enable was saved before publication failed");
      assertCancelReportsAlreadyCompleted(rig);
      assertEquals(Boolean.TRUE, rig.store.inspect().settings().getChatEnabled());
    }
  }

  @Test
  void cancelAfterSavingTheTerminalStateFailedPastTheCommitReportsAlreadyCompleted(
      @TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      doAnswer(call -> {
        call.callRealMethod();
        throw new IllegalStateException("The operation's terminal state was not saved");
      }).when(rig.settings).applyInternal(any(), any(), any(), any(), any(BooleanSupplier.class));
      rig.start(CONSENTED);
      finishWithFailedCompletion(rig);

      assertEquals(Boolean.TRUE, rig.store.inspect().settings().getChatEnabled(),
          "The enable was saved before the failure");
      assertCancelReportsAlreadyCompleted(rig);
    }
  }

  @Test
  void cancelAfterAnUnreadableCommitOutcomeReportsAlreadyCompleted(@TempDir Path home)
      throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      doAnswer(call -> {
        doThrow(new UncheckedIOException(new IOException("Unreadable"))).when(rig.store).inspect();
        throw new IOException("Replacement outcome unknown");
      }).when(rig.store).replacePrepared(any());
      rig.start(CONSENTED);
      finishWithFailedCompletion(rig);

      doCallRealMethod().when(rig.store).inspect();
      assertCancelReportsAlreadyCompleted(rig);
    }
  }

  @Test
  void aRefusedCancellationLeavesTheInstallsOwnMessage(@TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      var before = new AtomicReference<String>();
      var after = new AtomicReference<String>();
      // Reconciliation is told only after the enable committed, before completion bookkeeping.
      rig.installer.setChatIntentChanged(() -> {
        before.set(rig.installer.getStatus().message);
        cancelThroughOperation(rig);
        after.set(rig.installer.getStatus().message);
      });
      rig.start(CONSENTED);
      rig.finish();

      assertNotNull(before.get(), "Cancellation must have been requested after the commit");
      assertEquals(before.get(), after.get(),
          "The page shows this message; a refused cancellation has nothing to add to it");
    }
  }

  @Test
  void cancelAfterAProvenFailedReplacementIsAccepted(@TempDir Path home) throws Exception {
    try (var rig = new AnswersInstallRig(home)) {
      doThrow(new IOException("Disk full")).when(rig.store).replacePrepared(any());
      rig.start(CONSENTED);
      var before = rig.store.inspect();
      finishWithFailedCompletion(rig);

      var cancel = cancelThroughOperation(rig);
      assertEquals("AI install cancel requested", cancel.message());
      assertNull(cancel.structuredData().get("alreadyCompleted"));
      assertEquals(before.witness(), rig.store.inspect().witness(), "Nothing was saved");
      assertEquals(Boolean.FALSE, rig.store.inspect().settings().getChatEnabled());
    }
  }
}
