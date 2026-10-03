/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.core.execution.TestEngineExecutors;

/** Small real-executor registrations for component tests that close their concrete instances. */
public final class TestWorkerExecutorRegistrations {
  private TestWorkerExecutorRegistrations() {}

  public static EngineExecutorRegistry.Registration ocr() {
    return new TestEngineExecutors().register(new EngineExecutorSpec(
        "test-pdf-ocr", Kind.BACKGROUND, Mode.PLATFORM, 4, 64, 1));
  }

  public static java.util.function.IntFunction<java.util.concurrent.ExecutorService> ocrFactory() {
    var owner = ocr();
    return workers -> owner.open(Thread.ofPlatform().daemon().name("test-pdf-ocr-", 0).factory());
  }

  public static EngineExecutorRegistry.Registration timebox() {
    return new TestEngineExecutors()
        .register(
            new EngineExecutorSpec(
                "test-extraction-timebox", Kind.BACKGROUND, Mode.PLATFORM, 1, 4, 2));
  }

  public static EngineExecutorRegistry.Registration readers() {
    return new TestEngineExecutors()
        .register(
            new EngineExecutorSpec(
                "test-sandbox-readers", Kind.BACKGROUND, Mode.PLATFORM, 4, 4, 2));
  }

  public static EngineExecutorRegistry.Registration watcher() {
    return new TestEngineExecutors()
        .register(
            new EngineExecutorSpec(
                "test-watcher", Kind.BACKGROUND, Mode.SCHEDULED, 1, 64, 2));
  }
}
