/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContendedFileReadsTest {
  private static final Duration SHORT_BUDGET = Duration.ofMillis(250);

  @TempDir Path tempDir;

  @Test
  void waitsForAnExternalExclusiveLockThenReadsTheExactBytes() throws Exception {
    Path target = tempDir.resolve("locked-state.json");
    byte[] expected = "{\"generation\":17,\"complete\":true}".getBytes();
    Files.write(target, expected);

    try (LockHolder holder = LockHolder.start(target, false, tempDir)) {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<byte[]> read = executor.submit(
            () -> ContendedFileReads.readAllBytes(target, Duration.ofSeconds(3)));
        assertThrows(TimeoutException.class, () -> read.get(150, TimeUnit.MILLISECONDS));

        holder.release();
        assertArrayEquals(expected, read.get(3, TimeUnit.SECONDS));
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void readsWhileAnExternalSharedLockIsHeld() throws Exception {
    Path target = tempDir.resolve("shared-state.json");
    byte[] expected = "shared-owner-bytes".getBytes();
    Files.write(target, expected);

    try (LockHolder holder = LockHolder.start(target, true, tempDir)) {
      assertArrayEquals(expected, ContendedFileReads.readAllBytes(target, SHORT_BUDGET));
      assertTrue(holder.isAlive(), "the child keeps its shared lock until released");
      holder.release();
    }
  }

  @Test
  void overlappingLockInThisJvmHasABoundedContentionFailure() throws Exception {
    Path target = tempDir.resolve("overlap-state.json");
    Files.writeString(target, "held");

    try (FileChannel channel = FileChannel.open(
        target, StandardOpenOption.READ, StandardOpenOption.WRITE);
        var lock = channel.lock(0, Long.MAX_VALUE, false)) {
      assertTrue(lock.isValid());
      long started = System.nanoTime();
      assertThrows(
          ContendedFileReads.FileReadContendedException.class,
          () -> ContendedFileReads.readAllBytes(target, SHORT_BUDGET));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(elapsedMillis >= 100, "the read should retry rather than fail on first overlap");
      assertTrue(elapsedMillis < 2_000, "the explicit lock budget must bound the retry");
    }
  }

  @Test
  void interruptedContentionRestoresTheReadersInterruptFlag() throws Exception {
    Path target = tempDir.resolve("interrupt-state.json");
    Files.writeString(target, "held");
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    AtomicBoolean interruptRetained = new AtomicBoolean();
    Thread reader = new Thread(() -> {
      started.countDown();
      try {
        ContendedFileReads.readAllBytes(target, Duration.ofSeconds(10));
        outcome.set(new AssertionError("read unexpectedly acquired the held lock"));
      } catch (Throwable failure) {
        outcome.set(failure);
        interruptRetained.set(Thread.currentThread().isInterrupted());
      }
    }, "contended-file-reader-interruption-test");

    try (FileChannel channel = FileChannel.open(
        target, StandardOpenOption.READ, StandardOpenOption.WRITE);
        var lock = channel.lock(0, Long.MAX_VALUE, false)) {
      assertTrue(lock.isValid());
      reader.start();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertTrue(
          awaitState(reader, Thread.State.TIMED_WAITING, Duration.ofSeconds(2)),
          "reader should be in the bounded retry wait before interruption");
      reader.interrupt();
      reader.join(2_000);
      assertFalse(reader.isAlive(), "interrupted reader should stop promptly");
    } finally {
      if (reader.isAlive()) {
        reader.interrupt();
        reader.join(2_000);
      }
    }

    assertInstanceOf(InterruptedIOException.class, outcome.get());
    assertTrue(interruptRetained.get(), "the thrown cancellation must preserve the flag");
  }

  @Test
  void missingFilePropagatesImmediatelyInsteadOfBecomingLockContention() {
    Path missing = tempDir.resolve("not-created.json");
    long started = System.nanoTime();

    assertThrows(
        NoSuchFileException.class,
        () -> ContendedFileReads.readAllBytes(missing, Duration.ofMillis(800)));

    assertTrue(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
        "a missing path must not consume the lock retry budget");
  }

  @Test
  void concurrentAtomicReplacementsExposeOnlyWholeVersionsAndPropagateWriterFailure()
      throws Exception {
    Path target = tempDir.resolve("replaced-state.bin");
    byte[] oldBytes = repeated((byte) 0x35, 256 * 1024);
    byte[] newBytes = repeated((byte) 0x6a, 256 * 1024);
    Files.write(target, oldBytes);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch writerDone = new CountDownLatch(1);
    AtomicInteger readsDuringWrites = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> writer = executor.submit(() -> {
        await(start);
        try {
          for (int i = 0; i < 80; i++) {
            AtomicFileWrites.replace(target, (i & 1) == 0 ? newBytes : oldBytes);
          }
        } catch (IOException failure) {
          throw new IllegalStateException("atomic writer failed", failure);
        } finally {
          writerDone.countDown();
        }
      });
      Future<?> reader = executor.submit(() -> {
        await(start);
        try {
          while (writerDone.getCount() != 0) {
            assertWholeVersion(ContendedFileReads.readAllBytes(target), oldBytes, newBytes);
            readsDuringWrites.incrementAndGet();
          }
          assertWholeVersion(ContendedFileReads.readAllBytes(target), oldBytes, newBytes);
        } catch (IOException failure) {
          throw new IllegalStateException("reader failed during atomic replacement", failure);
        }
      });

      start.countDown();
      writer.get(20, TimeUnit.SECONDS);
      reader.get(20, TimeUnit.SECONDS);
      assertTrue(readsDuringWrites.get() > 0, "reader must overlap the replacement loop");
      assertDirectoryContainsOnlyTarget(target);

      CountDownLatch failureWriterEntered = new CountDownLatch(1);
      CountDownLatch allowFailure = new CountDownLatch(1);
      byte[] beforeFailure = ContendedFileReads.readAllBytes(target, Duration.ofSeconds(2));
      RecordingFailureFiles files = new RecordingFailureFiles(failureWriterEntered, allowFailure);
      Future<?> failingWriter = executor.submit(() -> {
        try {
          AtomicFileWrites.replace(target, newBytes, files);
          throw new AssertionError("injected writer failure was ignored");
        } catch (IOException expected) {
          if (!"injected write failure".equals(expected.getMessage())) throw new RuntimeException(expected);
        }
      });

      try {
        assertTrue(failureWriterEntered.await(2, TimeUnit.SECONDS));
        byte[] beforeFailedWrite = ContendedFileReads.readAllBytes(target, Duration.ofSeconds(2));
        assertArrayEquals(beforeFailure, beforeFailedWrite);
      } finally {
        allowFailure.countDown();
      }
      failingWriter.get(3, TimeUnit.SECONDS);
      assertArrayEquals(beforeFailure, ContendedFileReads.readAllBytes(target));
      assertFalse(Files.exists(files.createdTemp));
      assertDirectoryContainsOnlyTarget(target);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static void assertWholeVersion(byte[] observed, byte[] oldBytes, byte[] newBytes) {
    assertTrue(
        Arrays.equals(oldBytes, observed) || Arrays.equals(newBytes, observed),
        "read returned bytes that were neither the complete old nor complete new file");
  }

  private void assertDirectoryContainsOnlyTarget(Path target) throws IOException {
    try (var paths = Files.list(tempDir)) {
      assertEquals(java.util.List.of(target), paths.toList());
    }
  }

  private static byte[] repeated(byte value, int count) {
    byte[] result = new byte[count];
    Arrays.fill(result, value);
    return result;
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

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("worker was interrupted", interrupted);
    }
  }

  private static final class RecordingFailureFiles implements AtomicFileWrites.FileAccess {
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private Path createdTemp;

    private RecordingFailureFiles(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

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
      entered.countDown();
      try {
        if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test release timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("test writer interrupted", interrupted);
      }
      throw new IOException("injected write failure");
    }

    @Override
    public void writeForced(Path path, byte[] content) throws IOException {
      write(path, content);
    }

    @Override
    public void moveAtomicReplace(Path source, Path destination) throws IOException {
      Files.move(
          source,
          destination,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void moveReplace(Path source, Path destination) throws IOException {
      Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }

  private static final class LockHolder implements AutoCloseable {
    private static final Duration START_BUDGET = Duration.ofSeconds(5);

    private final Process process;
    private final Path release;
    private final Path output;
    private boolean released;

    private LockHolder(Process process, Path release, Path output) {
      this.process = process;
      this.release = release;
      this.output = output;
    }

    static LockHolder start(Path target, boolean shared, Path directory) throws Exception {
      Path ready = directory.resolve("holder-" + System.nanoTime() + ".ready");
      Path release = directory.resolve("holder-" + System.nanoTime() + ".release");
      Path output = directory.resolve("holder-" + System.nanoTime() + ".log");
      URI classes = ContendedFileReadsTest.class.getProtectionDomain()
          .getCodeSource()
          .getLocation()
          .toURI();
      Path java = Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
      Process process = new ProcessBuilder(
          java.toString(), "-cp", Path.of(classes).toString(), FileLockHolder.class.getName(),
          target.toString(), shared ? "shared" : "exclusive", ready.toString(), release.toString())
          .redirectErrorStream(true)
          .redirectOutput(output.toFile())
          .start();
      LockHolder holder = new LockHolder(process, release, output);
      try {
        holder.awaitReady(ready);
        return holder;
      } catch (IOException | InterruptedException | RuntimeException | Error failure) {
        holder.stopOwnedProcess();
        throw failure;
      }
    }

    boolean isAlive() {
      return process.isAlive();
    }

    void release() throws Exception {
      if (released) return;
      Files.writeString(release, "release");
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        stopOwnedProcess();
        throw new AssertionError("lock-holder process did not stop after release: " + logContents());
      }
      released = true;
      if (process.exitValue() != 0) {
        throw new AssertionError("lock-holder process failed: " + logContents());
      }
    }

    @Override
    public void close() throws Exception {
      release();
    }

    private void awaitReady(Path ready) throws IOException, InterruptedException {
      long deadline = System.nanoTime() + START_BUDGET.toNanos();
      while (System.nanoTime() < deadline) {
        if (Files.exists(ready)) return;
        if (!process.isAlive()) {
          throw new AssertionError("lock-holder exited before acquiring its lock: " + logContents());
        }
        Thread.sleep(10);
      }
      throw new AssertionError("lock-holder did not publish its ready marker: " + logContents());
    }

    private void stopOwnedProcess() {
      try {
        Files.writeString(release, "release");
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroy();
          if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
          }
        }
      } catch (IOException failure) {
        process.destroy();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        process.destroy();
      }
    }

    private String logContents() {
      try {
        return Files.exists(output) ? Files.readString(output) : "<no child output>";
      } catch (IOException failure) {
        return "<unable to read child output: " + failure + ">";
      }
    }
  }
}
