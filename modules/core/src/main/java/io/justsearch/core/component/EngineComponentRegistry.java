/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.util.function.Consumer;

/** Process-owned component observations and serialized configuration-apply admission. */
public interface EngineComponentRegistry extends AutoCloseable {
  ComponentHandle register(ComponentSpec spec);

  EngineComponentSnapshot snapshot();

  Subscription subscribe(Consumer<EngineComponentSnapshot> listener);

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
