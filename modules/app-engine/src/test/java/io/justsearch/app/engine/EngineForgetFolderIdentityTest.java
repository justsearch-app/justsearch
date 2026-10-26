/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationState;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Real file-to-directory history and public prepared FORGET through the process owner. */
@Timeout(90)
final class EngineForgetFolderIdentityTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "true,true", "false,false"})
  void FX55FolderCoversEqualityAndDescendantsWithoutSiblingWidening(boolean equality, boolean descendants)
      throws Exception {
    Path target = Files.writeString(directory.resolve("b"), "old source at exact target");
    Path sibling = Files.writeString(directory.resolve("bc"), "unrelated sibling source");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      // Proper parent/chunks use the real writer while this path is still a physical file.
      if (equality) { f.seed(target, "old exact indexed text"); assertEquals(1, f.queue.enqueue(List.of(target))); }
      Files.delete(target); Files.createDirectory(target);
      Path child = Files.writeString(target.resolve("child.txt"), "new descendant source");
      if (descendants) { f.seed(child, "descendant indexed text"); assertEquals(1, f.queue.enqueue(List.of(child))); }
      f.seed(sibling, "unrelated sibling indexed text"); assertEquals(1, f.queue.enqueue(List.of(sibling)));
      assertEquals(!equality, f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false));
      assertEquals(!descendants, f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(child), false));
      // This is the real UI handler preparation/accepted SQLite intent/Engine owner interface.
      // A preparation refusal is a setup failure, never an asserted behavioral reproduction.
      var result = f.forget(target, "FOLDER");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("CONFIRMED", outcome.path("removal").asString());
      assertEquals("INSTALLED", outcome.path("keepOut").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(EngineForgetInProcessTest.key(target)),
          "folder equality includes the old exact parent and both proper chunks");
      assertEquals(0, f.runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
          EngineForgetInProcessTest.key(target), List.of(), List.of()));
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false),
          "exact target queued work cannot survive a COMPLETE folder receipt");
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), true));
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(EngineForgetInProcessTest.key(sibling)));
      assertFalse(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(sibling), false));
      assertTrue(Files.isDirectory(target)); assertEquals("new descendant source", Files.readString(child));
      assertEquals("unrelated sibling source", Files.readString(sibling));
      var entry = LiteralExclusion.fromEntry(f.settings.inspect().settings().getExcludePatterns().getFirst());
      assertEquals(target.toString(), entry.literal().path());
      assertEquals(LiteralExclusion.Scope.FOLDER, entry.literal().scope());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void FX55ExactOrDescendantQueueDeleteFailureRemainsIncompleteAndRetryConverges(boolean failExact)
      throws Exception {
    Path target = Files.writeString(directory.resolve("b"), "former exact source");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      f.seed(target, "old exact indexed text"); assertEquals(1, f.queue.enqueue(List.of(target)));
      Files.delete(target); Files.createDirectory(target);
      Path child = Files.writeString(target.resolve("child.txt"), "descendant source");
      f.seed(child, "descendant indexed text"); assertEquals(1, f.queue.enqueue(List.of(child)));
      var db = (Connection) EngineForgetInProcessTest.field(f.queue, SqliteJobQueue.class, "connection");
      String failedKey = EngineForgetInProcessTest.key(failExact ? target : child);
      try (var statement = db.createStatement()) {
        statement.execute("CREATE TRIGGER private_forget_failure BEFORE DELETE ON jobs WHEN OLD.path = '"
            + failedKey.replace("'", "''") + "' BEGIN SELECT RAISE(ABORT, 'private fixture delete failure'); END");
      }
      var result = f.forgetAttempt(target, "FOLDER");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("INCOMPLETE", outcome.path("status").asString(), result.message());
      assertNotEquals("CONFIRMED", outcome.path("removal").asString());
      String key = outcome.path("operationKey").asString();
      assertFalse(f.operations.find(key).orElseThrow().state().terminal());
      try (var statement = db.createStatement()) { statement.execute("DROP TRIGGER private_forget_failure"); }
      var retry = f.root.forgetService().retry(key, EngineForgetInProcessTest.webview());
      assertEquals("COMPLETE", JSON.valueToTree(retry.structuredData().get("forget")).path("status").asString(), retry.message());
      assertEquals(OperationState.COMPLETE, f.operations.find(key).orElseThrow().state());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(EngineForgetInProcessTest.key(target)));
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false));
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), true));
      assertEquals("descendant source", Files.readString(child));
    }
  }

  @org.junit.jupiter.api.Test
  void FX55IgnoredExactQueueDeleteCannotBypassInclusiveAbsenceProof() throws Exception {
    Path target = Files.writeString(directory.resolve("b"), "former exact source");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      f.seed(target, "old exact indexed text"); assertEquals(1, f.queue.enqueue(List.of(target)));
      Files.delete(target); Files.createDirectory(target);
      Path child = Files.writeString(target.resolve("child.txt"), "descendant source");
      f.seed(child, "descendant indexed text"); assertEquals(1, f.queue.enqueue(List.of(child)));
      var db = (Connection) EngineForgetInProcessTest.field(f.queue, SqliteJobQueue.class, "connection");
      try (var statement = db.createStatement()) {
        statement.execute("CREATE TRIGGER private_forget_ignore BEFORE DELETE ON jobs WHEN OLD.path = '"
            + EngineForgetInProcessTest.key(target).replace("'", "''")
            + "' BEGIN SELECT RAISE(IGNORE); END");
      }
      var result = f.forgetAttempt(target, "FOLDER");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("INCOMPLETE", outcome.path("status").asString(),
          "a nonnegative delete result cannot certify a still-present exact job: " + result.message());
      assertNotEquals("CONFIRMED", outcome.path("removal").asString());
      assertFalse(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false));
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(child), false));
      String key = outcome.path("operationKey").asString();
      try (var statement = db.createStatement()) { statement.execute("DROP TRIGGER private_forget_ignore"); }
      var retry = f.root.forgetService().retry(key, EngineForgetInProcessTest.webview());
      assertEquals("COMPLETE", JSON.valueToTree(retry.structuredData().get("forget")).path("status").asString(), retry.message());
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false));
      assertEquals("descendant source", Files.readString(child));
    }
  }

  @org.junit.jupiter.api.Test
  void FX55UnknownQueueAbsenceAfterCommittedDeletesNeverBecomesZeroOrComplete() throws Exception {
    Path target = Files.writeString(directory.resolve("b"), "former exact source");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      f.seed(target, "old exact indexed text"); assertEquals(1, f.queue.enqueue(List.of(target)));
      Files.delete(target); Files.createDirectory(target);
      Path child = Files.writeString(target.resolve("child.txt"), "descendant source");
      f.seed(child, "descendant indexed text"); assertEquals(1, f.queue.enqueue(List.of(child)));
      var connectionField = SqliteJobQueue.class.getDeclaredField("connection"); connectionField.setAccessible(true);
      var actual = (Connection) connectionField.get(f.queue);
      // Private fault instrumentation at the existing JDBC absence-read boundary only; all real
      // deletes/commits run on the original private connection. No production hook or fake success.
      Connection fault = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                && sql.startsWith("SELECT 1 FROM jobs WHERE ")) {
              throw new SQLException("private fixture: absence read unavailable", "SQLITE_IOERR", 10);
            }
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
          });
      connectionField.set(f.queue, fault);
      String key;
      try {
        var result = f.forgetAttempt(target, "FOLDER");
        var outcome = JSON.valueToTree(result.structuredData().get("forget"));
        assertEquals("INCOMPLETE", outcome.path("status").asString(), result.message());
        assertNotEquals("CONFIRMED", outcome.path("removal").asString());
        key = outcome.path("operationKey").asString();
        assertFalse(f.operations.find(key).orElseThrow().state().terminal());
      } finally { connectionField.set(f.queue, actual); }
      var retry = f.root.forgetService().retry(key, EngineForgetInProcessTest.webview());
      assertEquals("COMPLETE", JSON.valueToTree(retry.structuredData().get("forget")).path("status").asString(), retry.message());
      assertEquals("descendant source", Files.readString(child));
    }
  }
}
