/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.app.services.conversation.spi.ConversationConfigProvider;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** The immutable resolved settings owned by one admitted conversation turn. */
public final class BoundConversationConfigProvider implements ConversationConfigProvider {
  private final Supplier<ResolvedConfig> unbound;
  private final Map<Object, ResolvedConfig> turns = new HashMap<>();

  public BoundConversationConfigProvider(Supplier<ResolvedConfig> unbound) {
    this.unbound = Objects.requireNonNull(unbound, "unbound config");
  }

  public Binding bind(EngineContext context, ResolvedConfig config) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(config, "config");
    Object key = key(context);
    synchronized (turns) {
      if (turns.putIfAbsent(key, config) != null) {
        throw new IllegalStateException("Conversation config already bound for this work");
      }
    }
    return new Binding(key, config);
  }

  @Override
  public ResolvedConfig resolve(EngineContext context) {
    Objects.requireNonNull(context, "context");
    ResolvedConfig captured;
    synchronized (turns) { captured = turns.get(key(context)); }
    return captured == null ? Objects.requireNonNull(unbound.get(), "resolved config") : captured;
  }

  private static Object key(EngineContext context) {
    return context.workId().<Object>map(id -> id).orElseGet(() -> new ContextIdentity(context));
  }

  private record ContextIdentity(EngineContext context) {
    @Override public boolean equals(Object other) {
      return other instanceof ContextIdentity identity && identity.context == context;
    }

    @Override public int hashCode() { return System.identityHashCode(context); }
  }

  public final class Binding implements AutoCloseable {
    private final Object key;
    private final ResolvedConfig config;
    private boolean closed;

    private Binding(Object key, ResolvedConfig config) {
      this.key = key;
      this.config = config;
    }

    @Override public void close() {
      synchronized (turns) {
        if (closed) return;
        closed = true;
        if (!turns.remove(key, config)) {
          throw new IllegalStateException("Conversation config binding changed unexpectedly");
        }
      }
    }
  }
}
