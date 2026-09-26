/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWritesTest {
  @TempDir Path tempDir;

  @Test
  void replacesExistingBytesAndCleansTemporaryFile() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");

    AtomicFileWrites.replaceUtf8(target, "new");

    assertEquals("new", Files.readString(target));
    try (var files = Files.list(tempDir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void createsParentDirectories() throws Exception {
    Path target = tempDir.resolve("nested").resolve("state.bin");
    AtomicFileWrites.replace(target, new byte[] {1, 2, 3});
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
  }

  @Test
  void fallsBackWhenAtomicMoveIsUnsupported() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.atomicUnsupported = true;

    AtomicFileWrites.replace(target, "new".getBytes(), files);

    assertEquals("new", Files.readString(target));
    assertEquals(1, files.fallbackMoves);
  }

  @Test
  void writeFailurePreservesOriginalAndDeletesTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.failWrite = true;

    assertThrows(
        IOException.class, () -> AtomicFileWrites.replace(target, "new".getBytes(), files));

    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void moveFailurePreservesOriginalAndDeletesTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.failAtomicMove = true;

    assertThrows(
        IOException.class, () -> AtomicFileWrites.replace(target, "new".getBytes(), files));

    assertEquals(1, files.atomicMoveAttempts, "generic move IO failures must not retry");
    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void retriesAccessDeniedOnTheSamePreparedTempBeforePublishing() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.accessDeniedFailuresRemaining = 2;
    files.accessDeniedFailure = new AccessDeniedException(target.toString());

    AtomicFileWrites.replace(target, "new".getBytes(), files);

    assertEquals("new", Files.readString(target));
    assertEquals(1, files.writeCalls, "the completed temp bytes should be written once");
    assertEquals(3, files.atomicMoveAttempts);
    assertEquals(1, files.atomicSources.stream().distinct().count());
    assertTrue(files.atomicSources.stream().allMatch(files.createdTemp::equals));
    assertEquals(java.util.List.of(true, true, true), files.tempExistedAtMove);
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void exhaustedAccessDeniedPreservesOriginalFailureAndCleansTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    AccessDeniedException original = new AccessDeniedException(target.toString());
    files.alwaysAccessDenied = true;
    files.accessDeniedFailure = original;
    long started = System.nanoTime();

    AccessDeniedException failure = assertThrows(
        AccessDeniedException.class,
        () -> AtomicFileWrites.replace(target, "new".getBytes(), files));

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertSame(original, failure);
    assertTrue(elapsedMillis >= 1_000, "permanent denial should use the bounded retry window");
    assertTrue(elapsedMillis < 5_000, "permanent denial must remain bounded");
    assertTrue(files.atomicMoveAttempts > 1);
    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void interruptedAccessDeniedRetryRestoresFlagAndCleansTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.alwaysAccessDenied = true;
    files.accessDeniedFailure = new AccessDeniedException(target.toString());
    files.firstAccessDenied = new CountDownLatch(1);
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    AtomicBoolean interruptRetained = new AtomicBoolean();
    Thread writer = new Thread(() -> {
      try {
        AtomicFileWrites.replace(target, "new".getBytes(), files);
        outcome.set(new AssertionError("replacement unexpectedly succeeded"));
      } catch (Throwable failure) {
        outcome.set(failure);
        interruptRetained.set(Thread.currentThread().isInterrupted());
      }
    }, "atomic-file-write-interruption-test");

    writer.start();
    try {
      assertTrue(files.firstAccessDenied.await(2, TimeUnit.SECONDS));
      assertTrue(
          awaitState(writer, Thread.State.TIMED_WAITING, Duration.ofSeconds(2)),
          "writer should be in the retry wait before interruption");
      writer.interrupt();
      writer.join(2_000);
    } finally {
      if (writer.isAlive()) {
        writer.interrupt();
        writer.join(2_000);
      }
    }

    assertFalse(writer.isAlive(), "interrupted replacement should stop promptly");
    assertInstanceOf(InterruptedIOException.class, outcome.get());
    assertTrue(interruptRetained.get(), "the thrown cancellation must preserve the flag");
    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void strictReplacementForcesBytesBeforeMove() throws Exception {
    Path target = tempDir.resolve("strict.json");
    RecordingFileAccess files = new RecordingFileAccess();
    AtomicFileWrites.replaceStrict(target, new byte[] {7}, files);
    assertEquals(java.util.List.of("force", "move"), files.events);
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
    AtomicFileWrites.replaceStrict(target, new byte[] {8});
    assertArrayEquals(new byte[] {8}, Files.readAllBytes(target));
  }

  @Test
  void strictReplacementRefusesFallbackAndPreservesTarget() throws Exception {
    Path target = tempDir.resolve("strict.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.atomicUnsupported = true;
    assertThrows(AtomicMoveNotSupportedException.class,
        () -> AtomicFileWrites.replaceStrict(target, new byte[] {7}, files));
    assertEquals(0, files.fallbackMoves);
    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void forceFailureDoesNotAttemptMove() throws Exception {
    Path target = tempDir.resolve("strict.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.failForce = true;
    assertThrows(IOException.class,
        () -> AtomicFileWrites.replaceStrict(target, new byte[] {7}, files));
    assertEquals(java.util.List.of("force"), files.events);
    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void cleanupFailureDoesNotHideReplacementFailure() throws Exception {
    RecordingFileAccess files = new RecordingFileAccess();
    files.failAtomicMove = true;
    files.failCleanup = true;
    IOException failure = assertThrows(IOException.class,
        () -> AtomicFileWrites.replaceStrict(tempDir.resolve("strict.json"), new byte[] {7}, files));
    assertEquals("injected move failure", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("injected cleanup failure", failure.getSuppressed()[0].getMessage());
    assertTrue(Files.exists(files.createdTemp));
  }

  private static final class RecordingFileAccess implements AtomicFileWrites.FileAccess {
    private final java.util.List<String> events = new java.util.ArrayList<>();
    private final java.util.List<Path> atomicSources = new java.util.ArrayList<>();
    private final java.util.List<Boolean> tempExistedAtMove = new java.util.ArrayList<>();
    private boolean failForce;
    private boolean failCleanup;
    private Path createdTemp;
    private boolean failWrite;
    private boolean failAtomicMove;
    private boolean atomicUnsupported;
    private boolean alwaysAccessDenied;
    private int accessDeniedFailuresRemaining;
    private AccessDeniedException accessDeniedFailure;
    private CountDownLatch firstAccessDenied;
    private int writeCalls;
    private int atomicMoveAttempts;
    private int fallbackMoves;

    @Override
    public void createDirectories(Path directory) throws IOException {
      Files.createDirectories(directory);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
      createdTemp = Files.createTempFile(directory, prefix, suffix);
      return createdTemp;
    }

    @Override
    public void write(Path path, byte[] content) throws IOException {
      writeCalls += 1;
      if (failWrite) throw new IOException("injected write failure");
      Files.write(path, content);
    }

    @Override
    public void writeForced(Path path, byte[] content) throws IOException {
      events.add("force");
      if (failForce) throw new IOException("injected force failure");
      write(path, content);
    }

    @Override
    public void moveAtomicReplace(Path source, Path target) throws IOException {
      events.add("move");
      atomicMoveAttempts += 1;
      atomicSources.add(source);
      tempExistedAtMove.add(Files.exists(source));
      if (atomicUnsupported) {
        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
      }
      if (failAtomicMove) throw new IOException("injected move failure");
      if (alwaysAccessDenied || accessDeniedFailuresRemaining > 0) {
        if (accessDeniedFailuresRemaining > 0) accessDeniedFailuresRemaining -= 1;
        if (firstAccessDenied != null) firstAccessDenied.countDown();
        if (accessDeniedFailure == null) {
          accessDeniedFailure = new AccessDeniedException(source.toString(), target.toString(), "injected");
        }
        throw accessDeniedFailure;
      }
      Files.move(
          source,
          target,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void moveReplace(Path source, Path target) throws IOException {
      fallbackMoves += 1;
      Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
      if (failCleanup) throw new IOException("injected cleanup failure");
      Files.deleteIfExists(path);
    }
  }

  private static boolean awaitState(Thread thread, Thread.State state, Duration budget)
      throws InterruptedException {
    long deadline = System.nanoTime() + budget.toNanos();
    while (System.nanoTime() < deadline) {
      if (thread.getState() == state) return true;
      if (thread.getState() == Thread.State.TERMINATED) return false;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    return thread.getState() == state;
  }
}
