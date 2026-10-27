/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Request endings must survive optional retrieval and whole-document fallbacks. */
public final class EngineRequestEnds {
  private EngineRequestEnds() {}

  public static boolean isDeadline(Throwable failure) {
    Throwable cause = cause(failure);
    return cause instanceof TimeoutException
        || cause instanceof KnowledgeClientException port
            && port.status() == KnowledgeClientException.Status.DEADLINE_EXCEEDED;
  }

  public static RuntimeException cancellation(Throwable failure) {
    Throwable cause = cause(failure);
    if (cause instanceof CancellationException cancelled) return cancelled;
    if (cause instanceof KnowledgeClientException port
        && port.status() == KnowledgeClientException.Status.CANCELLED) return port;
    return null;
  }

  public static void rethrowCancellation(Throwable failure) {
    RuntimeException cancelled = cancellation(failure);
    if (cancelled != null) throw cancelled;
  }

  public static void rethrow(Throwable failure) {
    rethrowCancellation(failure);
    if (isDeadline(failure)) {
      Throwable cause = cause(failure);
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new CompletionException(cause);
    }
  }

  private static Throwable cause(Throwable failure) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    while ((failure instanceof CompletionException || failure instanceof ExecutionException
        || failure instanceof DocumentService.UnavailableException
        // CompletableFuture.get may copy cancellation, retaining the typed original as its cause.
        || failure instanceof CancellationException && failure.getCause() instanceof CancellationException)
        && failure.getCause() != null && seen.add(failure)) {
      failure = failure.getCause();
    }
    return failure;
  }
}
