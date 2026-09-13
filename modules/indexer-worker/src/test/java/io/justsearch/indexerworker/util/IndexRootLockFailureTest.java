/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexRootLockFailureTest {
  @TempDir Path directory;

  @Test
  void failedChannelCloseRetainsExclusionAndCanRetryWhileOpen() throws Exception {
    var owner = new IndexRootLock(directory.resolve("index"));
    owner.acquire();
    var field = IndexRootLock.class.getDeclaredField("channel");
    field.setAccessible(true);
    FileChannel real = (FileChannel) field.get(owner);
    FileChannel intercepted = mock(FileChannel.class, org.mockito.AdditionalAnswers.delegatesTo(real));
    IOException failure = new IOException("channel close unavailable");
    doThrow(failure).when(intercepted).close();
    field.set(owner, intercepted);
    try {
      assertSame(failure, assertThrows(UncheckedIOException.class, owner::close).getCause());
      assertThrows(IOException.class, owner::acquire, "a failed close is not a healthy held owner");
      try (var contender = new IndexRootLock(directory.resolve("index"))) {
        assertThrows(IOException.class, contender::acquire);
      }
      assertEquals(23, probe(), "failed close must retain native cross-process exclusion");
      
    } finally {
      field.set(owner, real);
      owner.close();
    }
    try (var next = new IndexRootLock(directory.resolve("index"))) { next.acquire(); }
  }

  @Test
  void closeFailureAfterNativeCloseCannotBeClearedByANoopRetry() throws Exception {
    var owner = new IndexRootLock(directory.resolve("index"));
    owner.acquire();
    var field = IndexRootLock.class.getDeclaredField("channel");
    field.setAccessible(true);
    FileChannel real = (FileChannel) field.get(owner);
    FileChannel intercepted = mock(FileChannel.class, org.mockito.AdditionalAnswers.delegatesTo(real));
    IOException failure = new IOException("native close outcome unavailable");
    doAnswer(call -> { real.close(); throw failure; }).when(intercepted).close();
    field.set(owner, intercepted);
    try {
      assertSame(failure, assertThrows(UncheckedIOException.class, owner::close).getCause());
      assertSame(failure, assertThrows(UncheckedIOException.class, owner::close).getCause());
      assertThrows(IOException.class, owner::acquire);
      try (var contender = new IndexRootLock(directory.resolve("index"))) { assertThrows(IOException.class, contender::acquire); }
      
    } finally {
      real.close();
      // This test deliberately models process-lifetime refusal. Only remove this fixture's
      // reservation after its actual channel has closed; production requires process exit.
      var heldField = IndexRootLock.class.getDeclaredField("HELD_IN_JVM");
      heldField.setAccessible(true);
      Object held = heldField.get(null);
      synchronized (held) {
        ((java.util.Map<?, ?>) held).remove(directory.resolve("index.index.lock").toRealPath());
      }
    }
  }

  @Test
  void failedAcquisitionRetainsReservationWhenChannelCleanupFails() throws Exception {
    try (FileChannel real = FileChannel.open(directory.resolve("index.index.lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      var intercepted = mock(FileChannel.class, org.mockito.AdditionalAnswers.delegatesTo(real));
      var acquisition = new IOException("native acquisition unavailable");
      var cleanup = new IOException("failed acquisition channel did not close");
      doThrow(acquisition).when(intercepted).tryLock();
      doThrow(cleanup).when(intercepted).close();
      var owner = new IndexRootLock(directory.resolve("index"));
      try (var opening = mockStatic(FileChannel.class)) {
        opening.when(() -> FileChannel.open(directory.resolve("index.index.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)).thenReturn(intercepted);
        try {
          var failure = assertThrows(IOException.class, owner::acquire);
          assertSame(acquisition, failure.getCause());
          assertSame(cleanup, failure.getCause().getSuppressed()[0].getCause());
          try (var contender = new IndexRootLock(directory.resolve("index"))) { assertThrows(IOException.class, contender::acquire); }
          opening.verify(() -> FileChannel.open(directory.resolve("index.index.lock"),
              StandardOpenOption.CREATE, StandardOpenOption.WRITE), times(1));
        } finally {
          var field = IndexRootLock.class.getDeclaredField("channel");
          field.setAccessible(true);
          field.set(owner, real);
          owner.close();
        }
      }
    }
    try (var next = new IndexRootLock(directory.resolve("index"))) { next.acquire(); }
  }

  @Test
  void metadataFailureIsToleratedWhileNativeLockRemainsValid() throws Exception {
    metadataFailure(false);
  }

  @Test
  void metadataFailureThatClosesChannelRefusesAcquisition() throws Exception {
    metadataFailure(true);
  }

  private int probe() throws Exception {
    Path output = directory.resolve("failure-probe.log");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), directory.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(child.exitValue() == 0 || child.exitValue() == 23,
          () -> "unexpected lock probe result: " + child.exitValue());
      return child.exitValue();
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      Path directory = Path.of(args[0]);
      try (var lock = new IndexRootLock(directory.resolve("index"))) {
        lock.acquire();
      } catch (IOException refused) { System.exit(23); }
    }
  }

  private void metadataFailure(boolean closes) throws Exception {
    try (FileChannel real = FileChannel.open(directory.resolve("index.index.lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileChannel intercepted = mock(FileChannel.class, org.mockito.AdditionalAnswers.delegatesTo(real));
      doAnswer(call -> {
        if (closes) real.close();
        throw new IOException("metadata write failed");
      }).when(intercepted).force(true);
      try (var opening = mockStatic(FileChannel.class); var owner = new IndexRootLock(directory.resolve("index"))) {
        opening.when(() -> FileChannel.open(directory.resolve("index.index.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)).thenReturn(intercepted);
        if (closes) assertThrows(IOException.class, owner::acquire);
        else owner.acquire();
      }
    }
    try (var next = new IndexRootLock(directory.resolve("index"))) { next.acquire(); }
  }
}
