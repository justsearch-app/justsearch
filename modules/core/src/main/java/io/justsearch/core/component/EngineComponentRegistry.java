/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Process-owned component observations and serialized configuration-apply admission. */
public interface EngineComponentRegistry extends AutoCloseable {
  ComponentHandle register(ComponentSpec spec);

  EngineComponentSnapshot snapshot();

  /**
   * Prepares an atomic observation replacement for the named registered components.
   *
   * <p>The implementation validates component identity and builds the complete post-publication
   * snapshot before the commit point. Existing implementations that do not support coordinated
   * publication retain the default refusal for source compatibility with test registries.
   */
  default PreparedBatch prepareBatch(Map<String, EngineComponentSnapshot.Component> replacements) {
    throw new UnsupportedOperationException("prepared component publication is not supported");
  }

  Subscription subscribe(Consumer<EngineComponentSnapshot> listener);

  /** The shared visibility boundary, when coordinated projections are supported. */
  default Optional<ReentrantReadWriteLock> publicationBoundary() {
    return Optional.empty();
  }

  /**
   * Installs a bounded, nonblocking state projection inside the publication boundary, including
   * initial state. The callback must perform no observer delivery, I/O or arbitrary classification.
   * Ordinary observers belong in {@link #subscribe}. Simple test registries retain replay support.
   */
  default Subscription subscribePublication(Consumer<EngineComponentSnapshot> projection) {
    Subscription subscription = subscribe(projection);
    try {
      projection.accept(snapshot());
      return subscription;
    } catch (RuntimeException | Error failure) {
      subscription.close();
      throw failure;
    }
  }

  ApplyAttempt tryApply();

  @Override
  void close();

  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  interface ApplyLease extends AutoCloseable {
    @Override
    void close();
  }

  /** A validated, single-use component observation publication. */
  interface PreparedBatch {
    /** The complete immutable snapshot that will be visible after {@link #commit()}. */
    EngineComponentSnapshot snapshot();

    /** Validates the captured registry revision while the shared publication write lock is held. */
    void validate();

    /** Installs the already validated snapshot while the shared publication write lock is held. */
    void install();

    /** Delivers the pre-captured observers after the caller releases the publication lock. */
    void notifyObservers();

    /**
     * Commits the prepared observation and delivers listeners after the publication lock is
     * released. This is equivalent to validate, install, unlock, and notifyObservers.
     */
    void commit();
  }

  sealed interface ApplyAttempt permits ApplyAttempt.Acquired, ApplyAttempt.Refused {
    record Acquired(ApplyLease lease) implements ApplyAttempt {
      public Acquired {
        if (lease == null) throw new NullPointerException("lease");
      }
    }

    record Refused(Reason reason) implements ApplyAttempt {
      public Refused {
        if (reason == null) throw new NullPointerException("reason");
      }
    }

    enum Reason {
      BUSY,
      CLOSED,
      REENTRANT
    }
  }
}
