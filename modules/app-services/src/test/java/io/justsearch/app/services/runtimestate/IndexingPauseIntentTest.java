/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** S36.6/9/10: UI-only accepted pause intent uses the existing settings transaction. */
final class IndexingPauseIntentTest {
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();

  private OperationHandler handler(RuntimeSpecStore spec, boolean paused) {
    return assertDoesNotThrow(() -> (OperationHandler) Class.forName(
        "io.justsearch.app.services.registry.operations.handlers.IndexingPauseHandler")
        .getConstructor(Supplier.class, boolean.class).newInstance((Supplier<RuntimeSpecStore>) () -> spec, paused),
        "pause and resume require accepted UI operations");
  }

  @Test
  void pauseAndResumeAreOnlyOfferedToUi() {
    for (String id : Set.of("core.indexing-pause", "core.indexing-resume")) {
      var operation = new CoreOperationCatalog().definitions().stream()
          .filter(op -> op.id().value().equals(id)).findFirst();
      assertTrue(operation.isPresent(), id + " must exist for UI dispatch");
      assertEquals(Set.of(ExecutorTag.UI), operation.orElseThrow().executors());
    }
  }

  @Test
  void acceptedPauseAndResumePersistAndKeyedRetryCannotApplyDifferentIntent() throws Exception {
    try (var fixture = new RuntimeIntentTestFixture(directory)) {
      Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
          .getConstructor().newInstance());
      assertDoesNotThrow(() -> fixture.settings().getClass().getMethod("bindIndexingHold", hold.getClass())
          .invoke(fixture.settings(), hold));
      var pause = fixture.dispatcher("core.indexing-pause", handler(fixture.spec(), true));
      var resume = fixture.dispatcher("core.indexing-resume", handler(fixture.spec(), false));
      String key = OperationKeys.generate(Clock.systemUTC());
      long until = System.currentTimeMillis() + 60_000;
      String args = "{\"untilEpochMs\":" + until + "}";
      assertTrue(pause.dispatch(args, key).success());
      var policy = json.valueToTree(fixture.settings().load()).get("indexingPause");
      assertNotNull(policy);
      assertTrue(policy.get("paused").asBoolean());
      assertEquals(until, policy.get("untilEpochMs").asLong());
      assertEquals(true, hold.getClass().getMethod("held").invoke(hold), "the accepted commit publishes the hold");
      long revision = fixture.settings().inspect().witness().acceptedRevision();
      assertTrue(pause.dispatch(args, key).success());
      assertEquals(revision, fixture.settings().inspect().witness().acceptedRevision());
      var reused = assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
          () -> pause.dispatch("{}", key), "a lost-response retry cannot change its accepted deadline");
      assertEquals(io.justsearch.app.api.operations.OperationStoreException.Code.OPERATION_KEY_REUSED, reused.code());
      assertTrue(fixture.spec().writeIntent(true,
          io.justsearch.app.services.TestEngineContexts.internal(), null).response().success());
      assertEquals(true, hold.getClass().getMethod("held").invoke(hold), "an unrelated settings save preserves pause");
      assertTrue(resume.dispatch("{}", OperationKeys.generate(Clock.systemUTC())).success());
      var saved = json.valueToTree(fixture.settings().load());
      assertTrue(saved.get("indexingPause") == null || saved.get("indexingPause").isNull()
          || !saved.get("indexingPause").get("paused").asBoolean());
      assertEquals(false, hold.getClass().getMethod("held").invoke(hold));
      assertEquals(Boolean.TRUE, fixture.settings().load().getChatEnabled(), "Resume preserves unrelated intent");
    }
  }
}
