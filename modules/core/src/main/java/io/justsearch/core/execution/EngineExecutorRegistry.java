/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** Process-owned registry of bounded logical executors and their concrete instances. */
public interface EngineExecutorRegistry extends AutoCloseable {
  /** Loaded limits for one execution kind; consumers derive pool shapes without copying policy. */
  record Limits(int maxThreads, int maxQueue) {}

  Registration register(EngineExecutorSpec spec);

  Limits limits(EngineExecutorSpec.Kind kind);

  /** Process-wide concurrent-work ceiling used to bound per-call virtual instances. */
  int maxConcurrentWork();

  /** Running policy projection for bounded work buffers that refuse before executor submission. */
  int retryAfterSeconds();

  EngineExecutorSnapshot snapshot();

  @Override
  void close();

  interface Registration extends AutoCloseable {
    EngineExecutorSpec spec();

    ExecutorService open(ThreadFactory threadFactory);

    ScheduledExecutorService openScheduled(ThreadFactory threadFactory);

    ExecutorService openVirtual();

    @Override
    void close();
  }
}
