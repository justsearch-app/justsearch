/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Keep Engine refusal identity across optional fallbacks and operation result translation. */
public final class EngineRefusals {
  private EngineRefusals() {}

  public static void rethrow(Throwable failure) {
    RuntimeException refused = find(failure);
    if (refused != null) throw refused;
  }

  public static RuntimeException find(Throwable failure) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null && seen.add(failure)) {
      failure = failure.getCause();
    }
    if (failure instanceof EngineAdmissionException refused) return refused;
    if (failure instanceof EngineExecutorRejectedException refused) return refused;
    return null;
  }
}
