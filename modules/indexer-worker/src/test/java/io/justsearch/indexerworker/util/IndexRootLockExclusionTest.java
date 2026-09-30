package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexRootLockExclusionTest {
  @TempDir Path directory;

  @Test
  void metadataNamesProcessStartRatherThanAcquisitionTime() throws Exception {
    var start = ProcessHandle.current().info().startInstant().orElseThrow();
    try (var owner = new IndexRootLock(directory.resolve("index"))) {
      owner.acquire();
    }
    var metadata = new Properties();
      try (var reader = Files.newBufferedReader(directory.resolve("index.index.lock"))) {
        metadata.load(reader);
      }
    assertEquals(start, Instant.parse(metadata.getProperty("started_at")));
  }

  @Test
  void rejectedSameJvmContenderCannotReleaseTheOwnersProcessLock() throws Exception {
    try (var owner = new IndexRootLock(directory.resolve("index"))) {
      owner.acquire();
      try (var contender = new IndexRootLock(directory.resolve("index"))) {
        assertThrows(java.io.IOException.class, contender::acquire);
      }
      assertEquals(23, probe(), "another JVM must still be refused after the contender closes");
    }
    assertEquals(0, probe(), "normal close must allow a new JVM to acquire");
  }

  @Test
  void unlockedStaleMetadataDoesNotPreventAcquisition() throws Exception {
    Files.writeString(directory.resolve("index.index.lock"), "pid=99999999\nstarted_at=2000-01-01T00:00:00Z\n");
    assertEquals(0, probe());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void directoryAliasSharesSameJvmAndProcessExclusion(boolean aliasOwns) throws Exception {
    Path base = Files.createDirectory(directory.resolve("index"));
    Path alias = directory.resolve("index-alias");
    createDirectoryAlias(alias, base);
    assertEquals(base.toRealPath(), alias.toRealPath(), "the fixture must alias the same directory");
    Path ownerPath = aliasOwns ? alias : base;
    Path contenderPath = aliasOwns ? base : alias;
    try (var owner = new IndexRootLock(ownerPath)) {
      owner.acquire();
      try (var contender = new IndexRootLock(contenderPath)) {
        assertThrows(java.io.IOException.class, contender::acquire);
      }
      assertEquals(23, probe(contenderPath, false), "another JVM must share the canonical exclusion");
    }
    assertEquals(0, probe(alias, false));
    assertEquals(0, probe(base, false));
  }

  @Test
  void unresolvedDirectoryAliasCannotAcquireASeparateLock() throws Exception {
    Path base = Files.createDirectory(directory.resolve("index"));
    Path alias = directory.resolve("index-alias");
    createDirectoryAlias(alias, base);
    Files.move(base, directory.resolve("moved-index"));
    assertTrue(Files.exists(alias, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    try (var contender = new IndexRootLock(alias)) {
      assertThrows(java.io.IOException.class, contender::acquire);
    }
  }

  private void createDirectoryAlias(Path alias, Path target) throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows")) {
      Files.createSymbolicLink(alias, target);
      return;
    }
    // Same directory-alias fixture as IndexedRootGrantScopeTest: a Windows junction needs
    // no symlink privilege. Failure is a failed witness, not a silently skipped lock check.
    Path output = directory.resolve("junction.log");
    var child = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), target.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "junction creation must exit");
      assertEquals(0, child.exitValue(), () -> "junction creation failed: " + readOutput(output));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "owned junction helper must stop");
      }
    }
  }

  @Test
  void processDeathReleasesLockWithoutDeletingItsFile() throws Exception {
    assertEquals(71, probe(true), "child halts while holding its acquired lock");
    assertTrue(Files.isRegularFile(directory.resolve("index.index.lock")));
    assertEquals(0, probe());
  }

  private int probe() throws Exception {
    return probe(false);
  }

  private int probe(boolean halt) throws Exception {
    return probe(directory.resolve("index"), halt);
  }

  private int probe(Path indexBase, boolean halt) throws Exception {
    Path output = directory.resolve("probe.log");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), indexBase.toString(), halt ? "halt" : "close")
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "lock probe must exit");
      int result = child.exitValue();
      assertTrue(result == 0 || result == 23 || (halt && result == 71), () -> "unexpected probe failure: " + readOutput(output));
      return result;
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "owned probe must stop");
      }
    }
  }

  private static String readOutput(Path output) {
    try {
      return Files.readString(output);
    } catch (java.io.IOException failure) {
      return failure.toString();
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      try (var lock = new IndexRootLock(Path.of(args[0]))) {
        lock.acquire();
        if ("halt".equals(args[1])) Runtime.getRuntime().halt(71);
      } catch (java.io.IOException refused) {
        System.exit(23);
      }
    }
  }
}
