/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A real exit held between EXITED and publication, at the process-information seam. */
public final class RegistrationPublicationFixture implements AutoCloseable {
  public CountDownLatch publishing = new CountDownLatch(1);
  public CountDownLatch returned = new CountDownLatch(1);
  public final AtomicInteger exitRecords = new AtomicInteger();
  public final DefaultChildControl real;
  public final ChildControl control;
  private CountDownLatch release = new CountDownLatch(1);
  private final Path trigger;

  public RegistrationPublicationFixture(Path directory, String diagnostic) throws Exception {
    trigger = directory.resolve("exit-trigger");
    LedgerSink ledger = (type, fields) -> {
      if (type == LedgerRecordType.CHILD_EXIT) {
        exitRecords.incrementAndGet();
        publishing.countDown();
        await(release);
      }
      return true;
    };
    real = new DefaultChildControl(ledger, new PosixProcessTerminator(137), VramProbe.none(),
        Clock.systemUTC(), DefaultChildControl.Sleeper.REAL, ManagedChildRegistry.noop(),
        new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "publication-test"),
        Thread.ofPlatform().daemon().factory(), DefaultChildControl.JobMembership.NONE,
        (builder, kind) -> {
          builder.command(command(diagnostic));
          Process process = builder.start();
          Process wrapped = mock(Process.class, delegatesTo(process));
          AtomicInteger reads = new AtomicInteger();
          when(wrapped.info()).thenAnswer(call -> {
            if (reads.incrementAndGet() == 3) {
              Files.createFile(trigger);
              if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("child did not exit");
              await(publishing);
            }
            return process.info();
          });
          return wrapped;
        });
    control = mock(ChildControl.class, delegatesTo(real));
    doAnswer(call -> {
      ChildHandle child = real.start(call.getArgument(0), call.getArgument(1));
      returned.countDown();
      return child;
    }).when(control).start(any(), any());
  }

  public List<String> command(String diagnostic) {
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), ExitChild.class.getName(),
        trigger.toString(), diagnostic);
  }

  public void nextLaunch() throws java.io.IOException {
    Files.deleteIfExists(trigger);
    publishing = new CountDownLatch(1);
    returned = new CountDownLatch(1);
    release = new CountDownLatch(1);
  }

  public void release() {
    release.countDown();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("publication seam timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @Override public void close() {
    release();
    real.close();
  }

  public static final class ExitChild {
    public static void main(String[] args) throws Exception {
      while (!Files.exists(Path.of(args[0]))) Thread.sleep(5);
      System.err.println(args[1]);
      System.exit(37);
    }
  }
}
