/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One process-owned feedback writer. Overflow drops the new observation without caller work. */
public final class FeedbackObserver implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(FeedbackObserver.class);
  private final EngineExecutorRegistry.Registration registration;
  private final ExecutorService executor;

  public FeedbackObserver(EngineExecutorRegistry registry) {
    var limits = registry.limits(EngineExecutorSpec.Kind.BACKGROUND);
    registration = registry.register(new EngineExecutorSpec(
        "head.feedback-observer", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.PLATFORM, 1, Math.min(32, limits.maxQueue()), 1));
    try {
      executor = registration.open(runnable -> {
        Thread thread = new Thread(runnable, "feedback-observer");
        thread.setDaemon(true);
        return thread;
      });
    } catch (RuntimeException | Error failure) {
      registration.close();
      throw failure;
    }
  }

  public boolean observe(Runnable observation) {
    try {
      executor.execute(() -> {
        try {
          observation.run();
        } catch (Exception failure) {
          log.debug("Feedback observation failed (non-fatal)", failure);
        }
      });
      return true;
    } catch (RejectedExecutionException refused) {
      log.debug("Feedback observation omitted: {}", refused.toString());
      return false;
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    registration.close();
  }
}
