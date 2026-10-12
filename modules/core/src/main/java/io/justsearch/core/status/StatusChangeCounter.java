/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.status;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local status invalidation clock. State owners advance it at publication, under their
 * own serialization boundary, using immutable status-relevant values. Reads never call an owner,
 * acquire a publication lock, sample hardware or perform I/O. This is an invalidation signal, not
 * another state store. The epoch distinguishes a restarted head from its predecessor.
 */
public final class StatusChangeCounter {
  private static final StatusChangeCounter PROCESS = new StatusChangeCounter();
  private final String epoch = UUID.randomUUID().toString();
  private final AtomicLong value = new AtomicLong();

  public static StatusChangeCounter process() { return PROCESS; }
  public String epoch() { return epoch; }
  public long current() { return value.get(); }

  /** Called only after a successful publication, never for acceptance, replay or a read. */
  public void changed() { value.incrementAndGet(); }

  public void changed(Object before, Object after) {
    if (!Objects.equals(before, after)) changed();
  }
}
