/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("cross-platform")
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
  @EnabledOnOs(OS.LINUX)
  void reopensAfterContentionWhenWriterRotatesThePathBeforePublishingReplacement()
      throws Exception {
    Path target = tempDir.resolve("state.json");
    Path previous = tempDir.resolve("state.json.prev");
    byte[] next = "new-authoritative-state".getBytes();
    Files.writeString(target, "old-authoritative-state");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch readStarted = new CountDownLatch(1);
    AtomicReference<Thread> readerThread = new AtomicReference<>();
    try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      FileLock oldStateLock = channel.lock();
      try {
        Future<byte[]> reader = executor.submit(() -> {
          readerThread.set(Thread.currentThread());
          readStarted.countDown();
          return ContendedFileReads.readAllBytes(target, Duration.ofSeconds(2));
        });
        assertTrue(readStarted.await(1, TimeUnit.SECONDS));
        assertTrue(awaitState(readerThread.get(), Thread.State.TIMED_WAITING, Duration.ofSeconds(1)),
            "reader must be in the lock retry wait");
        assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS),
            "reader must first encounter the old state's lock");

        Files.move(target, previous);
        oldStateLock.release();
        // Keep the path absent longer than the read retry interval to exercise the exact gap.
        Thread.sleep(100);
        Files.write(target, next);
        assertArrayEquals(next, reader.get(2, TimeUnit.SECONDS));
      } finally {
        if (oldStateLock.isValid()) oldStateLock.release();
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
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

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void permissionDenialRetriesUntilBudgetIsSpent() throws Exception {
    Path target = tempDir.resolve("unreadable.json");
    Files.writeString(target, "private");
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
    try {
      Files.setPosixFilePermissions(target, Set.of());
      boolean canRead;
      try (FileChannel probe = FileChannel.open(target, StandardOpenOption.READ)) {
        canRead = probe.isOpen();
      } catch (AccessDeniedException expected) {
        canRead = false;
      }
      assumeTrue(!canRead, "process bypasses file permissions (for example root); denial fixture unavailable");
      AtomicReference<AccessDeniedException> lastDenial = new AtomicReference<>();
      ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
        try {
          return FileChannel.open(target, StandardOpenOption.READ);
        } catch (AccessDeniedException denied) {
          lastDenial.set(denied);
          throw denied;
        }
      });
      long started = System.nanoTime();
      AccessDeniedException failure = assertThrows(AccessDeniedException.class,
          () -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET));
      assertEquals(target.toString(), failure.getFile());
      long elapsed = System.nanoTime() - started;
      assertSame(lastDenial.get(), failure, "the original last denial must be retained");
      assertTrue(elapsed >= SHORT_BUDGET.toNanos(), "the read must spend the retry budget");
      assertTrue(opens.attempts.get() >= 2, "persistent denial must retry");
    } finally {
      Files.setPosixFilePermissions(target, permissions);
    }
  }

  @Test
  void denialThenMissingReopensAndReadsOneLockedChannel() throws Exception {
    Path target = tempDir.resolve("scripted-replacement.json");
    byte[] expected = "whole-replacement".getBytes();
    Files.write(target, expected);
    ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
      if (attempt == 1) throw new AccessDeniedException(target.toString());
      if (attempt == 2) throw new NoSuchFileException(target.toString());
      return new CheckedChannel(FileChannel.open(target, StandardOpenOption.READ), "", null);
    });
    assertArrayEquals(expected,
        assertDoesNotThrow(() -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET)));
    assertEquals(3, opens.attempts.get());
  }

  @Test
  void mixedRetriesReportTheLastFailedAttempt() throws Exception {
    Path target = tempDir.resolve("mixed-retries.json");
    Files.writeString(target, "held");
    IOException cause = new IOException("original denial cause");
    AccessDeniedException denial = new AccessDeniedException(target.toString());
    denial.initCause(cause);
    NoSuchFileException missing = new NoSuchFileException(target.toString());
    // Both orders must continue retrying, and the final attempt alone selects the timeout failure.
    for (String sequence : new String[] {"denial-lock", "lock-denial", "denial-missing"}) {
      ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
        boolean deny = sequence.equals("denial-lock") ? attempt == 1 : attempt > 1;
        if (sequence.equals("denial-missing")) {
          if (attempt == 1) throw denial;
          throw missing;
        }
        if (deny) throw denial;
        return new CheckedChannel(FileChannel.open(target, StandardOpenOption.READ), "unavailable", null);
      });
      long started = System.nanoTime();
      IOException failure = assertThrows(IOException.class,
          () -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET), sequence);
      if (sequence.equals("denial-lock")) {
        assertInstanceOf(ContendedFileReads.FileReadContendedException.class, failure);
      } else {
        assertSame(sequence.equals("lock-denial") ? denial : missing, failure);
      }
      assertTrue(opens.attempts.get() >= 3, sequence + " must continue after the transition");
      assertRetryElapsed(started);
    }
    assertSame(cause, denial.getCause());
  }

  @Test
  void denialAndLockContentionShareOneBudget() throws Exception {
    Path target = tempDir.resolve("shared-budget.json");
    Files.writeString(target, "held");
    AccessDeniedException denial = new AccessDeniedException(target.toString());
    ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
      if (attempt == 1) {
        // A filesystem call may block; retrying another kind of failure must not restart the clock.
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(600));
        throw denial;
      }
      return new CheckedChannel(FileChannel.open(target, StandardOpenOption.READ), "unavailable", null);
    });
    long started = System.nanoTime();
    assertThrows(ContendedFileReads.FileReadContendedException.class,
        () -> ContendedFileReads.readAllBytes(opens.path(), Duration.ofMillis(800)));
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 750, "the shared budget must be consumed");
    assertTrue(elapsed < 1_100, "lock contention must not receive a fresh budget after denial");
    assertTrue(opens.attempts.get() >= 2);
  }

  @Test
  void interruptedDenialRetainsTheReadersInterruptFlag() throws Exception {
    Path target = tempDir.resolve("interrupted-denial.json");
    CountDownLatch retried = new CountDownLatch(2);
    ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
      retried.countDown();
      throw new AccessDeniedException(target.toString());
    });
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    AtomicBoolean retained = new AtomicBoolean();
    Thread reader = new Thread(() -> {
      try {
        ContendedFileReads.readAllBytes(opens.path(), Duration.ofSeconds(10));
        outcome.set(new AssertionError("denial unexpectedly succeeded"));
      } catch (Throwable failure) {
        outcome.set(failure);
        retained.set(Thread.currentThread().isInterrupted());
      }
    }, "denied-file-reader-interruption-test");
    try {
      reader.start();
      assertTrue(retried.await(1, TimeUnit.SECONDS));
      assertTrue(awaitState(reader, Thread.State.TIMED_WAITING, Duration.ofSeconds(1)));
      reader.interrupt();
      reader.join(2_000);
      assertFalse(reader.isAlive());
      assertInstanceOf(InterruptedIOException.class, outcome.get());
      assertTrue(retained.get());
    } finally {
      reader.interrupt();
      reader.join(2_000);
    }
  }

  @Test
  void disappearanceAfterLockContentionTimesOutAsMissing() throws Exception {
    Path target = tempDir.resolve("disappeared.json");
    Files.writeString(target, "held");
    NoSuchFileException missing = new NoSuchFileException(target.toString());
    ScriptedOpens opens = new ScriptedOpens(target, attempt -> {
      if (attempt > 1) throw missing;
      return new CheckedChannel(FileChannel.open(target, StandardOpenOption.READ), "unavailable", null);
    });
    long started = System.nanoTime();
    assertSame(missing, assertThrows(NoSuchFileException.class,
        () -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET)));
    assertTrue(opens.attempts.get() >= 3);
    assertRetryElapsed(started);
  }

  @Test
  void otherOpenFailuresPropagateWithoutRetry() {
    Path target = tempDir.resolve("other-open-failure.json");
    FileSystemException failure = new FileSystemException(target.toString(), null, "not a sharing denial");
    ScriptedOpens opens = new ScriptedOpens(target, attempt -> { throw failure; });
    assertSame(failure, assertThrows(FileSystemException.class,
        () -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET)));
    assertEquals(1, opens.attempts.get());
  }

  @Test
  void channelLockReadAndCloseFailuresPropagateWithoutRetry() throws Exception {
    Path target = tempDir.resolve("channel-failure.json");
    Files.writeString(target, "content");
    for (String stage : new String[] {"lock", "read", "close"}) {
      for (IOException failure : new IOException[] {
          new AccessDeniedException(target.toString()), new NoSuchFileException(target.toString()),
          new FileSystemException(target.toString())}) {
        ScriptedOpens opens = new ScriptedOpens(target,
            attempt -> new CheckedChannel(FileChannel.open(target, StandardOpenOption.READ), stage, failure));
        assertSame(failure, assertThrows(IOException.class,
            () -> ContendedFileReads.readAllBytes(opens.path(), SHORT_BUDGET)), stage);
        assertEquals(1, opens.attempts.get(), stage + " failure must not retry");
      }
    }
  }

  private static void assertRetryElapsed(long started) {
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 200, "the read must use the retry budget");
    assertTrue(elapsed < 2_000, "the short budget must bound retries");
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

  @FunctionalInterface
  private interface ChannelOpener {
    FileChannel open(int attempt) throws IOException;
  }

  /** Routes the existing Path API through observable opens; no production test hook is needed. */
  private static final class ScriptedOpens extends FileSystemProvider {
    private final Path target;
    private final ChannelOpener opener;
    private final AtomicInteger attempts = new AtomicInteger();
    private final FileSystem fileSystem = new FileSystem() {
      @Override public FileSystemProvider provider() { return ScriptedOpens.this; }
      @Override public void close() { throw new UnsupportedOperationException(); }
      @Override public boolean isOpen() { return true; }
      @Override public boolean isReadOnly() { return true; }
      @Override public String getSeparator() { return FileSystems.getDefault().getSeparator(); }
      @Override public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }
      @Override public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }
      @Override public Set<String> supportedFileAttributeViews() { return Set.of(); }
      @Override public Path getPath(String first, String... more) { throw new UnsupportedOperationException(); }
      @Override public PathMatcher getPathMatcher(String syntaxAndPattern) { throw new UnsupportedOperationException(); }
      @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
      @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
    };

    private ScriptedOpens(Path target, ChannelOpener opener) {
      this.target = target;
      this.opener = opener;
    }

    Path path() {
      return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "getFileSystem" -> fileSystem;
            case "toString" -> target.toString();
            default -> throw new UnsupportedOperationException(method.getName());
          });
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      assertEquals(Set.of(StandardOpenOption.READ), options);
      return opener.open(attempts.incrementAndGet());
    }

    @Override public String getScheme() { return "scripted-read"; }
    @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
    @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
    @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
    @Override public java.nio.channels.SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
    @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) { throw new UnsupportedOperationException(); }
    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
    @Override public void delete(Path path) { throw new UnsupportedOperationException(); }
    @Override public void copy(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }
    @Override public void move(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }
    @Override public boolean isSameFile(Path path, Path other) { throw new UnsupportedOperationException(); }
    @Override public boolean isHidden(Path path) { throw new UnsupportedOperationException(); }
    @Override public FileStore getFileStore(Path path) { throw new UnsupportedOperationException(); }
    @Override public void checkAccess(Path path, AccessMode... modes) { throw new UnsupportedOperationException(); }
    @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { throw new UnsupportedOperationException(); }
    @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) { throw new UnsupportedOperationException(); }
    @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) { throw new UnsupportedOperationException(); }
    @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) { throw new UnsupportedOperationException(); }
  }

  /** A real read channel whose lock, read or close boundary can independently fail. */
  private static final class CheckedChannel extends FileChannel {
    private final FileChannel delegate;
    private final String stage;
    private final IOException failure;
    private FileLock held;

    private CheckedChannel(FileChannel delegate, String stage, IOException failure) {
      this.delegate = delegate;
      this.stage = stage;
      this.failure = failure;
    }

    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
      assertTrue(shared, "read must acquire a shared lock");
      assertEquals(0, position);
      assertEquals(Long.MAX_VALUE, size);
      if (stage.equals("lock")) throw failure;
      if (stage.equals("unavailable")) return null;
      held = delegate.tryLock(position, size, shared);
      return held;
    }

    @Override public int read(ByteBuffer dst) throws IOException {
      assertTrue(held != null && held.isValid(), "read must use the channel protected by the shared lock");
      if (stage.equals("read")) throw failure;
      return delegate.read(dst);
    }

    @Override protected void implCloseChannel() throws IOException {
      delegate.close();
      if (stage.equals("close")) throw failure;
    }

    @Override public long position() throws IOException { return delegate.position(); }
    @Override public long size() throws IOException { return delegate.size(); }
    @Override public FileChannel position(long position) { throw new UnsupportedOperationException(); }
    @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
    @Override public int read(ByteBuffer dst, long position) { throw new UnsupportedOperationException(); }
    @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
    @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
    @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
    @Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
    @Override public void force(boolean metaData) { throw new UnsupportedOperationException(); }
    @Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
    @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
    @Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
  }

  private static final class RecordingFailureFiles implements AtomicFileWrites.FileAccess {
    @Override
    public void writeNewForced(Path path, byte[] content) throws IOException {
      Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void createLink(Path target, Path existing) throws IOException {
      Files.createLink(target, existing);
    }

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
