/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class EncoderBindingsTest {

  @Test
  void publishAndBindApisExposeTheExpectedCurrentValues() {
    EncoderBindings bindings = new EncoderBindings();
    SpladeEncoder splade = mock(SpladeEncoder.class);
    BgeM3Encoder bge = mock(BgeM3Encoder.class);

    assertNull(bindings.snapshot().spladeEncoder());
    assertNull(bindings.snapshot().bgeM3Encoder());

    bindings.publish(new EncoderBindings.Snapshot(splade, bge, null, null));
    assertSame(splade, bindings.spladeEncoder());
    assertSame(bge, bindings.bgeM3Encoder());
    assertSame(splade, bindings.snapshot().spladeEncoder());
    assertSame(bge, bindings.snapshot().bgeM3Encoder());

    SpladeEncoder replacement = mock(SpladeEncoder.class);
    bindings.bindSpladeEncoder(replacement);
    assertSame(replacement, bindings.spladeEncoder());
    assertSame(bge, bindings.bgeM3Encoder());
  }

  @Test
  void concurrentReadersSeeOnlyCompleteSnapshots() throws Exception {
    EncoderBindings bindings = new EncoderBindings();
    SpladeEncoder spladeA = mock(SpladeEncoder.class);
    SpladeEncoder spladeB = mock(SpladeEncoder.class);
    BgeM3Encoder bgeA = mock(BgeM3Encoder.class);
    BgeM3Encoder bgeB = mock(BgeM3Encoder.class);
    EncoderBindings.Snapshot a = new EncoderBindings.Snapshot(spladeA, bgeA, null, null);
    EncoderBindings.Snapshot b = new EncoderBindings.Snapshot(spladeB, bgeB, null, null);
    bindings.publish(a);

    int readerCount = 4;
    ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean publishing = new AtomicBoolean(true);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();

    tasks.add(
        executor.submit(
            () -> {
              await(start);
              for (int i = 0; i < 50_000; i++) {
                bindings.publish((i & 1) == 0 ? a : b);
              }
              publishing.set(false);
            }));
    for (int i = 0; i < readerCount; i++) {
      tasks.add(
          executor.submit(
              () -> {
                await(start);
                while (publishing.get()) {
                  assertComplete(bindings.snapshot(), spladeA, bgeA, spladeB, bgeB, failure);
                }
                for (int j = 0; j < 1_000; j++) {
                  assertComplete(bindings.snapshot(), spladeA, bgeA, spladeB, bgeB, failure);
                }
              }));
    }
    start.countDown();
    for (var task : tasks) {
      task.get(10, TimeUnit.SECONDS);
    }
    executor.shutdownNow();

    assertNull(failure.get(), "a reader observed a mixed encoder snapshot");
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
  }

  private static void assertComplete(
      EncoderBindings.Snapshot snapshot,
      SpladeEncoder spladeA,
      BgeM3Encoder bgeA,
      SpladeEncoder spladeB,
      BgeM3Encoder bgeB,
      AtomicReference<Throwable> failure) {
    boolean completeA = snapshot.spladeEncoder() == spladeA && snapshot.bgeM3Encoder() == bgeA;
    boolean completeB = snapshot.spladeEncoder() == spladeB && snapshot.bgeM3Encoder() == bgeB;
    if (!completeA && !completeB) {
      failure.compareAndSet(
          null,
          new AssertionError(
              "mixed snapshot: splade="
                  + snapshot.spladeEncoder()
                  + ", bge="
                  + snapshot.bgeM3Encoder()));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test worker interrupted", interrupted);
    }
  }
}
