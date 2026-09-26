/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;

/** Resolves the immutable configuration captured for one admitted conversation turn. */
@FunctionalInterface
public interface ConversationConfigProvider {

  /** Returns the configuration bound to {@code context}'s work identity. */
  ResolvedConfig resolve(EngineContext context);
}
