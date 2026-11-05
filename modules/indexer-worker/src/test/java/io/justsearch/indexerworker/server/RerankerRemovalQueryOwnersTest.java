/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.QueryRoleSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Remove files" for Best match first (design D4, repair round 4) asks the owner that issues and
 * retires query views whether a reranker session is still held. Switching Best match first off
 * publishes a view without it, while queries issued before keep the previous view and its
 * reranker: those files stay in use until every such view, forked child leases included, is
 * released.
 */
final class RerankerRemovalQueryOwnersTest {

  /** Switches Best match first off through the real query owner, as the settings owner does. */
  private static void switchOff(KnowledgeServerQuerySettingsOwnerTest.QueryFixture f,
      org.mockito.MockedStatic<InferenceCompositionRoot> composition) {
    UiSettings settings = new UiSettings();
    settings.setRerankerEnabled(false);
    composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(),
        any(), any())).thenAnswer(call -> {
          QueryRoleSelection selected = call.getArgument(1);
          return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
              Optional.empty(), Optional.empty(), Optional.empty(), null, List.of(),
              new InferenceSurface.ComponentObservation(Optional.of(
                  EncoderConfigurationProjection.from(f.configuration).queryDigest()), Set.of(),
                  Set.of(), Optional.of(selected)));
        });
    var prepared = f.server.prepareQueryRoleSettings(
        settings, f.configuration, Set.of("rerankerEnabled"), f.selection);
    assertEquals(QueryRoleSelection.State.DISABLED, prepared.selection().reranker().state());
    prepared.withOwnerLocks(() -> { prepared.validate(); prepared.install(); });
    prepared.notifyObservers();
    prepared.retire();
  }

  @Test
  void anIssuedViewFromBeforeOffKeepsBestMatchFirstInUseUntilItIsReleased(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = f.composition()) {
      var issued = f.server.captureServingView();
      switchOff(f, composition);

      assertEquals(Optional.of(true), f.server.rerankerSessionHeld(),
          "a query issued before Off still holds the previous view and its reranker");

      issued.close();

      assertEquals(Optional.of(false), f.server.rerankerSessionHeld(),
          "released: no query owner holds a Best match first session");
    }
  }

  @Test
  void nothingIsConfirmedWhileTheBootModelCompositionIsStillRunning(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      // The boot composition opens its models on its own thread, outside the runtime lock; a
      // reranker it is opening belongs to no owner yet.
      var boot = new java.util.concurrent.CompletableFuture<Void>();
      f.server.deferredModelInit = boot;
      try {
        assertEquals(Optional.empty(), f.server.rerankerSessionHeld(),
            "unknown while the boot composition runs: the removal refuses");
      } finally {
        boot.complete(null); // closing the server waits for the boot composition
      }

      assertEquals(Optional.of(true), f.server.rerankerSessionHeld(),
          "once it finished, the owners answer: the serving view holds Best match first");
    }
  }

  @Test
  void aForkedChildLeaseKeepsBestMatchFirstInUseAfterItsParentIsReleased(@TempDir Path dir)
      throws Exception {
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = f.composition()) {
      var issued = f.server.captureServingView();
      var child = issued.fork();
      switchOff(f, composition);
      issued.close();

      assertEquals(Optional.of(true), f.server.rerankerSessionHeld(),
          "child work retains the issued view and its reranker");

      child.close();

      assertEquals(Optional.of(false), f.server.rerankerSessionHeld());
    }
  }
}
