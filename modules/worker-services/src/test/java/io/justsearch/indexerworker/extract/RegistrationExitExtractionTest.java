/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.javaCommand;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The extraction caller reports the exit code of an exited start, then replaces it on demand. */
@Timeout(30)
final class RegistrationExitExtractionTest {
  @TempDir Path tmp;

  @Test
  void registrationExitReportsItsActualCodeAndGetsAReplacement() throws Exception {
    assertFailureAndReplacement("fixture startup crash");
  }

  @Test
  void registrationHeapExitReportsItsActualCodeAndGetsAReplacement() throws Exception {
    assertFailureAndReplacement("java.lang.OutOfMemoryError: fixture startup heap");
  }

  @Test
  void realRegistrationCrashWaitsForPublishedStatusAndGetsAReplacement() throws Exception {
    assertRealFailureAndReplacement("fixture startup crash");
  }

  @Test
  void realRegistrationHeapExitWaitsForPublishedStatusAndGetsAReplacement() throws Exception {
    assertRealFailureAndReplacement("java.lang.OutOfMemoryError: fixture startup heap");
  }

  private void assertRealFailureAndReplacement(String diagnostic) throws Exception {
    try (var fixture = new io.justsearch.app.api.runtime.child.RegistrationPublicationFixture(tmp, diagnostic);
        var replacement = DefaultChildControl.unrecorded(ManagedChildRegistry.noop());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      ChildControl control = mock(ChildControl.class, delegatesTo(replacement));
      AtomicInteger launches = new AtomicInteger();
      doAnswer(call -> launches.getAndIncrement() == 0
          ? fixture.control.start(call.getArgument(0), call.getArgument(1))
          : replacement.start(call.getArgument(0), call.getArgument(1)))
          .when(control).start(any(), any());
      var command = javaCommand(PersistentExtractionSandboxTest.ScriptedChild.class);
      try (var sandbox = ChildControlFixtures.pool(command, Duration.ofSeconds(10), 500, null, control)) {
        Path input = Files.writeString(tmp.resolve("ok-replacement.txt"), "test input");
        var result = executor.submit(() -> assertThrows(ContentExtractor.ExtractionException.class,
            () -> sandbox.extract(input)));
        assertTrue(fixture.publishing.await(10, TimeUnit.SECONDS));
        boolean returnedBeforePublication;
        try {
          returnedBeforePublication = fixture.returned.await(200, TimeUnit.MILLISECONDS);
        } finally {
          fixture.release();
        }
        var failure = result.get(10, TimeUnit.SECONDS);
        assertAll(
            () -> assertFalse(returnedBeforePublication, "extraction registration returned without its exit outcome"),
            () -> assertTrue(failure.getMessage().contains("37"), failure.getMessage()));
        assertEquals("ok-replacement.txt", sandbox.extract(input).result().content());
        assertEquals(2, launches.get());
        assertEquals(1, fixture.exitRecords.get());
        assertEquals(1, fixture.real.recentExits(32).size());
      }
    }
  }

  private void assertFailureAndReplacement(String diagnostic) throws Exception {
    CountDownLatch requestWritten = new CountDownLatch(1);
    try (DefaultChildControl real = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      ChildControl control = mock(ChildControl.class, delegatesTo(real));
      ChildHandle exited = mock(ChildHandle.class);
      ExitOutcome outcome = mock(ExitOutcome.class);
      when(outcome.code()).thenReturn(OptionalInt.of(23));
      when(exited.state()).thenReturn(ChildHandle.State.EXITED);
      when(exited.exit()).thenReturn(CompletableFuture.completedFuture(outcome));
      when(exited.stdin()).thenReturn(new OutputStream() {
        @Override public void write(int value) throws IOException {
          requestWritten.countDown();
          throw new IOException("fixture exited before its first request");
        }
      });
      when(exited.stdout()).thenReturn(InputStream.nullInputStream());
      when(exited.stderr()).thenReturn(new InputStream() {
        final InputStream bytes = new ByteArrayInputStream(diagnostic.getBytes(StandardCharsets.UTF_8));
        @Override public int read() throws IOException {
          try {
            if (!requestWritten.await(10, TimeUnit.SECONDS)) throw new IOException("no request written");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
          return bytes.read();
        }
      });
      AtomicInteger launches = new AtomicInteger();
      doAnswer(call -> launches.getAndIncrement() == 0 ? exited
          : real.start(call.getArgument(0), call.getArgument(1)))
          .when(control).start(any(), any());
      var command = javaCommand(PersistentExtractionSandboxTest.ScriptedChild.class);
      try (var sandbox = ChildControlFixtures.pool(command, Duration.ofSeconds(10), 500, null, control)) {
        Path input = Files.writeString(tmp.resolve("ok-replacement.txt"), "test input");
        var failure = assertThrows(ContentExtractor.ExtractionException.class, () -> sandbox.extract(input));
        assertTrue(failure.getMessage().contains("23"), failure.getMessage());
        assertEquals("ok-replacement.txt", sandbox.extract(input).result().content());
        assertEquals(2, launches.get(), "the later request starts a replacement");
      }
    } finally {
      requestWritten.countDown();
    }
  }
}
