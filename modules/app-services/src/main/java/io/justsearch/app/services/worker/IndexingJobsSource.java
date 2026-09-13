/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.IndexingJobsFrame;
import java.util.function.Consumer;

/**
 * Whatever can open a {@code SubscribeIndexingJobs} flow (lane F stage A item A6).
 *
 * <p>{@link RemoteIndexingJobsBridge} needs exactly one thing from the client — the frames — and
 * naming that thing separately is what keeps the bridge testable against a producer that is not a
 * whole {@link KnowledgeClient}. On the live path the source IS the client
 * ({@code client::subscribeIndexingJobs}).
 */
@FunctionalInterface
public interface IndexingJobsSource {

  /**
   * Opens the flow.
   *
   * @param onFrame invoked for each frame, in stream order, on the delivery thread
   * @param onError invoked once if the flow fails
   * @param onCompleted invoked once if the producer closes the flow normally
   * @return a handle whose {@code close()} stops production
   */
  KnowledgeClient.IndexingJobsStream subscribe(
      Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted);
}
