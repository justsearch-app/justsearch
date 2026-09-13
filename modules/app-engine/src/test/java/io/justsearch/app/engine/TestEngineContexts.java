/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;

/** Explicit test callers; work axes are independent of the operation being exercised. */
final class TestEngineContexts {
  static final EngineContext FOREGROUND = EngineProvenance.internal("engine-test",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  static final EngineContext BACKGROUND = EngineProvenance.internal("engine-test-background",
      EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  private TestEngineContexts() {}
}
