/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWritesCreateOnceTest {
  @TempDir Path dir;

  @Test
  void concurrentDifferingCandidatesCannotOverwriteTheFirstPublishedSnapshot() throws Exception {
    Path backup = dir.resolve("settings.v2.bak.json");
    byte[] firstBytes = "first legacy settings".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] secondBytes = "different legacy settings".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var prepared = new CountDownLatch(2);
    var firstPublished = new CountDownLatch(1);
    // Both writers prepare complete bytes before either publishes. The second publisher is
    // released only after the first has installed its snapshot: deterministic former-overwrite race.
    try (var writers = Executors.newFixedThreadPool(2)) {
      var first = writers.submit(() -> AtomicFileWrites.createOnceStrict(backup, firstBytes,
          new OrderedFiles(true, prepared, firstPublished)));
      var second = writers.submit(() -> AtomicFileWrites.createOnceStrict(backup, secondBytes,
          new OrderedFiles(false, prepared, firstPublished)));
      boolean firstCreated = first.get(10, TimeUnit.SECONDS);
      boolean secondCreated = second.get(10, TimeUnit.SECONDS);
      assertArrayEquals(firstBytes, Files.readAllBytes(backup), "first published snapshot must survive");
      assertTrue(firstCreated);
      assertFalse(secondCreated);
    }
    try (var files = Files.list(dir)) { assertEquals(1, files.count(), "both temps must be removed"); }
  }

  @Test
  void existingSnapshotIsRetainedAndTemporaryBytesAreRemoved() throws Exception {
    Path backup = dir.resolve("settings.v2.bak.json");
    assertTrue(AtomicFileWrites.createOnceStrict(backup, new byte[] {1}));
    assertFalse(AtomicFileWrites.createOnceStrict(backup, new byte[] {2}));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(backup));
    try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
  }

  @Test
  void unsupportedOperationFallsBackToCreateNew() throws Exception {
    Path backup = dir.resolve("settings.v2.bak.json");
    var files = new OrderedFiles(true, new CountDownLatch(1), new CountDownLatch(1)) {
      @Override public void createLink(Path target, Path existing) {
        throw new UnsupportedOperationException("no hard links");
      }
    };
    assertTrue(AtomicFileWrites.createOnceStrict(backup, new byte[] {1}, files));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(backup));
    assertFalse(AtomicFileWrites.createOnceStrict(backup, new byte[] {2}, files));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(backup));
    try (var remaining = Files.list(dir)) { assertEquals(1, remaining.count()); }
  }

  @Test
  void fileSystemLinkFailureFallsBackToForcedCreateNewWithoutOverwriting() throws Exception {
    Path backup = dir.resolve("settings.v2.bak.json");
    byte[] bytes = "original legacy bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var fallbackWrites = new java.util.concurrent.atomic.AtomicInteger();
    var files = new OrderedFiles(true, new CountDownLatch(1), new CountDownLatch(1)) {
      @Override public void createLink(Path target, Path existing) throws IOException {
        throw new java.nio.file.FileSystemException(target.toString(), existing.toString(), "Incorrect function");
      }
      @Override public void writeNewForced(Path path, byte[] content) throws IOException {
        fallbackWrites.incrementAndGet();
        super.writeNewForced(path, content);
      }
    };
    assertTrue(AtomicFileWrites.createOnceStrict(backup, bytes, files));
    assertArrayEquals(bytes, Files.readAllBytes(backup));
    assertFalse(AtomicFileWrites.createOnceStrict(backup, new byte[] {2}, files));
    assertArrayEquals(bytes, Files.readAllBytes(backup));
    assertEquals(2, fallbackWrites.get());
    try (var remaining = Files.list(dir)) { assertEquals(1, remaining.count()); }
  }

  private static class OrderedFiles implements AtomicFileWrites.FileAccess {
    private final boolean first;
    private final CountDownLatch prepared;
    private final CountDownLatch firstPublished;
    OrderedFiles(boolean first, CountDownLatch prepared, CountDownLatch firstPublished) {
      this.first = first; this.prepared = prepared; this.firstPublished = firstPublished;
    }
    private static void await(CountDownLatch latch) throws IOException {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test publication ordering timed out");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException(failure);
      }
    }
    @Override public void createDirectories(Path path) throws IOException { Files.createDirectories(path); }
    @Override public Path createTempFile(Path path, String prefix, String suffix) throws IOException {
      return Files.createTempFile(path, prefix, suffix);
    }
    @Override public void write(Path path, byte[] content) throws IOException { Files.write(path, content); }
    @Override public void writeForced(Path path, byte[] content) throws IOException {
      try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        var bytes = ByteBuffer.wrap(content);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      prepared.countDown();
      await(prepared);
    }
    @Override public void writeNewForced(Path path, byte[] content) throws IOException {
      try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var bytes = ByteBuffer.wrap(content);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
    }
    @Override public void createLink(Path target, Path existing) throws IOException {
      if (!first) await(firstPublished);
      Files.createLink(target, existing);
      if (first) firstPublished.countDown();
    }
    @Override public void moveAtomicReplace(Path source, Path target) throws IOException {
      if (!first) await(firstPublished);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      if (first) firstPublished.countDown();
    }
    @Override public void moveReplace(Path source, Path target) throws IOException {
      throw new AssertionError("create-once must not fall back to replacement");
    }
    @Override public void deleteIfExists(Path path) throws IOException { Files.deleteIfExists(path); }
  }
}
