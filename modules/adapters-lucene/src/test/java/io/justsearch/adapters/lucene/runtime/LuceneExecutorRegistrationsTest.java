/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

class LuceneExecutorRegistrationsTest {
  @Test
  void registersBoundedCommitAndUrgencySpecificFanout() {
    RecordingRegistry registry = new RecordingRegistry();
    LuceneExecutorRegistrations bundle = new LuceneExecutorRegistrations(registry);

    assertEquals(3, registry.specs.size());
    assertEquals("head.lucene.commit-timer", registry.specs.get(0).name());
    assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registry.specs.get(0).mode());
    assertEquals(1, registry.specs.get(0).threadCount());
    assertEquals(3, registry.specs.get(0).maxInstances());
    assertEquals("head.lucene.search-fanout-foreground", registry.specs.get(1).name());
    assertEquals("head.lucene.search-fanout-background", registry.specs.get(2).name());
    assertEquals(7, registry.specs.get(1).maxInstances());

    try (ExecutorService foreground = bundle.openSearchFanout(EngineContext.Urgency.FOREGROUND);
        ExecutorService background = bundle.openSearchFanout(EngineContext.Urgency.BACKGROUND)) {
      assertEquals(EngineExecutorSpec.Mode.VIRTUAL, registry.opened.get(0));
      assertEquals(EngineExecutorSpec.Mode.VIRTUAL, registry.opened.get(1));
    }
    bundle.close();
    assertFalse(registry.closed);
    assertTrue(registry.registrationCloseCount > 0);
  }

  private static final class RecordingRegistry implements EngineExecutorRegistry {
    final List<EngineExecutorSpec> specs = new ArrayList<>();
    final List<EngineExecutorSpec.Mode> opened = new ArrayList<>();
    int registrationCloseCount;
    boolean closed;

    @Override public Registration register(EngineExecutorSpec spec) {
      specs.add(spec);
      return new Registration() {
        @Override public EngineExecutorSpec spec() { return spec; }
        @Override public ExecutorService open(ThreadFactory factory) {
          opened.add(spec.mode());
          return Executors.newFixedThreadPool(spec.threadCount(), factory);
        }
        @Override public ScheduledExecutorService openScheduled(ThreadFactory factory) {
          opened.add(spec.mode());
          return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(factory);
        }
        @Override public ExecutorService openVirtual() {
          opened.add(spec.mode());
          return Executors.newVirtualThreadPerTaskExecutor();
        }
        @Override public void close() { registrationCloseCount++; }
      };
    }

    @Override public Limits limits(EngineExecutorSpec.Kind kind) { return new Limits(4, 64); }
    @Override public int maxConcurrentWork() { return 7; }
    @Override public io.justsearch.core.execution.EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }
    @Override public void close() { closed = true; }
  }
}
