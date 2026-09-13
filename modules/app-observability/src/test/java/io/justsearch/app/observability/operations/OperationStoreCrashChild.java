/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Abrupt process-exit fixture: deliberately runs neither close nor shutdown hooks. */
public final class OperationStoreCrashChild {
  private OperationStoreCrashChild() {}

  public static void main(String[] args) throws Exception {
    Clock clock = Clock.fixed(Instant.ofEpochMilli(Long.parseLong(args[2])), ZoneOffset.UTC);
    try (var store = new SqliteOperationStore(Path.of(args[0]), clock, step -> {
      if (step.equals(args[1])) Runtime.getRuntime().halt(71);
    })) {
      if (java.util.Set.of("before-accept", "after-accept-before-effect", "after-first-effect").contains(args[1])) {
        Path effects = Path.of(args[0]).resolveSibling("effects.db");
        String point = args[1];
        String key = args[3];
        if (point.equals("before-accept")) Runtime.getRuntime().halt(71);
        var context = new io.justsearch.core.context.EngineContext(
            io.justsearch.core.context.EngineContext.ClientKind.INTERNAL, "crash-child",
            java.util.Optional.empty(), java.util.Optional.empty(), "system", "SYSTEM_INTERNAL",
            io.justsearch.core.context.EngineContext.Survival.DURABLE,
            io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
        var descriptor = io.justsearch.app.api.operations.OperationDescriptor.invocation(
            io.justsearch.agent.api.registry.OperationKind.OPERATION, "core.crash-proof", "{}", false);
        var runner = new OperationAttemptRunnerImpl(store, clock, java.util.Set.of());
        var attempt = runner.accept(new io.justsearch.app.api.operations.OperationAttemptRunner.Request(
            key, descriptor, context, null));
        if (point.equals("after-accept-before-effect")) Runtime.getRuntime().halt(71);
        runner.start(attempt, record -> {
          try (var database = java.sql.DriverManager.getConnection("jdbc:sqlite:" + effects);
              var insert = database.prepareStatement("INSERT INTO effects(operation_key) VALUES (?)")) {
            insert.setString(1, record.key());
            if (insert.executeUpdate() != 1) throw new AssertionError("Effect did not commit");
          } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
          if (point.equals("after-first-effect")) Runtime.getRuntime().halt(71);
          return io.justsearch.agent.api.registry.OperationExecution.finished(
              io.justsearch.agent.api.registry.OperationResult.success("effect complete"));
        });
      }
      throw new AssertionError("Requested crash point was not reached; recovery=" + store.recovery());
    }
  }

}
