/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real process kills: no close(), rollback callback or shutdown hook gets to tidy up the cut. */
@Timeout(600)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030CrashRecoveryTest {
  @Test
  void killedQueueMigrationPreservesEveryReleasedSavedJobField(@TempDir Path temporary)
      throws Exception {
    for (String cut : List.of("migration-17", "migration-22", "migration-24")) {
      Path root = Release030Fixture.unpack("small", temporary.resolve(cut));
      var before = savedJobs(root);
      killAt(root, cut);
      try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) { queue.open(); }
      var after = savedJobs(root);
      assertEquals(before.keySet(), after.keySet(), "no job is deleted at " + cut);
      for (var row : before.entrySet()) {
        for (var field : row.getValue().entrySet()) {
          assertEquals(field.getValue(), after.get(row.getKey()).get(field.getKey()),
              cut + ": " + row.getKey() + "." + field.getKey());
        }
      }
      assertTrue(Files.exists(root.resolve("data/jobs.db.bak")), "production backup ran");
    }
  }

  @Test
  void killsDuringRepairAndRecoveryConvergeLikeUninterruptedBoot(@TempDir Path temporary)
      throws Exception {
    Path uninterrupted = Release030Fixture.unpack("small", temporary.resolve("uninterrupted"));
    prepareWaits(uninterrupted);
    var expected = finished(uninterrupted);
    for (String cut : List.of("repair-boundaries", "repair-retries", "repair-committed",
        "recovery-admitted", "recovery-second-root")) {
      Path root = Release030Fixture.unpack("small", temporary.resolve(cut));
      prepareWaits(root);
      killAt(root, cut);
      assertEquals(expected, finished(root), "recovery converges after " + cut);
    }
  }

  private static Map<String, String> finished(Path root) throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
        var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        assertEquals("IDLE", state(root).migration_state(), "kill recovery never starts a full rebuild");
        activate(server);
        await(() -> !Files.exists(root.resolve("data/upgrade-030-recovery.properties"))
            && count(root, "state='PENDING' AND retry_after IS NULL OR state='PROCESSING'") == 0,
            "checkpointed work finishes after restart");
        Map<String, String> result = new java.util.TreeMap<>();
        for (var entry : savedJobs(root).entrySet()) {
          String path = entry.getKey().substring(key(root).length());
          var row = entry.getValue();
          String summary = row.get("state") + ":" + row.get("attempts");
          if ("PARSER_TIMEOUT".equals(row.get("last_outcome_class"))
              || "IO_FAILED".equals(row.get("last_outcome_class")) || "FAILED".equals(row.get("state"))) {
            var preserved = new java.util.TreeMap<String, Object>();
            for (String column : List.of("state", "attempts", "last_updated", "collection", "size_bytes",
                "retry_after", "first_failed_at", "error_message", "last_outcome_class", "last_reason_code",
                "last_retry_policy", "last_diagnostic_summary", "last_outcome_at")) {
              preserved.put(column, row.get(column));
            }
            summary += ":" + preserved;
          }
          result.put(path, summary);
        }
        for (String file : List.of("roots/alpha/file-00.txt", "roots/alpha/pending.txt",
            "roots/beta/processing.txt", "explicit.txt", "orphan/exhausted.txt")) {
          assertTrue(search(server, "copper").contains(key(root.resolve(file))), file);
        }
        System.out.println("Release030 recovered " + root.getFileName() + ": " + result);
        return result;
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static void prepareWaits(Path root) throws Exception {
    // Fixed deadlines make field comparisons independent of wall time and the capture date.
    sql(root, "UPDATE jobs SET retry_after=4102444800000 WHERE last_outcome_class IN ('PARSER_TIMEOUT','IO_FAILED')");
  }

  static Map<String, Map<String, Object>> savedJobs(Path root) throws Exception {
    var jobs = new java.util.TreeMap<String, Map<String, Object>>();
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM jobs ORDER BY path")) {
      while (rows.next()) {
        Map<String, Object> fields = new HashMap<>();
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
          fields.put(rows.getMetaData().getColumnName(column), rows.getObject(column));
        }
        jobs.put(rows.getString("path"), fields);
      }
    }
    return jobs;
  }

  private static void killAt(Path root, String cut) throws Exception {
    Path arguments = root.resolve("child.args");
    Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "--enable-native-access=ALL-UNNAMED",
        "-Djava.io.tmpdir=" + Files.createDirectories(root.resolve("jvm-tmp")),
        "@" + arguments, Release030CrashRecoveryTest.class.getName(), root.toString(), cut));
    var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile());
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().put("JUSTSEARCH_HOME", root.resolve("home").toString());
    builder.environment().put("JUSTSEARCH_DATA_DIR", root.resolve("data").toString());
    Process child = builder.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (child.isAlive() && !Files.exists(root.resolve("cut")) && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(Files.exists(root.resolve("cut")), "child reached " + cut + ": " + Files.readString(root.resolve("child.log")));
      child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS), "recorded child process exited");
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
    }
  }

  /** Subprocess driver, using existing hooks/proxies instead of production crash-test options. */
  public static void main(String[] args) throws Exception {
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    Path root = Path.of(args[0]);
    String cut = args[1];
    if (cut.startsWith("migration-")) {
      Class<?> hookType = Class.forName(SqliteJobQueue.class.getName() + "$MigrationStepHook");
      Object hook = Proxy.newProxyInstance(hookType.getClassLoader(), new Class<?>[] {hookType},
          (proxy, method, values) -> {
            if (method.getName().equals("afterStep") && values[0].equals(Integer.valueOf(cut.substring(10)))) hold(root);
            return null;
          });
      var constructor = SqliteJobQueue.class.getDeclaredConstructor(Path.class, int.class, Runnable.class, hookType);
      constructor.setAccessible(true);
      try (var queue = constructor.newInstance(root.resolve("data/jobs.db"), 3, (Runnable) () -> {}, hook)) {
        bootUntilCut(root, queue, null);
      }
    } else if (cut.startsWith("repair-")) {
      try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
        bootUntilCut(root, queue, cut);
      }
    } else {
      configure(root);
      var models = new ReleasedModelIdentities(root);
      try (models;
          var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        var task = KnowledgeServer.class.getDeclaredField("release030RecoveryTask");
        task.setAccessible(true);
        ((java.util.concurrent.Future<?>) task.get(server)).cancel(false);
        activate(server);
        Object sync = Release030ExtractionRecoveryTest.field(server.appServices().ingestService(), "syncOps");
        var queueField = sync.getClass().getDeclaredField("jobQueue");
        queueField.setAccessible(true);
        Object original = queueField.get(sync);
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        queueField.set(sync, Proxy.newProxyInstance(JobQueue.class.getClassLoader(), new Class<?>[] {JobQueue.class},
            (proxy, method, values) -> {
              Object result = invoke(original, method, values);
              if (method.getName().equals("enqueueUpgradeRecoveryEntries")) {
                int batch = batches.incrementAndGet();
                if (cut.equals("recovery-admitted") && batch == 1
                    || cut.equals("recovery-second-root") && batch == 2) hold(root);
              }
              return result;
            }));
        var recover = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
        recover.setAccessible(true);
        recover.invoke(server);
      }
    }
    throw new IllegalStateException("Requested cut was not reached: " + cut);
  }

  /** The real boot constructs its queue facade; all calls delegate to the actual hooked queue. */
  private static void bootUntilCut(Path root, SqliteJobQueue queue, String repairCut) throws Exception {
    configure(root);
    var models = new ReleasedModelIdentities(root);
    var construction = org.mockito.Mockito.mockConstruction(SqliteJobQueue.class,
        org.mockito.Mockito.withSettings().defaultAnswer(call -> {
          Object result = invoke(queue, call.getMethod(), call.getArguments());
          if (repairCut != null && call.getMethod().getName().equals("open")) {
            interceptRepair(queue, root, repairCut);
          }
          return result;
        }));
    try (models; construction; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
      throw new IllegalStateException("Boot passed requested queue cut: " + server.lifecycleManagerForTests());
    }
  }

  private static void interceptRepair(SqliteJobQueue queue, Path root, String cut) throws Exception {
    var field = SqliteJobQueue.class.getDeclaredField("connection");
    field.setAccessible(true);
    Connection connection = (Connection) field.get(queue);
    var repaired = new java.util.concurrent.atomic.AtomicBoolean();
    field.set(queue, Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, values) -> {
          Object result = invoke(connection, method, values);
          if (method.getName().equals("commit") && cut.equals("repair-committed") && repaired.get()) hold(root);
          if (!method.getName().equals("createStatement")) return result;
          return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
              (statement, operation, parameters) -> {
                Object value = invoke(result, operation, parameters);
                if (operation.getName().equals("executeUpdate") && parameters[0] instanceof String sql) {
                  if (sql.contains("WHERE state='RETRY_EXHAUSTED'")) repaired.set(true);
                  if (cut.equals("repair-boundaries") && sql.startsWith("UPDATE jobs SET ingestion_root=path")
                      || cut.equals("repair-retries") && repaired.get()) hold(root);
                }
                return value;
              });
        }));
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
    try { return method.invoke(target, arguments); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }

  private static void hold(Path root) throws Exception {
    Files.writeString(root.resolve("cut"), "reached");
    while (true) Thread.sleep(1_000);
  }
}
